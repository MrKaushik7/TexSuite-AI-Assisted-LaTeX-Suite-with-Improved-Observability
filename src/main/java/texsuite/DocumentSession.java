package texsuite;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import java.util.function.Function;
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
    private final java.util.Set<Path> mergeHistoryRoots = new java.util.LinkedHashSet<>();
    private final ModelSettings modelSettings;
    private final Function<ModelSettings.Profile, ModelClient> modelFactory;

    DocumentSession(BufferedReader input, PrintWriter out, PrintWriter err, Path selectedFile,
            boolean debug, IntSupplier showSnapshot, EditorPreferences editorPreferences,
            EditorLauncher editorLauncher, EditorPicker editorPicker, TexCompileGate compileGate) {
        this(input, out, err, selectedFile, debug, showSnapshot, editorPreferences,
                editorLauncher, editorPicker, compileGate, ModelClient::create);
    }

    DocumentSession(BufferedReader input, PrintWriter out, PrintWriter err, Path selectedFile,
            boolean debug, IntSupplier showSnapshot, EditorPreferences editorPreferences,
            EditorLauncher editorLauncher, EditorPicker editorPicker, TexCompileGate compileGate,
            Function<ModelSettings.Profile, ModelClient> modelFactory) {
        this(input, out, err, selectedFile, debug, showSnapshot, editorPreferences, editorLauncher,
                editorPicker, compileGate, modelFactory, new ModelSettings(ModelSettings.defaultStorage()));
    }

    DocumentSession(BufferedReader input, PrintWriter out, PrintWriter err, Path selectedFile,
            boolean debug, IntSupplier showSnapshot, EditorPreferences editorPreferences,
            EditorLauncher editorLauncher, EditorPicker editorPicker, TexCompileGate compileGate,
            Function<ModelSettings.Profile, ModelClient> modelFactory, ModelSettings modelSettings) {
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
        this.modelSettings = modelSettings;
        this.modelFactory = modelFactory;
    }

    int run() {
        try {
            initializeEditor();
            out.println();
            out.println("Enter back at any prompt to return to the menu.");
            initializeAi();
            for (Path root : historyRoots()) {
                if (!TextRecovery.pending(root).isEmpty()) {
                    out.println("An interrupted text edit needs recovery. Use Settings option 7 before applying edits.");
                    break;
                }
            }
            while (true) {
                out.println("What would you like to do?");

                out.println("  1. Rename a mathematical symbol");

                out.println("  2. Replace text");

                out.println("  3. Clean up whitespace");

                out.println("  4. Merge documents");

                out.println("  5. Settings and diagnostics");

                out.println("  6. Quit");

                out.println("  7. Undo last change");

                out.println("  8. Help");
                try {
                    String choice = ask("> ");
                    if (java.util.Set.of("1","2","3","4").contains(choice)) {
                        boolean pending = false;
                        for (Path root : historyRoots()) {
                            // Existing workflows retain their own-root preview/recovery behavior.
                            if (choice.equals("4") || !root.equals(selectedFile.getParent())) pending |= !TextRecovery.pending(root).isEmpty();
                        }
                        if (pending) {
                            out.println("Resolve interrupted edits in Settings option 7 before applying another operation.");
                            continue;
                        }
                    }
                    switch (choice) {
                        case "1" -> {
                            RenameRequest request = collectRequest();
                            MathematicalRenameWorkflow workflow = new MathematicalRenameWorkflow(
                                    input, out, err, debug, compileGate, modelClient());
                            int status = workflow.run(request);
                            if (status != CommandLine.ExitCode.OK || workflow.sessionEnded()) return status;
                        }
                        case "2" -> {
                            int status = new TextReplacementWorkflow(input, out, err, selectedFile,
                                    debug, compileGate, modelClient()).run();
                            if (status != CommandLine.ExitCode.OK) return status;
                        }
                        case "3" -> {
                            int status = new SanitationWorkflow(input, out, err, selectedFile, compileGate).run();
                            if (status != CommandLine.ExitCode.OK) return status;
                        }
                        case "4" -> {
                            var workflow = new MergeWorkflow(input, out, err, selectedFile, compileGate,
                                    DocumentInput::openNativePicker, donor -> {
                                        if (editorPreferences.automatic()) openEditor(donor);
                                    });
                            workflow.run();
                            mergeHistoryRoots.add(workflow.historyRoot());
                        }
                        case "5" -> settings();
                        case "6" -> {
                            out.println("Session ended.");
                            return CommandLine.ExitCode.OK;
                        }
                        case "7" -> revertLastChange();
                        case "8", "?", "help" -> HelpText.overview(out);
                        default -> out.println("Choose a number from 1 to 8, help, or quit.");
                    }
                } catch (EditReview.Back exception) {
                    out.println("Back to menu.");
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
        openEditor(selectedFile);
    }

    private void openEditor(Path file) {
        editorLauncher.open(file, editorPreferences.application())
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

            out.println("Editor");

            out.println("  2. Toggle automatic opening");

            out.println("  3. Browse for editor…");

            out.println("  4. Open file now");

            out.println("  5. Reset editor");

            out.println();
            out.println("AI");

            out.println("  8. AI provider settings and test");

            out.println("  10. AI response cache");

            out.println();
            out.println("History and recovery");

            out.println("  9. Edit history");

            out.println("  7. Recover interrupted edits");

            out.println();
            out.println("  1. Diagnostics");

            out.println("  6. Back");

            out.println("  ?  Help");

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
                case "8" -> modelSettings();
                case "9" -> showHistory();
                case "10" -> cacheSettings();
                case "?", "help" -> HelpText.overview(out);
                default -> out.println("Choose a Settings option from 1 to 10.");
            }
        }
    }

    private String readSettingsAnswer() throws IOException, QuitRequest {
        out.print("Settings> ");
        out.flush();
        String answer = input.readLine();
        if (answer == null) throw new EOFException();

        if (!EditorApplication.safeText(answer)) {
            out.println("Choose a Settings option from 1 to 10; control characters are not allowed.");
            return null;
        }
        answer = answer.strip();
        if (answer.equalsIgnoreCase("quit")) throw new QuitRequest();
        if (answer.equalsIgnoreCase("back")) throw new EditReview.Back();

        return answer;
    }

    private ModelClient modelClient() {
        try {
            ModelSettings.Profile profile = modelSettings.load();
            if (profile.configured()) return modelFactory.apply(profile);

        } catch (IOException | IllegalArgumentException exception) {
            out.println("AI settings unavailable; using manual selection.");
        }
        return null;
    }

    private void cacheSettings() throws IOException, QuitRequest {
        ModelCache cache = new ModelCache(selectedFile.getParent());
        while (true) {
            try {
                var stats = cache.stats();
                out.printf("Project AI cache: %s; %d/%d entries; %d bytes (maximum 32 MiB).%n",
                        cache.enabled() ? "enabled" : "disabled", stats.entries(), ModelCache.MAX_ENTRIES, stats.bytes());
                out.println("1. Enable  2. Disable  3. Clear cached results  4. Back");
                out.println("Choose AI regenerate during selection to bypass cached results for that operation.");
                switch (ask("Cache> ")) {
                    case "1" -> cache.setEnabled(true);
                    case "2" -> cache.setEnabled(false);
                    case "3" -> {
                        if (ask("Type clear to delete this project's cached AI results: ").equals("clear")) {
                            out.printf("Cleared %d cached result(s).%n", cache.clear());
                        }
                    }
                    case "4", "" -> { return; }
                    default -> out.println("Choose a cache option from 1 to 4.");
                }
            } catch (EditReview.Back exception) {
                throw exception;
            } catch (IOException exception) {
                if (exception instanceof EOFException) throw exception;
                out.println("AI cache storage is unavailable; source and history were not changed.");
                return;
            }
        }
    }

    private void initializeAi() throws IOException, QuitRequest {
        try {
            ModelSettings.Profile profile = modelSettings.load();
            if (!profile.configured() && !modelSettings.saved()) {
                out.println("AI is not configured. You can change it anytime in Settings 8.");
                String choice = ask("Configure AI now? [y/N]: ");
                if (choice.equalsIgnoreCase("y") || choice.equalsIgnoreCase("yes")) configureModel();

                profile = modelSettings.load();
            }

            if (!profile.configured()) return;

            out.println();

            if (modelSettings.keyAvailable(profile)) {
                out.printf("API key: %s is readable in this session. Validity/expiration has not been checked.%n",
                        profile.keyEnvironment());
                out.println();

                out.println("If you changed the key since launch, quit and restart TexSuite from the terminal with the updated export.");
            } else {
                out.printf("API key: %s is missing, unreadable, or has invalid characters in this session.%n",
                        profile.keyEnvironment());
                out.println();

                out.println("Before using AI, type quit, then export your key in the terminal:");
                out.println();

                out.printf("  export %s='your-api-key'%n", profile.keyEnvironment());
                out.println();

                out.println("Replace the placeholder with your key, then relaunch TexSuite from that same terminal.");
                out.println("A running session cannot pick up later exports. Manual operations remain available.");
            }

            out.println();
            out.println("Settings 8 offers a provider test with separate consent; startup sends no API request.");
            out.println();
        } catch (EditReview.Back exception) {
            // Returning from startup setup leaves manual operations available.
        } catch (IOException exception) {
            if (exception instanceof EOFException) throw exception;

            out.println("AI settings unavailable; use Settings 8. Manual selection is available.");
        }
    }

    private void modelSettings() throws IOException, QuitRequest {
        while (true) {
            try {
                ModelSettings.Profile profile = modelSettings.load();
                out.printf("AI: %s / %s; key variable: %s; timeout: %ds; output: %s%n",
                        profile.provider().id(), profile.configured() ? profile.model() : "disabled",
                        profile.keyEnvironment(), profile.timeoutSeconds(), profile.outputMode().id());
            } catch (IOException exception) {
                out.println("Global AI settings could not be read.");
            }
            out.println("  1. Change AI settings (saved globally)");
            out.println("  2. Test provider (synthetic text, may incur API charges)");
            out.println("  3. Back");
            switch (ask("AI> ")) {
                case "1" -> configureModel();
                case "2" -> {
                    ModelClient client = modelClient();
                    if (client == null) out.println("AI is disabled; choose option 1 to configure it.");
                    else {
                        try {
                            new ModelReview(new EditReview(input, out), out, client).testProvider();
                        } catch (EditReview.Cancel | EOFException exception) {
                            throw new QuitRequest();
                        }
                    }
                }
                case "3", "" -> { return; }
                default -> out.println("Choose an AI option from 1 to 3.");
            }
        }
    }

    private void configureModel() throws IOException, QuitRequest {
        try {
            ModelSettings.Provider provider = switch (ask("Provider [1=OpenAI, 2=OpenRouter, 3=Gemini]: ")) {
                case "1" -> ModelSettings.Provider.OPENAI;
                case "2" -> ModelSettings.Provider.OPENROUTER;
                case "3" -> ModelSettings.Provider.GEMINI;
                default -> throw new IllegalArgumentException("Choose a provider explicitly.");
            };
            String model = ask("Model ID [" + provider.defaultModel() + "; Enter=default, off=disable AI]: ");
            if (model.isEmpty()) model = provider.defaultModel();
            else if (model.equalsIgnoreCase("off")) model = "";

            out.println("Use the key variable NAME, not the API key. Export its value before launching TexSuite.");
            String keyEnvironment = ask("API key environment-variable NAME [" + provider.keyEnvironment() + "]: ");
            String timeout = ask("Request timeout in seconds [60]: ");
            ModelSettings.OutputMode mode = provider == ModelSettings.Provider.OPENROUTER
                    && model.equals(provider.defaultModel())
                    ? ModelSettings.OutputMode.PROMPT_JSON : ModelSettings.OutputMode.JSON_SCHEMA;
            if (provider == ModelSettings.Provider.OPENROUTER) {
                if (mode == ModelSettings.OutputMode.PROMPT_JSON) {
                    out.println("Nemotron uses prompt JSON because this endpoint does not support JSON Schema output.");
                }

                mode = switch (ask("Output [1=JSON Schema, 2=prompt JSON; Enter="
                        + (mode == ModelSettings.OutputMode.PROMPT_JSON ? "2" : "1") + "]: ")) {
                    case "" -> mode;
                    case "1" -> ModelSettings.OutputMode.JSON_SCHEMA;
                    case "2" -> ModelSettings.OutputMode.PROMPT_JSON;
                    default -> throw new IllegalArgumentException("Choose an output mode.");
                };
            }
            var profile = new ModelSettings.Profile(provider, model,
                    keyEnvironment.isEmpty() ? provider.keyEnvironment() : keyEnvironment,
                    timeout.isEmpty() ? 60 : Integer.parseInt(timeout), mode);
            try {
                modelSettings.save(profile);
                out.println("AI settings saved globally. Change them anytime in Settings 8.");
            } catch (IOException exception) {
                out.println("Could not save global AI settings; profile unchanged: "
                        + DocumentInput.safeDisplay(exception.getMessage()));
            }
        } catch (IllegalArgumentException exception) {
            out.println(DocumentInput.safeDisplay(exception.getMessage()) + " Profile unchanged.");
        }
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
            boolean found = false;
            for (Path root : historyRoots()) {
                var pending = TextRecovery.pending(root);
                found |= !pending.isEmpty();
                for (Path recovery : pending) {
                    var targets = TextRecovery.targets(root, recovery);

                    out.println("Restore original saved files from: " + DocumentInput.safeDisplay(recovery));
                    for (Path target : targets) out.println("  " + DocumentInput.safeDisplay(target));
                    if (ask("Type restore to restore these files, or Enter to cancel: ").equals("restore")) {
                        TextRecovery.restore(root, recovery);

                        out.println("Original files restored. Prepare a new preview before editing.");
                    }
                }
            }
            if (!found) out.println("No interrupted text edits need recovery.");
        } catch (EditReview.Back exception) {
            throw exception;
        } catch (IOException exception) {
            err.println("Recovery stopped: " + DocumentInput.safeDisplay(exception.getMessage()));
        }
    }

    private java.util.Set<Path> historyRoots() throws IOException {
        var roots = new java.util.LinkedHashSet<Path>();
        roots.add(selectedFile.getParent());
        roots.addAll(mergeHistoryRoots);
        Path explicit = compileGate.explicitMainRoot();
        if (explicit != null) roots.add(explicit);
        return roots;
    }

    private void showHistory() {
        try {
            for (Path root : historyRoots()) EditHistory.print(root, out, false);
        } catch (IOException exception) {
            err.println("Could not read edit history: " + DocumentInput.safeDisplay(exception.getMessage()));
        }
    }

    private void revertLastChange() throws IOException, QuitRequest {
        try {
            Path root = null, recovery = null;
            for (Path candidateRoot : historyRoots()) {
                Path candidate = TextRecovery.lastCompleted(candidateRoot);
                if (candidate != null && (recovery == null || TextRecovery.completedTime(candidateRoot,candidate)
                        .isAfter(TextRecovery.completedTime(root,recovery)))) {
                    root = candidateRoot;
                    recovery = candidate;
                }
            }
            if (recovery == null) {
                out.println("No completed change is available to undo.");
                return;
            }
            var targets = TextRecovery.completedTargets(root, recovery);
            out.println("Undo the whole last completed operation: " + DocumentInput.safeDisplay(recovery.getFileName()));
            for (Path target : targets) out.println("  " + DocumentInput.safeDisplay(target));
            if (new EditReview(input, out).yes("Are you sure you want to undo this change? [y/N]: ")) {
                TextRecovery.revert(root, recovery);
                out.println("Last change undone. Originals restored; backups and history retained.");
            } else {
                out.println("Undo cancelled; no source changes.");
            }
        } catch (EditReview.Cancel | EOFException exception) {
            throw new QuitRequest();
        } catch (EditReview.Back exception) {
            throw exception;
        } catch (IOException exception) {
            err.println("Undo stopped: " + DocumentInput.safeDisplay(exception.getMessage()));
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

        while (true) {
            replacement = askLiteral("Replacement: ");
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
            String answer = ask("Scope [1=file (default), 2=project; ?=help]: ");
            if (answer.equals("?") || answer.equalsIgnoreCase("help")) {
                out.println(HelpText.SCOPE);
                continue;
            }
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
        if (answer.equalsIgnoreCase("back")) throw new EditReview.Back();
        return answer;
    }

    private static final class QuitRequest extends Exception { }
}
