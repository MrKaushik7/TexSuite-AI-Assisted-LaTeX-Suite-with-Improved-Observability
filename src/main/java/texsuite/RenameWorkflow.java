package texsuite;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import picocli.CommandLine;

final class RenameWorkflow {
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

    RenameWorkflow(BufferedReader input, PrintWriter out, PrintWriter err, Path selectedFile,
            boolean debug, IntSupplier showSnapshot) {
        this(input, out, err, selectedFile, debug, showSnapshot, null,
                (file, application) -> java.util.Optional.empty(), java.util.Optional::empty, new TexCompileGate(false, null));
    }

    RenameWorkflow(BufferedReader input, PrintWriter out, PrintWriter err, Path selectedFile,
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
                        if (!showRenameInventory(collectRequest())) {
                            return CommandLine.ExitCode.USAGE;
                        }
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
        if (editorPreferences == null) return;
        try {
            editorPreferences.load().ifPresent(err::println);
        } catch (IOException exception) {
            err.println("Could not read editor preferences; using the system text editor.");
            editorPreferences.reset();
        }
        if (editorPreferences.automatic()) openEditor();
    }

    private void openEditor() {
        if (editorPreferences == null) return;
        editorLauncher.open(selectedFile, editorPreferences.application())
                .ifPresent(message -> err.println(DocumentInput.safeDisplay(message)));
    }

    private void settings() throws IOException, QuitRequest {
        while (true) {
            out.printf("Editor: %s; automatic opening: %s%n",
                    editorPreferences == null || editorPreferences.application() == null
                            ? "system text editor" : DocumentInput.safeDisplay(
                                    Path.of(editorPreferences.application()).getFileName()) + " ("
                                    + DocumentInput.safeDisplay(editorPreferences.application()) + ")",
                    editorPreferences == null || editorPreferences.automatic() ? "on" : "off");
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
                    if (editorPreferences != null) {
                        editorPreferences.setAutomatic(!editorPreferences.automatic());
                        saveEditorSettings();
                        if (editorPreferences.automatic()) openEditor();
                    }
                }
                case "3" -> {
                    browseForEditor();
                }
                case "4" -> openEditor();
                case "5" -> {
                    if (editorPreferences != null) {
                        editorPreferences.reset();
                        saveEditorSettings();
                    }
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
        if (editorPreferences == null) return;

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
        String source;
        while (true) {
            source = askLiteral("Source symbol or literal LaTeX string: ");
            try {
                RenameRequest.validateLiteral(source, "source");
                break;
            } catch (IllegalArgumentException exception) {
                out.println(DocumentInput.safeDisplay(exception.getMessage()));
            }
        }
        String meaning;
        do {
            meaning = ask("What does this symbol mean here? ");
            if (meaning.isBlank()) {
                out.println("Describe the intended meaning before continuing.");
            }
        } while (meaning.isBlank());

        String replacement;
        out.println("Enter literal LaTeX source; rendering depends on its TeX syntax.");
        while (true) {
            replacement = askLiteral("Replacement (only 'quit' alone cancels): ");
            try {
                new RenameRequest(selectedFile, source, meaning, replacement,
                        RenameRequest.Scope.FILE);
                break;
            } catch (IllegalArgumentException exception) {
                out.println(DocumentInput.safeDisplay(exception.getMessage()));
            }
        }
        RenameRequest.Scope scope;
        while (true) {
            String answer = ask("Scope [1=file (default), 2=project]: ");
            if (answer.isEmpty() || answer.equals("1") || answer.equalsIgnoreCase("file")) {
                scope = RenameRequest.Scope.FILE;
                break;
            }
            if (answer.equals("2") || answer.equalsIgnoreCase("project")) {
                scope = RenameRequest.Scope.PROJECT;
                break;
            }
            out.println("Choose 1 for this file or 2 for its static include closure.");
        }
        return new RenameRequest(selectedFile, source, meaning, replacement, scope);
    }

    private boolean showRenameInventory(RenameRequest request) {
        try {
            if (!request.target().toRealPath().equals(request.target())) {
                throw new DocumentLoader.LoadException(
                        "Selected file identity changed; select the file again.");
            }
            DocumentLoader loader = new DocumentLoader();
            DocumentSnapshot snapshot = request.scope() == RenameRequest.Scope.FILE
                    ? loader.loadFile(request.target()) : loader.load(request.target());
            if (!snapshot.root().resolve(snapshot.main()).equals(request.target())) {
                throw new DocumentLoader.LoadException(
                        "Selected file identity changed; select the file again.");
            }
            out.printf("Rename request: %s -> %s; meaning: %s%n",
                    DocumentInput.safeDisplay(request.source()),
                    DocumentInput.safeDisplay(request.replacement()),
                    DocumentInput.safeDisplay(request.meaning()));
            out.printf("Scope: %s; entry: %s; root: %s%n", request.scope(),
                    DocumentInput.safeDisplay(request.target()),
                    DocumentInput.safeDisplay(snapshot.root()));
            if (request.scope() == RenameRequest.Scope.PROJECT) {
                out.println("Project scope covers only the loaded static include closure.");
                if (!snapshot.complete()) {
                    out.println("Static include closure is incomplete; inventory stopped.");
                    printDiagnostics(snapshot);
                    return true;
                }
            }
            if (!snapshot.diagnostics().isEmpty()) {
                printDiagnostics(snapshot);
            }
            RenameCandidateDiscovery.Result result = new RenameCandidateDiscovery()
                    .discover(snapshot, request);
            out.printf("Read-only inventory: %d file(s), %d candidate(s), %d review, %d excluded.%n",
                    result.fileCount(), result.count(RenameCandidateDiscovery.Status.CANDIDATE),
                    result.count(RenameCandidateDiscovery.Status.REVIEW),
                    result.count(RenameCandidateDiscovery.Status.EXCLUDED));
            out.println("Candidates are lexical findings, not approved edits.");
            if (result.count(RenameCandidateDiscovery.Status.CANDIDATE) == 0
                    && result.count(RenameCandidateDiscovery.Status.REVIEW) == 0) {
                out.println(result.occurrences().isEmpty()
                        ? "No literal matches were found in the chosen scope."
                        : "Matches are outside mathematical rename. Use Replace text "
                        + "for a general literal change.");
            }
            out.println("Mathematical rename is discovery-only in this build; no edit is proposed.");
            if (debug) {
                out.printf("Replacement: \"%s\" (proposed literal LaTeX source)%n",
                        DocumentInput.safeDisplay(request.replacement()).replace("\"", "\\\""));
                out.println("Matched source is marked with ⟦ ⟧; excerpts show the original text.");
            }
            int excludedShown = 0;
            Path displayedFile = null;
            byte[] displayedBytes = null;
            for (RenameCandidateDiscovery.Occurrence occurrence : result.occurrences()) {
                if (!debug) continue;
                if (occurrence.status() == RenameCandidateDiscovery.Status.EXCLUDED) {
                    if (excludedShown >= 20) {
                        continue;
                    }
                    excludedShown++;
                }
                if (!occurrence.path().equals(displayedFile)) {
                    displayedFile = occurrence.path();
                    displayedBytes = snapshot.files().get(displayedFile).bytes();
                }
                out.printf("  %s: %s (%s:%d:%d)%n",
                        occurrence.status(), SourceExcerpt.marked(displayedBytes, occurrence.startByte(), occurrence.endByte()),
                        DocumentInput.safeDisplay(occurrence.path()), occurrence.line(),
                        occurrence.column());
                out.printf("    REASON: %s%n", occurrence.reason());
                if (debug) {
                    out.printf("    id=%s bytes[%d,%d)%n", occurrence.id(),
                            occurrence.startByte(), occurrence.endByte());
                }
            }
            if (debug && result.count(RenameCandidateDiscovery.Status.EXCLUDED) > excludedShown) {
                out.printf("  %d excluded occurrence(s) omitted from debug detail.%n",
                        result.count(RenameCandidateDiscovery.Status.EXCLUDED) - excludedShown);
            }
            out.println("Inventory finished; no source changes.");
            return true;
        } catch (DocumentLoader.LoadException | IOException exception) {
            err.println("Could not load document: "
                    + DocumentInput.safeDisplay(exception.getMessage()));
            return false;
        }
    }

    private void printDiagnostics(DocumentSnapshot snapshot) {
        out.printf("%d source diagnostic(s) need review.%n", snapshot.diagnostics().size());
        snapshot.diagnostics().stream().limit(5).forEach(diagnostic ->
                out.printf("  %s at %s%n", diagnostic.code(),
                        TexSuiteCli.diagnosticLocation(snapshot, diagnostic)));
        if (snapshot.diagnostics().size() > 5) {
            out.printf("  ... %d more diagnostic(s)%n", snapshot.diagnostics().size() - 5);
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
