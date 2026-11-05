package texsuite;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Bounded literal BibTeX entries; no execution or guessed field semantics. */
final class MergeBibliography {
    private MergeBibliography() { }

    static Map<String, Entry> parse(String text) throws IOException {
        Map<String, Entry> result = new LinkedHashMap<>();
        int p = 0;
        while (p < text.length()) {
            if (Character.isWhitespace(text.charAt(p))) { p++; continue; }
            if (text.charAt(p) == '%') { while (p < text.length() && text.charAt(p) != '\n') p++; continue; }
            if (text.charAt(p) != '@') throw new IOException("Unsupported BibTeX text outside entries.");
            int start = p++, typeStart = p;
            while (p < text.length() && Character.isLetter(text.charAt(p))) p++;
            String type = text.substring(typeStart, p).toLowerCase(java.util.Locale.ROOT);
            var group = MergeSyntax.group(text, p, '{', '}');
            p = group.end();
            if (type.equals("comment")) continue;
            if (type.equals("preamble") || type.isEmpty()) throw new IOException("Unsupported BibTeX preamble/type.");
            String body = group.value();
            int split = body.indexOf(type.equals("string") ? '=' : ',');
            if (split < 1) throw new IOException("Malformed BibTeX entry.");
            String key = body.substring(0, split).trim();
            if (!key.matches("[A-Za-z0-9:_.-]+")) throw new IOException("Unsupported bibliography key: " + key);
            var dependencies = new LinkedHashSet<String>();
            String crossref = null;
            int crossrefStart = -1, crossrefEnd = -1;
            int q = split + 1;
            while (q < body.length()) {
                q = spaceComma(body, q);
                if (q == body.length()) break;
                String field = "string";
                if (!type.equals("string")) {
                    int a = q;
                    while (q < body.length() && (Character.isLetterOrDigit(body.charAt(q)) || body.charAt(q) == '_')) q++;
                    field = body.substring(a, q).toLowerCase(java.util.Locale.ROOT);
                    q = MergeSyntax.skip(body, q);
                    if (q == body.length() || body.charAt(q++) != '=') throw new IOException("Malformed BibTeX field.");
                }
                StringBuilder literal = new StringBuilder();
                boolean allLiteral = true;
                int valueStart = q;
                do {
                    q = MergeSyntax.skip(body, q);
                    if (q == body.length()) throw new IOException("Missing BibTeX value.");
                    if (body.charAt(q) == '{') {
                        var value = MergeSyntax.group(body, q, '{', '}'); literal.append(value.value()); q = value.end();
                    } else if (body.charAt(q) == '"') {
                        int a = ++q;
                        while (q < body.length() && body.charAt(q) != '"') { if (body.charAt(q) == '\\') q++; q++; }
                        if (q >= body.length()) throw new IOException("Unclosed BibTeX string.");
                        literal.append(body, a, q++);
                    } else {
                        int a = q;
                        while (q < body.length() && (Character.isLetterOrDigit(body.charAt(q)) || body.charAt(q) == '_')) q++;
                        if (q == a) throw new IOException("Unsupported BibTeX value.");
                        String value = body.substring(a, q);
                        if (!value.matches("[0-9]+") && !Set.of("jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec").contains(value.toLowerCase(java.util.Locale.ROOT))) {
                            dependencies.add("string:" + value.toLowerCase(java.util.Locale.ROOT)); allLiteral = false;
                        }
                        literal.append(value);
                    }
                    q = MergeSyntax.skip(body, q);
                    if (q < body.length() && body.charAt(q) == '#') { q++; continue; }
                    break;
                } while (true);
                if (field.equals("crossref")) {
                    if (crossref != null) throw new IOException("Duplicate BibTeX crossref field.");
                    if (!allLiteral) throw new IOException("Dynamic BibTeX crossref unsupported.");
                    crossref = literal.toString(); dependencies.add(crossref);
                    crossrefStart = group.start() - start + valueStart;
                    crossrefEnd = group.start() - start + q;
                }
                if (q < body.length() && body.charAt(q) != ',') throw new IOException("Malformed BibTeX field separator.");
                if (type.equals("string")) { q = spaceComma(body, q); if (q != body.length()) throw new IOException("Multiple string assignments unsupported."); }
            }
            String id = type.equals("string") ? "string:" + key.toLowerCase(java.util.Locale.ROOT) : key;
            Entry entry = new Entry(id, text.substring(start, p), Set.copyOf(dependencies), crossref, crossrefStart, crossrefEnd);
            if (result.putIfAbsent(id, entry) != null) throw new IOException("Duplicate bibliography key: " + id);
        }
        return result;
    }

    private static int spaceComma(String text, int p) {
        p = MergeSyntax.skip(text, p);
        while (p < text.length() && text.charAt(p) == ',') p = MergeSyntax.skip(text, p + 1);
        return p;
    }

    record Entry(String key, String source, Set<String> dependencies, String crossref, int crossrefStart, int crossrefEnd) { }
}
