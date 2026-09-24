package texsuite;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import texsuite.DocumentSnapshot.SourceContext;

final class TexSourceScanner {
    private static final Set<String> MATH_ENVIRONMENTS = Set.of("math", "displaymath",
            "equation", "equation*", "align", "align*", "gather", "gather*",
            "multline", "multline*", "flalign", "flalign*", "eqnarray", "eqnarray*");
    private static final Set<String> VERBATIM_ENVIRONMENTS = Set.of("verbatim", "verbatim*",
            "lstlisting", "minted", "filecontents", "filecontents*", "comment");
    private static final Set<String> TEXT_ARGUMENTS = Set.of("text", "mbox", "operatorname",
            "textrm", "textup", "textbf", "textit", "texttt");
    private static final Set<String> METADATA_ARGUMENTS = Set.of("label", "ref", "eqref",
            "pageref", "cite", "citep", "citet", "cref", "Cref", "autoref", "url");
    private static final Set<String> DEFINITION_COMMANDS = Set.of("def", "gdef", "edef",
            "xdef", "newcommand", "renewcommand", "providecommand", "DeclareRobustCommand",
            "newenvironment", "renewenvironment");

    private final String source;
    private final int[] byteOffsets;
    private final int[] lines;
    private final List<RawOccurrence> occurrences = new ArrayList<>();
    private final List<IncludeReference> includes = new ArrayList<>();
    private final List<AssetReference> assets = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();
    private final List<ProtectedRegion> protectedRegions = new ArrayList<>();
    private final List<String> environments = new ArrayList<>();
    private final List<ConditionalState> conditionals = new ArrayList<>();
    private char occurrenceTarget;
    private String mathDelimiter;
    private String verbatimEnvironment;
    private int mathEnvironmentDepth;
    private int braceDepth;
    private int mathStart;
    private int commentStart = -1;
    private int verbatimStart = -1;
    private boolean comment;
    private boolean unknown;

    TexSourceScanner(String source) {
        this.source = source;
        byteOffsets = new int[source.length() + 1];
        lines = new int[source.length() + 1];
        int bytes = 0;
        int line = 1;
        for (int index = 0; index < source.length();) {
            int codePoint = source.codePointAt(index);
            int width = Character.charCount(codePoint);
            for (int part = 0; part < width; part++) {
                byteOffsets[index + part] = bytes;
                lines[index + part] = line;
            }
            bytes += utf8Width(codePoint);
            if (codePoint == '\r' || codePoint == '\n'
                    && (index == 0 || source.charAt(index - 1) != '\r')) {
                line++;
            }
            index += width;
        }
        byteOffsets[source.length()] = bytes;
        lines[source.length()] = line;
    }

    Scan scan() {
        return scan('\0');
    }

    Scan scanFor(char target) {
        if (!asciiLetter(target)) {
            throw new IllegalArgumentException("occurrence target must be an ASCII letter");
        }
        return scan(target);
    }

    private Scan scan(char target) {
        occurrences.clear();
        includes.clear();
        assets.clear();
        problems.clear();
        protectedRegions.clear();
        environments.clear();
        conditionals.clear();
        mathDelimiter = null;
        verbatimEnvironment = null;
        mathEnvironmentDepth = 0;
        braceDepth = 0;
        mathStart = 0;
        commentStart = -1;
        verbatimStart = -1;
        comment = false;
        unknown = false;
        occurrenceTarget = target;
        for (int index = 0; index < source.length();) {
            char character = source.charAt(index);
            if (comment) {
                record(index, SourceContext.COMMENT);
                if (lineBreak(character)) {
                    comment = false;
                    protectedRegions.add(new ProtectedRegion(commentStart, index + 1,
                            SourceContext.COMMENT));
                    commentStart = -1;
                }
                index++;
                continue;
            }
            if (verbatimEnvironment != null) {
                String ending = "\\end{" + verbatimEnvironment + "}";
                if (source.startsWith(ending, index)) {
                    recordRange(index, index + ending.length(), SourceContext.VERBATIM);
                    protectedRegions.add(new ProtectedRegion(verbatimStart,
                            index + ending.length(), SourceContext.VERBATIM));
                    index += ending.length();
                    environments.remove(environments.size() - 1);
                    verbatimEnvironment = null;
                    verbatimStart = -1;
                } else {
                    record(index, SourceContext.VERBATIM);
                    index++;
                }
                continue;
            }
            if (character == '%') {
                comment = true;
                commentStart = index;
                index++;
                continue;
            }
            if (character == '\\') {
                index = scanCommand(index);
                continue;
            }
            if (character == '$') {
                int width = index + 1 < source.length() && source.charAt(index + 1) == '$' ? 2 : 1;
                toggleMath(width == 2 ? "$$" : "$", index);
                index += width;
                continue;
            }
            if (character == '{') {
                braceDepth++;
            } else if (character == '}') {
                if (braceDepth == 0) {
                    problem(index, "UNBALANCED_BRACE", "unexpected closing brace");
                    unknown = true;
                } else {
                    braceDepth--;
                }
            }
            if (occurrenceTarget != '\0') {
                record(index, unknown ? SourceContext.UNKNOWN
                        : inMath() ? SourceContext.MATH : SourceContext.PROSE);
            }
            index++;
        }
        if (comment) {
            protectedRegions.add(new ProtectedRegion(commentStart, source.length(),
                    SourceContext.COMMENT));
        }
        if (verbatimEnvironment != null) {
            protectedRegions.add(new ProtectedRegion(verbatimStart, source.length(),
                    SourceContext.VERBATIM));
        }
        if (mathDelimiter != null || mathEnvironmentDepth > 0 || braceDepth != 0
                || !environments.isEmpty() || verbatimEnvironment != null) {
            problem(source.length(), "UNCLOSED_STRUCTURE", "unclosed delimiter, group, or environment");
            int uncertainFrom = mathDelimiter != null && mathEnvironmentDepth == 0
                    && braceDepth == 0 && environments.isEmpty() ? mathStart : 0;
            for (int index = 0; index < occurrences.size(); index++) {
                RawOccurrence occurrence = occurrences.get(index);
                if (occurrence.reason() == SourceContext.MATH
                        && occurrence.charIndex() >= uncertainFrom) {
                    occurrences.set(index, new RawOccurrence(occurrence.charIndex(),
                            SourceContext.UNKNOWN, occurrence.conditional()));
                }
            }
        }
        if (!conditionals.isEmpty()) {
            problem(source.length(), "UNCLOSED_CONDITIONAL", "conditional has no matching \\fi");
        }
        return new Scan(List.copyOf(occurrences), List.copyOf(includes),
                List.copyOf(assets), List.copyOf(problems), List.copyOf(protectedRegions));
    }

    int byteOffset(int charIndex) {
        return byteOffsets[charIndex];
    }

    int line(int charIndex) {
        return lines[charIndex];
    }

    boolean adjacentLetters(int charIndex) {
        return charIndex > 0 && asciiLetter(source.charAt(charIndex - 1))
                || charIndex + 1 < source.length() && asciiLetter(source.charAt(charIndex + 1));
    }

    private int scanCommand(int start) {
        int end = start + 1;
        if (end >= source.length()) {
            problem(start, "DANGLING_ESCAPE", "backslash at end of file");
            unknown = true;
            return end;
        }
        if (asciiLetter(source.charAt(end)) || source.charAt(end) == '@') {
            while (end < source.length()
                    && (asciiLetter(source.charAt(end)) || source.charAt(end) == '@')) {
                end++;
            }
        } else {
            end++;
        }
        String command = source.substring(start + 1, end);
        recordRange(start, end, SourceContext.CONTROL_SEQUENCE);
        if (command.equals("(") || command.equals("[")) {
            openMath(command.equals("(") ? "\\(" : "\\[", start);
            return end;
        }
        if (command.equals(")") || command.equals("]")) {
            closeMath(command.equals(")") ? "\\(" : "\\[", start);
            return end;
        }
        if (command.equals("verb")) {
            return scanInlineVerb(start, end);
        }
        if (command.equals("begin") || command.equals("end")) {
            return scanEnvironment(command, end, start);
        }
        if (DEFINITION_COMMANDS.contains(command)) {
            int definitionEnd = definitionEnd(command, end);
            if (definitionEnd < 0) {
                problem(start, "UNSUPPORTED_DEFINITION", "cannot locate complete definition body");
                unknown = true;
                return end;
            }
            recordRange(end, definitionEnd, SourceContext.DEFINITION);
            protectedRegions.add(new ProtectedRegion(start, definitionEnd,
                    SourceContext.DEFINITION));
            return definitionEnd;
        }
        if (command.equals("input") || command.equals("include")) {
            return scanInclude(command, end, start);
        }
        if (command.equals("includegraphics") || command.equals("addbibresource")
                || command.equals("bibliography")) {
            return scanAsset(command, end, start);
        }
        if (TEXT_ARGUMENTS.contains(command) || METADATA_ARGUMENTS.contains(command)
                || command.equals("ensuremath")) {
            return scanProtectedArgument(command, end);
        }
        if (command.equals("catcode") || command.equals("ExplSyntaxOn")) {
            problem(start, "UNSUPPORTED_CATCODES", "category-code changes need manual review");
            unknown = true;
        }
        if (command.equals("newif")) {
            int nameStart = skipSpaces(end);
            if (source.startsWith("\\if", nameStart)) {
                int nameEnd = nameStart + 3;
                while (nameEnd < source.length() && asciiLetter(source.charAt(nameEnd))) {
                    nameEnd++;
                }
                recordRange(end, nameEnd, SourceContext.DEFINITION);
                protectedRegions.add(new ProtectedRegion(start, nameEnd,
                        SourceContext.DEFINITION));
                return nameEnd;
            }
            problem(start, "UNSUPPORTED_DEFINITION", "conditional name is not a control sequence");
            unknown = true;
        }
        if (command.equals("fi") || command.equals("else") || command.equals("or")) {
            if (conditionals.isEmpty()) {
                problem(start, "UNMATCHED_CONDITIONAL", "conditional boundary has no tracked opening");
                unknown = true;
            } else {
                ConditionalState initial = conditionals.getLast();
                if (!initial.equals(conditionalState())) {
                    problem(start, "CONDITIONAL_STRUCTURE",
                            "conditional branch changes delimiter, group, or environment state");
                    unknown = true;
                }
                if (command.equals("fi")) {
                    conditionals.removeLast();
                }
            }
        } else if (command.startsWith("if") && !command.equals("ifthenelse")
                && !command.equals("iff")) {
            conditionals.add(conditionalState());
        }
        return end;
    }

    private int scanInlineVerb(int start, int afterCommand) {
        int delimiterIndex = afterCommand;
        if (delimiterIndex < source.length() && source.charAt(delimiterIndex) == '*') {
            delimiterIndex++;
        }
        if (delimiterIndex >= source.length() || lineBreak(source.charAt(delimiterIndex))) {
            problem(afterCommand, "UNCLOSED_VERB", "inline verbatim has no delimiter");
            unknown = true;
            return afterCommand;
        }
        char delimiter = source.charAt(delimiterIndex);
        int end = delimiterIndex + 1;
        while (end < source.length() && source.charAt(end) != delimiter
                && !lineBreak(source.charAt(end))) {
            end++;
        }
        if (end < source.length() && source.charAt(end) == delimiter) {
            end++;
        } else {
            problem(delimiterIndex, "UNCLOSED_VERB", "inline verbatim has no closing delimiter");
        }
        recordRange(afterCommand, end, SourceContext.VERBATIM);
        protectedRegions.add(new ProtectedRegion(start, end, SourceContext.VERBATIM));
        return end;
    }

    private int scanEnvironment(String command, int afterCommand, int start) {
        int argumentStart = skipSpaces(afterCommand);
        Group argument = groupAt(argumentStart, '{', '}');
        if (argument == null) {
            problem(start, "UNSUPPORTED_ENVIRONMENT", "environment name is not a literal group");
            unknown = true;
            return afterCommand;
        }
        recordRange(afterCommand, argument.end(), SourceContext.METADATA);
        protectedRegions.add(new ProtectedRegion(argumentStart, argument.end(),
                SourceContext.METADATA));
        String name = source.substring(argumentStart + 1, argument.end() - 1);
        if (command.equals("begin")) {
            environments.add(name);
            if (MATH_ENVIRONMENTS.contains(name)) {
                mathEnvironmentDepth++;
            }
            if (VERBATIM_ENVIRONMENTS.contains(name)) {
                verbatimEnvironment = name;
                verbatimStart = start;
            }
        } else if (environments.isEmpty()
                || !environments.get(environments.size() - 1).equals(name)) {
            problem(start, "MISMATCHED_ENVIRONMENT", "environment close does not match open");
            unknown = true;
        } else {
            environments.remove(environments.size() - 1);
            if (MATH_ENVIRONMENTS.contains(name)) {
                mathEnvironmentDepth--;
            }
        }
        return argument.end();
    }

    private int scanInclude(String command, int afterCommand, int start) {
        int argumentStart = skipSpaces(afterCommand);
        Group argument = groupAt(argumentStart, '{', '}');
        int end;
        String literal;
        if (argument != null) {
            end = argument.end();
            literal = source.substring(argumentStart + 1, end - 1).trim();
        } else if (command.equals("input")) {
            end = argumentStart;
            while (end < source.length() && !Character.isWhitespace(source.charAt(end))
                    && source.charAt(end) != '%' && source.charAt(end) != '}') {
                end++;
            }
            literal = source.substring(argumentStart, end);
        } else {
            end = argumentStart;
            literal = "";
        }
        if (end <= argumentStart || !literal.matches("[A-Za-z0-9_./-]+")) {
            problem(start, "UNRESOLVED_INCLUDE", "include path is not a supported literal");
            recordRange(afterCommand, end, SourceContext.UNKNOWN);
            protectedRegions.add(new ProtectedRegion(afterCommand, Math.max(end, afterCommand),
                    SourceContext.UNKNOWN));
            includes.add(new IncludeReference(start, literal, !conditionals.isEmpty(), false));
            return Math.max(end, afterCommand);
        }
        recordRange(afterCommand, end, SourceContext.METADATA);
        protectedRegions.add(new ProtectedRegion(afterCommand, end,
                SourceContext.METADATA));
        includes.add(new IncludeReference(start, literal, !conditionals.isEmpty() || unknown, true));
        return end;
    }

    private int scanAsset(String command, int afterCommand, int start) {
        int position = argumentStart(afterCommand);
        Group argument = groupAt(position, '{', '}');
        if (argument == null) {
            problem(start, "UNRESOLVED_ASSET", "asset path is not a literal group");
            unknown = true;
            return afterCommand;
        }
        recordRange(afterCommand, argument.end(), SourceContext.METADATA);
        protectedRegions.add(new ProtectedRegion(afterCommand, argument.end(),
                SourceContext.METADATA));
        assets.add(new AssetReference(start,
                command, source.substring(position + 1, argument.end() - 1)));
        return argument.end();
    }

    private int scanProtectedArgument(String command, int afterCommand) {
        int position = argumentStart(afterCommand);
        Group argument = groupAt(position, '{', '}');
        if (position >= source.length()) {
            recordRange(afterCommand, position, SourceContext.UNKNOWN);
            return position;
        }
        if (argument == null && (source.charAt(position) == '{'
                || source.charAt(position) == '[')) {
            problem(position, "UNSUPPORTED_ARGUMENT", "protected argument is not balanced");
            unknown = true;
            return afterCommand;
        }
        int end = argument == null ? position + 1 : argument.end();
        if (argument == null && source.charAt(position) == '\\' && end < source.length()) {
            if (asciiLetter(source.charAt(end))) {
                while (end < source.length() && asciiLetter(source.charAt(end))) {
                    end++;
                }
            } else {
                end++;
            }
        }
        SourceContext reason = METADATA_ARGUMENTS.contains(command)
                ? SourceContext.METADATA
                : command.equals("ensuremath") ? SourceContext.UNKNOWN
                : SourceContext.TEXT_ARGUMENT;
        recordRange(afterCommand, end, reason);
        protectedRegions.add(new ProtectedRegion(afterCommand, end, reason));
        return end;
    }

    private int argumentStart(int afterCommand) {
        int position = skipSpaces(afterCommand);
        if (position < source.length() && source.charAt(position) == '*') {
            position = skipSpaces(position + 1);
        }
        Group option;
        while ((option = groupAt(position, '[', ']')) != null) {
            position = skipSpaces(option.end());
        }
        return position;
    }

    private int definitionEnd(String command, int afterCommand) {
        int position = skipSpaces(afterCommand);
        if (command.equals("def") || command.equals("gdef") || command.equals("edef")
                || command.equals("xdef")) {
            while (position < source.length() && source.charAt(position) != '{'
                    && !lineBreak(source.charAt(position))) {
                position++;
            }
            Group body = groupAt(position, '{', '}');
            return body == null ? -1 : body.end();
        }
        if (position < source.length() && source.charAt(position) == '*') {
            position = skipSpaces(position + 1);
        }
        Group name = groupAt(position, '{', '}');
        if (name != null) {
            position = name.end();
        } else if (position < source.length() && source.charAt(position) == '\\') {
            position++;
            while (position < source.length() && asciiLetter(source.charAt(position))) {
                position++;
            }
        } else {
            return -1;
        }
        position = skipSpaces(position);
        for (int optionCount = 0; optionCount < 2; optionCount++) {
            Group option = groupAt(position, '[', ']');
            if (option == null) {
                break;
            }
            position = skipSpaces(option.end());
        }
        Group body = groupAt(position, '{', '}');
        if (body == null) {
            return -1;
        }
        position = body.end();
        if (command.equals("newenvironment") || command.equals("renewenvironment")) {
            Group endBody = groupAt(skipSpaces(position), '{', '}');
            if (endBody == null) {
                return -1;
            }
            position = endBody.end();
        }
        return position;
    }

    private Group groupAt(int start, char opening, char closing) {
        if (start >= source.length() || source.charAt(start) != opening) {
            return null;
        }
        int depth = 0;
        int braces = 0;
        for (int index = start; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '\\' && index + 1 < source.length()) {
                // Consume the control symbol, so \\ does not escape the following brace.
                index++;
            } else if (character == '%') {
                while (index < source.length() && !lineBreak(source.charAt(index))) {
                    index++;
                }
            } else if (opening == '[' && character == '{') {
                braces++;
            } else if (opening == '[' && character == '}' && braces > 0) {
                braces--;
            } else if (braces > 0) {
                continue;
            } else if (character == opening) {
                depth++;
            } else if (character == closing && --depth == 0) {
                return new Group(index + 1);
            }
        }
        return null;
    }

    private void toggleMath(String delimiter, int index) {
        if (mathDelimiter == null) {
            openMath(delimiter, index);
        } else {
            closeMath(delimiter, index);
        }
    }

    private void openMath(String delimiter, int index) {
        if (mathDelimiter != null) {
            problem(index, "NESTED_MATH_DELIMITER", "math delimiter opened inside math");
            unknown = true;
        } else {
            mathDelimiter = delimiter;
            mathStart = index;
        }
    }

    private void closeMath(String delimiter, int index) {
        if (!delimiter.equals(mathDelimiter)) {
            problem(index, "MISMATCHED_MATH_DELIMITER", "math delimiter does not match open");
            unknown = true;
        } else {
            mathDelimiter = null;
        }
    }

    private boolean inMath() {
        return mathDelimiter != null || mathEnvironmentDepth > 0;
    }

    private ConditionalState conditionalState() {
        return new ConditionalState(mathDelimiter, braceDepth, List.copyOf(environments));
    }

    private void recordRange(int start, int end, SourceContext reason) {
        if (occurrenceTarget == '\0') {
            return;
        }
        for (int index = start; index < end; index++) {
            record(index, reason);
        }
    }

    private void record(int index, SourceContext reason) {
        if (occurrenceTarget != '\0' && source.charAt(index) == occurrenceTarget) {
            occurrences.add(new RawOccurrence(index, reason, !conditionals.isEmpty()));
        }
    }

    private void problem(int charIndex, String code, String detail) {
        problems.add(new Problem(byteOffset(charIndex), code, detail));
    }

    private int skipSpaces(int index) {
        while (index < source.length()) {
            if (Character.isWhitespace(source.charAt(index))) {
                index++;
            } else if (source.charAt(index) == '%') {
                while (index < source.length() && !lineBreak(source.charAt(index))) {
                    index++;
                }
            } else {
                break;
            }
        }
        return index;
    }

    private static boolean lineBreak(char character) {
        return character == '\r' || character == '\n';
    }

    private static boolean asciiLetter(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z';
    }

    private static int utf8Width(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }

    record Scan(List<RawOccurrence> occurrences, List<IncludeReference> includes,
            List<AssetReference> assets, List<Problem> problems,
            List<ProtectedRegion> protectedRegions) { }
    record RawOccurrence(int charIndex, SourceContext reason, boolean conditional) { }
    record IncludeReference(int charIndex, String literal, boolean conditional,
            boolean supported) { }
    record AssetReference(int charIndex, String command, String literal) { }
    record Problem(int byteOffset, String code, String detail) { }
    record ProtectedRegion(int startChar, int endChar, SourceContext reason) { }
    private record Group(int end) { }
    private record ConditionalState(String mathDelimiter, int braceDepth,
            List<String> environments) { }
}
