package texsuite;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "texsuite",
        description = "Safely plan and apply context-aware edits to LaTeX projects.",
        mixinStandardHelpOptions = true,
        version = "TexSuite 0.1.0-SNAPSHOT")
public final class TexSuiteCli implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "FILE",
            description = "Root LaTeX file to open.")
    private String file;

    @Option(names = "--debug", description = "Show development snapshot details.")
    private boolean debugRequested;

    @Option(names = "--no-debug", description = "Show only the concise snapshot summary.")
    private boolean debugDisabled;

    @Option(names = "--allow-no-compile", description = "Allow approved text edits only when pdflatex is unavailable.")
    private boolean allowNoCompile;

    @Option(names = "--compile-main", description = "Saved main .tex file for validating chapter edits.")
    private Path compilationMain;

    @Spec
    private CommandSpec commandSpec;

    private final BufferedReader input;
    private final boolean interactive;
    private final Path workingDirectory;
    private final RecentFolders recentFolders;
    private final Supplier<Optional<Path>> filePicker;
    private final EditorPreferences editorPreferences;
    private EditorLauncher editorLauncher;
    private EditorPicker editorPicker;
    private java.util.function.BiFunction<Boolean, Path, TexCompileGate> compileGate;

    TexSuiteCli() {
        this.input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.interactive = hasInteractiveConsole();
        this.workingDirectory = Path.of("").toAbsolutePath();
        this.filePicker = DocumentInput::openNativePicker;

        Path recentFolderStorage = RecentFolders.defaultStorage();
        this.recentFolders = new RecentFolders(recentFolderStorage);
        this.editorPreferences = new EditorPreferences(
                recentFolderStorage.resolveSibling("editor.properties"));

        this.editorLauncher = EditorLauncher.system();
        this.editorPicker = EditorPicker.system();
        this.compileGate = TexCompileGate::new;
    }

    // Test-only constructor: supplies controlled input, paths, and file selection.
    TexSuiteCli(BufferedReader input, boolean interactive, Path workingDirectory,
            Path recentFolderStorage, Supplier<Optional<Path>> filePicker) {
        this.input = input;
        this.interactive = interactive;
        this.workingDirectory = workingDirectory;
        this.filePicker = filePicker;

        this.recentFolders = new RecentFolders(recentFolderStorage);
        this.editorPreferences = new EditorPreferences(
                recentFolderStorage.resolveSibling("editor.properties"));

        this.editorLauncher = (path, application) -> Optional.empty();
        this.editorPicker = Optional::empty;
        this.compileGate = TexCompileGate::new;
    }

    // Test-only dependency injection; call before command execution.
    void setDependenciesForTests(EditorLauncher editorLauncher,
            java.util.function.BiFunction<Boolean, Path, TexCompileGate> compileGate) {
        this.editorLauncher = editorLauncher;
        this.compileGate = compileGate;
    }

    // Test-only picker injection; call before command execution.
    void setEditorPickerForTests(EditorPicker editorPicker) {
        this.editorPicker = editorPicker;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TexSuiteCli()).execute(args);

        System.exit(exitCode);
    }

    private static boolean hasInteractiveConsole() {
        var console = System.console();
        if (console == null) {
            return false;
        }

        try {
            // Newer JDKs can supply a console for redirected streams. Keep Java 21 support.
            return (boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
        } catch (NoSuchMethodException exception) {
            return true;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    @Override
    public Integer call() {
        DocumentInput documentInput = new DocumentInput(input, interactive, workingDirectory,
                recentFolders, filePicker, commandSpec.commandLine().getOut(),
                commandSpec.commandLine().getErr());

        Optional<Path> selectedFile = documentInput.select(file);
        if (selectedFile.isEmpty()) {
            return documentInput.wasCancelledByUser()
                    ? CommandLine.ExitCode.OK : CommandLine.ExitCode.USAGE;
        }

        commandSpec.commandLine().getOut().printf("Selected: %s%n",
                DocumentInput.safeDisplay(selectedFile.get()));

        commandSpec.commandLine().getOut().printf("Project root: %s%n",
                DocumentInput.safeDisplay(selectedFile.get().getParent()));

        if (interactive) {
            return new DocumentSession(input, commandSpec.commandLine().getOut(),
                    commandSpec.commandLine().getErr(), selectedFile.get(),
                    !debugDisabled || debugRequested,
                    () -> showSnapshot(selectedFile.get()), editorPreferences,
                    editorLauncher, editorPicker, compileGate.apply(allowNoCompile, compilationMain == null ? null
                            : workingDirectory.resolve(compilationMain).toAbsolutePath().normalize())).run();
        }

        return showSnapshot(selectedFile.get());
    }

    private int showSnapshot(Path selectedFile) {
        try {
            DocumentSnapshot snapshot = new DocumentLoader().load(selectedFile);

            commandSpec.commandLine().getOut().printf(
                    "Snapshot: %d source file(s), %d include site(s), %d asset reference(s).%n",
                    snapshot.files().size(), snapshot.includeSites().size(), snapshot.assets().size());

            commandSpec.commandLine().getOut().printf("Snapshot fingerprint: %s%n",
                    snapshot.fingerprint());

            commandSpec.commandLine().getOut().println(
                    "Snapshot is held in memory only; no snapshot file is saved.");

            if (!snapshot.complete()) {
                commandSpec.commandLine().getOut().println(
                        "Static include closure is incomplete; inspect source diagnostics.");
            } else {
                commandSpec.commandLine().getOut().println("Supported static include closure complete.");
            }

            if (!snapshot.diagnostics().isEmpty()) {
                commandSpec.commandLine().getOut().printf(
                        "%d source diagnostic(s) need review.%n", snapshot.diagnostics().size());

                snapshot.diagnostics().stream().limit(5).forEach(diagnostic ->
                        commandSpec.commandLine().getOut().printf("  %s at %s%n",
                                diagnostic.code(), diagnosticLocation(snapshot, diagnostic)));
            }

            if (!debugDisabled || debugRequested) {
                printDebug(snapshot);
            }

            commandSpec.commandLine().getOut().println(
                    "Read-only snapshot finished. Editing is not implemented yet.");

            return CommandLine.ExitCode.OK;
        } catch (DocumentLoader.LoadException exception) {
            commandSpec.commandLine().getErr().println("Could not load document: "
                    + DocumentInput.safeDisplay(exception.getMessage()));

            return CommandLine.ExitCode.USAGE;
        }
    }

    static String diagnosticLocation(DocumentSnapshot snapshot,
            DocumentSnapshot.Diagnostic diagnostic) {
        byte[] bytes = snapshot.files().get(diagnostic.source()).bytes();
        String prefix = new String(bytes, 0, diagnostic.byteOffset(), StandardCharsets.UTF_8);
        int line = 1;
        int column = 1;

        for (int index = 0; index < prefix.length();) {
            int codePoint = prefix.codePointAt(index);
            index += Character.charCount(codePoint);

            if (codePoint == '\r') {
                if (index < prefix.length() && prefix.charAt(index) == '\n') {
                    index++;
                }

                line++;
                column = 1;
            } else if (codePoint == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }

        return DocumentInput.safeDisplay(diagnostic.source()) + ":" + line + ":" + column
                + " (byte " + diagnostic.byteOffset() + ")";
    }

    private void printDebug(DocumentSnapshot snapshot) {
        var out = commandSpec.commandLine().getOut();

        out.printf("Debug snapshot (development): %d protected span(s).%n",
                snapshot.protectedRegions().size());

        for (DocumentSnapshot.SourceFile source : snapshot.files().values()) {
            out.printf("  source %s sha256=%s%n",
                    DocumentInput.safeDisplay(source.path()), source.hash());
        }

        for (DocumentSnapshot.IncludeSite site : snapshot.includeSites()) {
            out.printf("  include %s:%d literal=%s target=%s conditional=%s resolved=%s%n",
                    DocumentInput.safeDisplay(site.source()), site.byteOffset(),
                    DocumentInput.safeDisplay(site.literal()),
                    site.target() == null ? "-" : DocumentInput.safeDisplay(site.target()),
                    site.conditional(), site.resolved());
        }

        for (DocumentSnapshot.AssetReference asset : snapshot.assets()) {
            out.printf("  asset %s:%d command=%s literal=%s target=%s sha256=%s resolved=%s%n",
                    DocumentInput.safeDisplay(asset.source()), asset.byteOffset(),
                    asset.command(), DocumentInput.safeDisplay(asset.literal()),
                    asset.target() == null ? "-" : DocumentInput.safeDisplay(asset.target()),
                    asset.hash() == null ? "-" : asset.hash(), asset.resolved());
        }

        for (DocumentSnapshot.ProtectedRegion region : snapshot.protectedRegions()) {
            out.printf("  protected %s bytes[%d,%d) reason=%s%n",
                    DocumentInput.safeDisplay(region.source()), region.startByte(),
                    region.endByte(), region.reason());
        }

        for (DocumentSnapshot.Diagnostic diagnostic : snapshot.diagnostics()) {
            out.printf("  diagnostic %s code=%s detail=%s%n",
                    diagnosticLocation(snapshot, diagnostic),
                    diagnostic.code(), DocumentInput.safeDisplay(diagnostic.detail()));
        }
    }
}
