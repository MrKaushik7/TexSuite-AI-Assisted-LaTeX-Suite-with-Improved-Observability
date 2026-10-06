package texsuite;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared review prompts; each operation retains responsibility for which occurrences are eligible. */
final class EditReview {
    private final BufferedReader input;
    private final PrintWriter out;

    EditReview(BufferedReader input, PrintWriter out) {
        this.input = input;
        this.out = out;
    }

    String answer(String prompt) throws IOException {
        out.print(prompt);
        out.flush();
        String value = input.readLine();
        if (value == null) throw new EOFException();
        if (value.equalsIgnoreCase("quit")) throw new Cancel();
        return value;
    }

    boolean yes(String prompt) throws IOException {
        while (true) {
            switch (answer(prompt).trim().toLowerCase(java.util.Locale.ROOT)) {
                case "y", "yes" -> { return true; }
                case "", "n", "no" -> { return false; }
                default -> out.println("Choose y or n (Enter skips).");
            }
        }
    }

    void printEdit(DocumentSnapshot snapshot, TextEditPlan.Edit edit, String replacement) {
        byte[] bytes = snapshot.files().get(edit.path()).bytes();

        out.printf("  %s:%d:%d%n", DocumentInput.safeDisplay(edit.path()), edit.line(), edit.column());
        out.println("    - " + SourceExcerpt.marked(bytes, edit.start(), edit.end()));
        out.println("    + " + DocumentInput.safeDisplay(replacement));
    }

    void printUnifiedDiff(DocumentSnapshot snapshot, Map<Path, byte[]> updates) {
        out.println("Proposed changes to saved source:");
        for (var entry : new TreeMap<>(updates).entrySet()) {
            List<String> before = new String(snapshot.files().get(entry.getKey()).bytes(),
                    StandardCharsets.UTF_8).lines().toList();
            List<String> after = new String(entry.getValue(), StandardCharsets.UTF_8).lines().toList();
            String file = entry.getKey().toString();
            List<String> diff = UnifiedDiffUtils.generateUnifiedDiff("a/" + file, "b/" + file,
                    before, DiffUtils.diff(before, after), 3);
            for (String line : diff) out.println(DocumentInput.safeDisplay(line));
        }
        out.println("Source line endings and final newline are preserved.");
    }

    TexCompileGate compilationMain(DocumentSnapshot snapshot, List<TextEditPlan.Edit> accepted,
            TexCompileGate compileGate) throws IOException {
        if (!compileGate.hasCompiler()) return compileGate;

        TexCompileGate resolved = compileGate;
        Path selectedFile = snapshot.root().resolve(snapshot.main());
        String source = new String(snapshot.files().get(snapshot.main()).bytes(), StandardCharsets.UTF_8);
        TexSourceScanner scanner = new TexSourceScanner(source);
        scanner.scan();
        if (!compileGate.hasExplicitMain() && !scanner.hasDocumentStart()) {
            out.println("Selected file is a chapter. Choose its compilation main.");
            out.println("Project root: " + DocumentInput.safeDisplay(snapshot.root()));
            Path main = null;
            var hint = java.util.regex.Pattern.compile("(?im)^[ \\t]*%[ \\t]*!TEX[ \\t]+root[ \\t]*=[ \\t]*([^\\r\\n]+)")
                    .matcher(source);
            if (hint.find() && yes("Use compilation main "
                    + DocumentInput.safeDisplay(hint.group(1).trim()) + "? [y/N]: ")) {
                try {
                    main = selectedFile.getParent().resolve(hint.group(1).trim());
                } catch (java.nio.file.InvalidPathException exception) {
                    out.println("Invalid root hint; enter a main-file path.");
                }
            }
            while (true) {
                if (main == null) {
                    String path = answer("Compilation main (.tex path relative to project root; Enter cancels): ").trim();
                    if (path.isEmpty()) throw new Cancel();
                    try {
                        main = snapshot.root().resolve(path);
                    } catch (java.nio.file.InvalidPathException exception) {
                        out.println("Invalid main-file path.");
                        continue;
                    }
                }
                resolved = compileGate.withMain(main);
                try {
                    if (!main.toRealPath().startsWith(snapshot.root())) {
                        throw new IOException("Compilation main must be inside the displayed project root; "
                                + "use --compile-main explicitly for a containing project.");
                    }
                    resolved.checkMain(snapshot, accepted);
                    break;
                } catch (IOException exception) {
                    out.println("Cannot use compilation main: " + DocumentInput.safeDisplay(exception.getMessage()));
                    main = null;
                }
            }
        } else {
            resolved.checkMain(snapshot, accepted);
        }
        out.println("Compilation main: " + DocumentInput.safeDisplay(resolved.mainFor(snapshot)));
        return resolved;
    }

    static final class Cancel extends IOException { }
}
