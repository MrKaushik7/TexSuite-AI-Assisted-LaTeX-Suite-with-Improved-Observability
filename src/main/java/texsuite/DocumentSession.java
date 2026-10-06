package texsuite;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import picocli.CommandLine;

final class DocumentSession {
    private final BufferedReader input;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Path selectedFile;
    private final boolean debug;
    private final IntSupplier showSnapshot;
    private final EditorPreferences editorPreferences;
    private final EditorLauncher editorLauncher;
    private final EditorPicker editorPicker;
    private final TexCompileGate compileGate;

    DocumentSession(BufferedReader input, PrintWriter out, PrintWriter err, Path selectedFile,
            boolean debug, IntSupplier showSnapshot, EditorPreferences editorPreferences,
            EditorLauncher editorLauncher, EditorPicker editorPicker, TexCompileGate compileGate) {
        this.input = input;
        this.out = out;
        this.err = err;
        this.selectedFile = selectedFile;
        this.debug = debug;
        this.showSnapshot = showSnapshot;
        this.editorPreferences = editorPreferences;
        this.editorLauncher = editorLauncher;
        this.editorPicker = editorPicker;
        this.compileGate = compileGate;
    }

    int run() {
        try {
            initializeEditor();
            if (!TextRecovery.pending(selectedFile.getParent()).isEmpty()) {
                out.println("An interrupted text edit needs recovery. Use Settings option 7 before applying edits.");
            }
            while (true) {
                out.println("What would you like to do?");

                out.println("  1. Rename a mathematical symbol");

                out.println("  2. Replace text");

                out.println("  3. Sanitize this document");

                out.println("  4. Merge another LaTeX document into this one");

                out.println("  5. Settings and diagnostics");

                out.println("  6. Quit");
                String choice = ask("> ");
                switch (choice) {
                    case "1" -> {
                        RenameRequest request = collectRequest();
                        MathematicalRenameWorkflow workflow = new MathematicalRenameWorkflow(
                                input, out, err, debug, compileGate);
                        int status = workflow.run(request);
                        if (status != CommandLine.ExitCode.OK || workflow.sessionEnded()) return status;
                    }
                    case "2" -> {
                        int status = new TextReplacementWorkflow(input, out, err, selectedFile,
                                debug, compileGate).run();
                        if (status != CommandLine.ExitCode.OK) return status;
                    }
                    case "3", "4" -> out.println("This operation is unavailable in this build.");
                    case "5" -> settings();
                    case "6" -> {
                        out.println("Session ended.");
                        return CommandLine.ExitCode.OK;
                    }
                    default -> out.println("Choose a number from 1 to 6, or enter quit.");
                }
            }
        } catch (QuitRequest exception) {
            out.println("Session ended.");
            return CommandLine.ExitCode.OK;
        } catch (IOException exception) {
            err.println("Input ended or could not be read.");
            return CommandLine.ExitCode.USAGE;
        } catch (IllegalStateException exception) {
            err.println("Could not produce a complete inventory: "
                    + DocumentInput.safeDisplay(exception.getMessage()));
            return CommandLine.ExitCode.USAGE;
        }
    }

    private void initializeEditor() {
        try {
            editorPreferences.load().ifPresent(err::println);
        } catch (IOException exception) {
            err.println("Could not read editor preferences; using the system text editor.");
            editorPreferences.reset();
        }
        if (editorPreferences.automatic()) openEditor();
    }

    private void openEditor() {
        editorLauncher.open(selectedFile, editorPreferences.application())
                .ifPresent(message -> err.println(DocumentInput.safeDisplay(message)));
    }

    private void settings() throws IOException, QuitRequest {
        while (true) {
            out.printf("Editor: %s; automatic opening: %s%n",
                    editorPreferences.application() == null
                            ? "system text editor" : DocumentInput.safeDisplay(
                                    Path.of(editorPreferences.application()).getFileName()) + " ("
                                    + DocumentInput.safeDisplay(editorPreferences.application()) + ")",
                    editorPreferences.automatic() ? "on" : "off");

            out.println("Settings:");

            out.println("  1. Diagnostics");

            out.println("  2. Toggle automatic opening");

            out.println("  3. Browse for editor…");

            out.println("  4. Open file now");

            out.println("  5. Reset editor");

            out.println("  6. Back");

            out.println("  7. Recover interrupted edits");

            String answer = readSettingsAnswer();
            if (answer == null) continue;

            switch (answer) {
                case "1" -> showSnapshot.getAsInt();
                case "2" -> {
                    editorPreferences.setAutomatic(!editorPreferences.automatic());
                    saveEditorSettings();

                    if (editorPreferences.automatic()) openEditor();
                }
                case "3" -> {
                    browseForEditor();
                }
                case "4" -> openEditor();
                case "5" -> {
                    editorPreferences.reset();
                    saveEditorSettings();
                }
                case "6", "" -> { return; }
                case "7" -> recoverEdits();
                default -> out.println("Choose a Settings option from 1 to 7.");
            }
        }
    }

    private String readSettingsAnswer() throws IOException, QuitRequest {
        out.print("Settings> ");
        out.flush();
        String answer = input.readLine();
        if (answer == null) throw new EOFException();

        if (!EditorApplication.safeText(answer)) {
            out.println("Choose a Settings option from 1 to 7; control characters are not allowed.");
            return null;
        }
        answer = answer.strip();
        if (answer.equalsIgnoreCase("quit")) throw new QuitRequest();

        return answer;
    }

    private void browseForEditor() {
        try {
            var selected = editorPicker.choose();
            if (selected.isEmpty()) {
                out.println("No application selected; editor unchanged.");
                return;
            }
            editorPreferences.setApplication(selected.get().toString());
            saveEditorSettings();
        } catch (IOException | SecurityException | java.nio.file.InvalidPathException exception) {
            err.println("Could not select an editor; previous editor kept. "
                    + DocumentInput.safeDisplay(exception.getMessage()));
        }
    }

    private void recoverEdits() throws IOException, QuitRequest {
        try {
            var pending = TextRecovery.pending(selectedFile.getParent());
            if (pending.isEmpty()) out.println("No interrupted text edits need recovery.");
            for (Path recovery : pending) {
                var targets = TextRecovery.targets(selectedFile.getParent(), recovery);

                out.println("Restore original saved files from: " + DocumentInput.safeDisplay(recovery));
                for (Path target : targets) out.println("  " + DocumentInput.safeDisplay(target));
                if (ask("Type restore to restore these files, or Enter to cancel: ").equals("restore")) {
                    TextRecovery.restore(selectedFile.getParent(), recovery);

                    out.println("Original files restored. Prepare a new preview before editing.");
                }
            }
        } catch (IOException exception) {
            err.println("Recovery stopped: " + DocumentInput.safeDisplay(exception.getMessage()));
        }
    }

    private void saveEditorSettings() {
        try {
            editorPreferences.save();
        } catch (IOException exception) {
            err.println("Could not save editor preference; this session keeps the choice.");
        }
    }

    private RenameRequest collectRequest() throws IOException, QuitRequest {
        String source = askSource();
        String meaning = askMeaning();
        String replacement = askReplacement(source, meaning);
        return new RenameRequest(selectedFile, source, meaning, replacement, askScope());
    }

    private String askSource() throws IOException, QuitRequest {
        String source;
        while (true) {
            source = askLiteral("Source symbol or literal LaTeX string: ");
            try {
                RenameRequest.validateLiteral(source, "source");
                return source;
            } catch (IllegalArgumentException exception) {
                out.println(DocumentInput.safeDisplay(exception.getMessage()));
            }
        }
    }

    private String askMeaning() throws IOException, QuitRequest {
        String meaning;
        do {
            meaning = ask("What does this symbol mean here? ");
            if (meaning.isBlank()) {
                out.println("Describe the intended meaning before continuing.");
            }
        } while (meaning.isBlank());

        return meaning;
    }

    private String askReplacement(String source, String meaning) throws IOException, QuitRequest {
        String replacement;

        out.println("Enter literal LaTeX source; rendering depends on its TeX syntax.");
        while (true) {
            replacement = askLiteral("Replacement (only 'quit' alone cancels): ");
            try {
                new RenameRequest(selectedFile, source, meaning, replacement,
                        RenameRequest.Scope.FILE);
                return replacement;
            } catch (IllegalArgumentException exception) {
                out.println(DocumentInput.safeDisplay(exception.getMessage()));
            }
        }
    }

    private RenameRequest.Scope askScope() throws IOException, QuitRequest {
        while (true) {
            String answer = ask("Scope [1=file (default), 2=project]: ");
            if (answer.isEmpty() || answer.equals("1") || answer.equalsIgnoreCase("file")) {
                return RenameRequest.Scope.FILE;
            }
            if (answer.equals("2") || answer.equalsIgnoreCase("project")) {
                return RenameRequest.Scope.PROJECT;
            }
            out.println("Choose 1 for this file or 2 for its static include closure.");
        }
    }

    private String ask(String prompt) throws IOException, QuitRequest {
        return readAnswer(prompt, false);
    }

    private String askLiteral(String prompt) throws IOException, QuitRequest {
        return readAnswer(prompt, true);
    }

    private String readAnswer(String prompt, boolean preserveWhitespace)
            throws IOException, QuitRequest {
        out.print(prompt);
        out.flush();
        String answer = input.readLine();
        if (answer == null) {
            throw new EOFException();
        }
        if (!preserveWhitespace) {
            answer = answer.strip();
        }
        if (answer.equalsIgnoreCase("quit")) {
            throw new QuitRequest();
        }
        return answer;
    }

    private static final class QuitRequest extends Exception { }
}
