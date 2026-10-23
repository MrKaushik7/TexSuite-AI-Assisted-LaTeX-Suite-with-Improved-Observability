package texsuite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import texsuite.DocumentSnapshot.SourceContext;

/** Bounded lexical cleanup under standard TeX whitespace rules; never executes TeX. */
final class SanitationPlanner {
    static final String TRAILING = "trailing-ascii-whitespace";
    static final String EOL = "line-ending";
    static final int MAX_EDITS = 200;
    private static final int MAX_FINDINGS = 200;
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "\\\\(?:catcode|endlinechar|newlinechar|escapechar|obeyspaces|obeylines|obeycr|"
            + "ExplSyntaxOn|makeatletter|scantokens|csname|let|futurelet|read|readline|"
            + "everypar|everyeof|everyjob|everycr|lccode|uccode|lowercase|uppercase|"
            + "chardef|mathchardef|newenvironment|renewenvironment|newif|newcommand|"
            + "renewcommand|providecommand|DeclareRobustCommand|def|gdef|edef|xdef)"
            + "(?![A-Za-z@])|\\^\\^");
    private static final Set<String> DEFINITIONS = Set.of("newcommand", "renewcommand", "providecommand",
            "DeclareRobustCommand", "def", "gdef", "edef", "xdef");
    private static final Pattern PACKAGE = Pattern.compile(
            "\\\\(?:usepackage|RequirePackage)\\h*(?:\\[([^\\]\\r\\n]*)\\]\\h*)?\\{([A-Za-z0-9_,. -]+)\\}");
    private static final Pattern COMMAND = Pattern.compile("\\\\(?:[A-Za-z@]+|[^\\r\\n])");
    private static final Pattern ENVIRONMENT = Pattern.compile("\\\\begin\\{([^}\\r\\n]+)\\}");
    private static final Set<String> STANDARD_ENVIRONMENTS = Set.of("document", "itemize", "enumerate",
            "description", "center", "flushleft", "flushright", "quote", "quotation", "verse",
            "abstract", "table", "table*", "figure", "figure*", "tabular", "tabular*", "minipage",
            "math", "displaymath", "equation", "equation*", "align", "align*", "gather", "gather*",
            "multline", "multline*", "flalign", "flalign*", "eqnarray", "eqnarray*", "verbatim",
            "verbatim*", "lstlisting", "minted", "filecontents", "filecontents*", "comment");

    Report inspect(DocumentSnapshot snapshot) {
        var findings = new Findings();
        for (var diagnostic : snapshot.diagnostics()) {
            var source = snapshot.files().get(diagnostic.source());
            var scanner = source == null ? null : new TexSourceScanner(text(source.bytes()));
            int index = scanner == null ? 0 : scanner.charIndex(diagnostic.byteOffset());
            findings.add(new Finding(diagnostic.source(), scanner == null ? 1 : scanner.line(index),
                    scanner == null ? 1 : scanner.editorColumn(index), diagnostic.code(), diagnostic.detail()));
        }

        boolean blocked = !snapshot.complete();
        // A dependency can change character/space interpretation in the selected file too.
        for (var file : snapshot.files().values().stream().sorted(java.util.Comparator.comparing(
                DocumentSnapshot.SourceFile::path)).toList()) {
            String source = text(file.bytes());
            var scanner = new TexSourceScanner(source);
            var scan = scanner.scanForSanitation();
            if (!scan.problems().isEmpty()) blocked = true;
            Hazard hazard = unsupported(source, scanner);
            if (hazard != null) {
                blocked = true;
                findings.add(new Finding(file.path(), scanner.line(hazard.index()), scanner.editorColumn(hazard.index()),
                        "UNSUPPORTED_WHITESPACE", hazard.message()
                        + "; automatic whitespace fixes withheld for this file's dependency closure."));
            }
        }

        Path path = snapshot.main();
        byte[] bytes = snapshot.files().get(path).bytes();
        String source = text(bytes);
        var scanner = new TexSourceScanner(source);
        scanner.scanForSanitation();
        unicode(source, scanner, path, findings);
        packages(source, scanner, path, findings);

        var trailing = new ArrayList<TextEditPlan.Edit>();
        var lf = new ArrayList<TextEditPlan.Edit>();
        var crlf = new ArrayList<TextEditPlan.Edit>();
        int trailingCount = 0;
        int skipped = 0;
        int lfCount = 0;
        int crlfCount = 0;
        int crCount = 0;
        boolean lfSafe = !blocked;
        boolean crlfSafe = !blocked;
        for (int start = 0; start < source.length();) {
            int end = start;
            while (end < source.length() && source.charAt(end) != '\r' && source.charAt(end) != '\n') end++;
            int next = end;
            if (next < source.length()) {
                if (source.charAt(next++) == '\r') {
                    if (next < source.length() && source.charAt(next) == '\n') { next++; crlfCount++; }
                    else crCount++;
                } else lfCount++;
            }
            int spaces = end;
            while (spaces > start && (source.charAt(spaces - 1) == ' ' || source.charAt(spaces - 1) == '\t')) spaces--;
            boolean ordinary = !blocked && ordinaryLine(source, scanner, start, end);
            if (spaces < end) {
                if (ordinary) {
                    trailingCount++;
                    if (trailing.size() <= MAX_EDITS) trailing.add(edit(path, scanner, bytes, spaces, end, "", TRAILING));
                } else skipped++;
            }
            if (next > end) {
                String ending = source.substring(end, next);
                boolean safe = ordinary && scanner.ordinaryProse(end, next);
                if (!ending.equals("\n")) {
                    lfSafe &= safe;
                    if (lf.size() <= MAX_EDITS) lf.add(edit(path, scanner, bytes, end, next, "\n", EOL));
                }
                if (!ending.equals("\r\n")) {
                    crlfSafe &= safe;
                    if (crlf.size() <= MAX_EDITS) crlf.add(edit(path, scanner, bytes, end, next, "\r\n", EOL));
                }
            }
            start = next;
        }
        if (blocked) findings.add(new Finding(path, 1, 1, "FIXES_WITHHELD",
                "Incomplete dependencies, structural uncertainty or unsupported whitespace semantics; repair manually and inspect again."));
        if (skipped > 0) findings.add(new Finding(path, 1, 1, "PROTECTED_TRAILING",
                skipped + " trailing whitespace span(s) kept: only unconditional plain prose lines are supported."));
        if (trailingCount > MAX_EDITS) {
            trailing.clear();
            findings.add(new Finding(path, 1, 1, "FIX_LIMIT", trailingCount
                    + " trailing fixes exceed the 200-edit limit; reduce the dirty spans manually, then inspect again."));
        }
        String lfBlock = !lfSafe ? "A changed terminator is protected/uncertain; LF normalization withheld as a whole."
                : lf.size() > MAX_EDITS ? "LF normalization exceeds the 200-edit limit." : null;
        String crlfBlock = !crlfSafe ? "A changed terminator is protected/uncertain; CRLF normalization withheld as a whole."
                : crlf.size() > MAX_EDITS ? "CRLF normalization exceeds the 200-edit limit." : null;
        return new Report(List.copyOf(trailing), lfBlock == null ? List.copyOf(lf) : List.of(),
                crlfBlock == null ? List.copyOf(crlf) : List.of(), lfBlock, crlfBlock,
                lfCount, crlfCount, crCount, List.copyOf(findings.items), findings.omitted);
    }

    private static boolean ordinaryLine(String source, TexSourceScanner scanner, int start, int end) {
        if (source.substring(start, end).isBlank() || !scanner.ordinaryProse(start, end)) return false;
        for (int index = start; index < end; index++) {
            if ("\\{}$%".indexOf(source.charAt(index)) >= 0) return false;
        }
        return true;
    }

    private static Hazard unsupported(String source, TexSourceScanner scanner) {
        var matcher = UNSUPPORTED.matcher(source);
        while (matcher.find()) {
            SourceContext context = scanner.contextAt(matcher.start());
            // Encoded input is processed before ordinary lexical comment assumptions.
            if ((context == SourceContext.COMMENT || context == SourceContext.VERBATIM)
                    && !matcher.group().equals("^^")) continue;
            String command = matcher.group().substring(1);
            // Ordinary definitions are protected. Their contents are still checked for hazards.
            if (DEFINITIONS.contains(command)) continue;
            return new Hazard(matcher.start(), "Unsupported construct " + matcher.group());
        }
        var environments = ENVIRONMENT.matcher(source);
        while (environments.find()) {
            if (scanner.contextAt(environments.start()) == SourceContext.CONTROL_SEQUENCE
                    && !STANDARD_ENVIRONMENTS.contains(environments.group(1))) {
                return new Hazard(environments.start(), "Unknown environment " + environments.group(1));
            }
        }
        // A definition can invoke another command that changes subsequent tokenization.
        // Only command-free literal bodies are understood; the first token is its name.
        var commands = COMMAND.matcher(source);
        int definitionNames = 0;
        boolean inDefinition = false;
        for (int index = 0; index < source.length(); index++) {
            boolean definition = scanner.contextAt(index) == SourceContext.DEFINITION;
            if (definition && !inDefinition) definitionNames = 0;
            inDefinition = definition;
            if (definition && source.charAt(index) == '\\' && commands.find(index)
                    && commands.start() == index && ++definitionNames > 1) {
                return new Hazard(index, "Opaque macro definition containing " + commands.group());
            }
        }
        return null;
    }

    private static void packages(String source, TexSourceScanner scanner, Path path, Findings findings) {
        Map<String, String> seen = new HashMap<>();
        var matcher = PACKAGE.matcher(source);
        while (matcher.find()) {
            if (scanner.contextAt(matcher.start()) != SourceContext.CONTROL_SEQUENCE
                    || scanner.withinDocument(matcher.start(), matcher.end())
                    || !scanner.unconditional(matcher.start(), matcher.end())) continue;
            String options = matcher.group(1) == null ? "" : matcher.group(1);
            for (String name : matcher.group(2).split(",")) {
                name = name.trim();
                if (name.isEmpty()) continue;
                String previous = seen.putIfAbsent(name, options);
                if (previous != null) findings.add(new Finding(path, scanner.line(matcher.start()),
                        scanner.editorColumn(matcher.start()), previous.equals(options) ? "DUPLICATE_PACKAGE" : "PACKAGE_OPTIONS",
                        name + (previous.equals(options) ? " imported again with identical literal options."
                                : " imported with differing literal options; inspect for conflicts.") + " Report only."));
            }
        }
    }

    private static void unicode(String source, TexSourceScanner scanner, Path path, Findings findings) {
        for (int index = 0; index < source.length();) {
            int point = source.codePointAt(index);
            if ((point > 127 && (Character.isWhitespace(point) || Character.isSpaceChar(point)))
                    || Character.getType(point) == Character.FORMAT
                    || point == 0x2212 || point == 0xff3c || point == 0xff5b || point == 0xff5d
                    || point == 0x2018 || point == 0x2019 || point == 0x201c || point == 0x201d) {
                findings.add(new Finding(path, scanner.line(index), scanner.editorColumn(index), "UNICODE_REVIEW",
                        String.format("U+%04X %s; may be intentional. Report only.", point, Character.getName(point))));
            }
            index += Character.charCount(point);
        }
    }

    private static TextEditPlan.Edit edit(Path path, TexSourceScanner scanner, byte[] bytes,
            int start, int end, String replacement, String rule) {
        int first = scanner.byteOffset(start);
        int last = scanner.byteOffset(end);
        return new TextEditPlan.Edit(path, first, last, Arrays.copyOfRange(bytes, first, last),
                scanner.line(start), scanner.editorColumn(start), replacement, rule);
    }

    static boolean validFix(String rule, String oldText, String newText) {
        if (TRAILING.equals(rule)) return !oldText.isEmpty() && newText.isEmpty()
                && oldText.chars().allMatch(character -> character == ' ' || character == '\t');
        return EOL.equals(rule) && List.of("\r", "\n", "\r\n").contains(oldText)
                && List.of("\n", "\r\n").contains(newText) && !oldText.equals(newText);
    }

    static String visible(String text) {
        return text.replace(" ", "␠").replace("\t", "⇥").replace("\r", "<CR>").replace("\n", "<LF>");
    }

    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    record Finding(Path path, int line, int column, String rule, String message) { }

    private record Hazard(int index, String message) { }

    record Report(List<TextEditPlan.Edit> trailing, List<TextEditPlan.Edit> lf, List<TextEditPlan.Edit> crlf,
            String lfBlock, String crlfBlock, int lfCount, int crlfCount, int crCount,
            List<Finding> findings, int omittedFindings) { }

    private static final class Findings {
        private final List<Finding> items = new ArrayList<>();
        private int omitted;

        void add(Finding finding) {
            if (items.size() < MAX_FINDINGS) items.add(finding);
            else omitted++;
        }
    }
}
