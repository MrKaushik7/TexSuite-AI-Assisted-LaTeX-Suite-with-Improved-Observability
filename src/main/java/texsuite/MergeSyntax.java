package texsuite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Literal TeX syntax only: never expands commands or executes source. */
final class MergeSyntax {
    static final Set<String> DECLARATIONS = Set.of("newcommand", "providecommand", "DeclareRobustCommand",
            "def", "DeclareMathOperator", "newenvironment");
    private static final Set<String> UNSUPPORTED = Set.of("renewcommand", "renewenvironment", "gdef", "edef", "xdef",
            "let", "futurelet", "csname", "catcode", "scantokens", "newif", "inputifexists", "InputIfFileExists",
            "includeonly", "makeatletter", "ExplSyntaxOn", "newtheorem", "AtBeginDocument", "AtEndDocument",
            "begingroup", "endgroup", "obeyspaces", "obeylines", "endlinechar", "write", "openout", "read", "directlua");

    private MergeSyntax() { }

    static List<Token> tokens(String text) throws IOException {
        var scan = new TexSourceScanner(text).scan();
        if (!scan.problems().isEmpty()) throw new IOException("Merge source needs structural review: " + scan.problems().getFirst().detail());
        var result = new ArrayList<Token>();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (c == '%') { while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++; continue; }
            if (c != '\\') { i++; continue; }
            int start = i++;
            if (i >= text.length()) break;
            int name = i;
            if (Character.isLetter(text.charAt(i))) {
                while (i < text.length() && (Character.isLetter(text.charAt(i)) || text.charAt(i) == '@')) i++;
            } else i++;
            String command = text.substring(name, i);
            if (command.equals("verb")) {
                if (i < text.length() && text.charAt(i) == '*') i++;
                if (i == text.length()) throw new IOException("Unterminated verb command.");
                char delimiter = text.charAt(i++);
                while (i < text.length() && text.charAt(i) != delimiter) i++;
                if (i == text.length()) throw new IOException("Unterminated verb command.");
                i++; continue;
            }
            if (command.equals("begin")) {
                Group g = group(text, i, '{', '}');
                if (Set.of("verbatim", "verbatim*", "lstlisting", "minted").contains(g.value())) {
                    String closing = "\\end{" + g.value() + "}";
                    int end = text.indexOf(closing, g.end());
                    if (end < 0) throw new IOException("Unclosed literal environment.");
                    i = end + closing.length(); continue;
                }
            }
            if (UNSUPPORTED.contains(command) || (command.startsWith("if") && !command.equals("iff")) || Set.of("else", "fi", "or").contains(command)) {
                throw new IOException("Unsupported merge semantics: \\" + command);
            }
            result.add(new Token(command, start, i));
        }
        if (text.contains("^^")) throw new IOException("Encoded TeX input is unsupported for merge.");
        return result;
    }

    static List<Definition> definitions(String text) throws IOException {
        var result = new ArrayList<Definition>();
        var scanner = new TexSourceScanner(text);
        scanner.scanForSanitation();
        int consumed = -1;
        for (Token t : tokens(text)) {
            if (t.start() < consumed || !DECLARATIONS.contains(t.name())) continue;
            if (scanner.groupedAt(t.start())) throw new IOException("Locally grouped definitions require manual adaptation.");
            int p = skip(text, t.end());
            boolean star = p < text.length() && text.charAt(p) == '*';
            if (star) p = skip(text, p + 1);
            String name;
            if (p < text.length() && text.charAt(p) == '{') {
                Group g = group(text, p, '{', '}'); name = g.value().trim(); p = g.end();
            } else {
                if (p >= text.length() || text.charAt(p) != '\\') throw new IOException("Missing definition name.");
                int start = ++p;
                while (p < text.length() && Character.isLetter(text.charAt(p))) p++;
                name = "\\" + text.substring(start, p);
            }
            boolean environment = t.name().equals("newenvironment");
            if (!(environment ? name.matches("[A-Za-z]+") : name.matches("\\\\[A-Za-z]+"))) throw new IOException("Unsupported definition name: " + name);
            int count = 0;
            String optional = null;
            p = skip(text, p);
            if (t.name().equals("def")) {
                while (p + 1 < text.length() && text.charAt(p) == '#') {
                    count++;
                    if (text.charAt(p + 1) != '0' + count || count > 9) throw new IOException("Unsupported def parameter signature.");
                    p += 2;
                    if (p < text.length() && Character.isWhitespace(text.charAt(p))) throw new IOException("Delimited def parameters are unsupported.");
                }
            } else if (p < text.length() && text.charAt(p) == '[') {
                Group g = group(text, p, '[', ']');
                if (!g.value().matches("[0-9]")) throw new IOException("Unsupported argument count.");
                count = Integer.parseInt(g.value()); p = skip(text, g.end());
                if (p < text.length() && text.charAt(p) == '[') {
                    g = group(text, p, '[', ']'); optional = g.value(); p = g.end();
                    if (count == 0) throw new IOException("Optional default without an argument.");
                }
            }
            Group body = group(text, p, '{', '}');
            String endBody = "";
            p = body.end();
            if (environment) { Group end = group(text, p, '{', '}'); endBody = end.value(); p = end.end(); }
            if (!definitionsInside(body.value()).isEmpty()) throw new IOException("Nested declarations require manual repair.");
            result.add(new Definition(environment ? "env:" + name : name.substring(1), t.name(), count, optional, star,
                    body.value(), endBody, text.substring(t.start(), p), t.start(), p));
            consumed = p;
        }
        return result;
    }

    private static List<String> definitionsInside(String text) {
        return DECLARATIONS.stream().filter(name -> text.contains("\\" + name)).toList();
    }

    static Group group(String text, int offset, char open, char close) throws IOException {
        int p = skip(text, offset);
        if (p >= text.length() || text.charAt(p) != open) throw new IOException("Expected " + open + " near character " + p);
        int start = ++p, depth = 1, braces = 0;
        while (p < text.length()) {
            char c = text.charAt(p++);
            if (c == '\\') { if (p < text.length()) p++; continue; }
            if (c == '%') { while (p < text.length() && text.charAt(p) != '\n' && text.charAt(p) != '\r') p++; continue; }
            if (open == '[' && c == '{') braces++;
            else if (open == '[' && c == '}') braces--;
            else if (braces == 0 && c == open) depth++;
            else if (braces == 0 && c == close && --depth == 0) return new Group(text.substring(start, p - 1), start, p);
        }
        throw new IOException("Unclosed group near character " + offset);
    }

    static int skip(String text, int p) {
        while (p < text.length()) {
            if (Character.isWhitespace(text.charAt(p))) p++;
            else if (text.charAt(p) == '%') { while (p < text.length() && text.charAt(p) != '\n' && text.charAt(p) != '\r') p++; }
            else break;
        }
        return p;
    }

    static String rewrite(String text, java.util.Map<String, String> mapping) throws IOException {
        var output = new StringBuilder(text);
        var ts = tokens(text);
        for (int i = ts.size() - 1; i >= 0; i--) {
            Token t = ts.get(i);
            if (mapping.containsKey(t.name())) output.replace(t.start() + 1, t.end(), mapping.get(t.name()));
            if (Set.of("begin", "end", "newenvironment").contains(t.name())) {
                int p = skip(text, t.end());
                if (t.name().equals("newenvironment") && p < text.length() && text.charAt(p) == '*') p++;
                Group g = group(text, p, '{', '}');
                String replacement = mapping.get("env:" + g.value());
                if (replacement != null) output.replace(g.start(), g.end() - 1, replacement.replace("env:", ""));
            }
        }
        return output.toString();
    }

    record Token(String name, int start, int end) { }
    record Group(String value, int start, int end) { }
    record Definition(String name, String kind, int arguments, String optional, boolean star, String body,
            String endBody, String declaration, int start, int end) {
        boolean compatible(Definition other) {
            return arguments == other.arguments && star == other.star && java.util.Objects.equals(optional, other.optional)
                    && name.startsWith("env:") == other.name.startsWith("env:");
        }
        boolean identical(Definition other) {
            return compatible(other) && kind.equals(other.kind) && body.equals(other.body) && endBody.equals(other.endBody);
        }
    }
}
