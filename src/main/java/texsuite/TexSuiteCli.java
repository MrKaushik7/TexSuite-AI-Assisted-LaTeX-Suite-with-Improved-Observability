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

    @Spec
    private CommandSpec commandSpec;

    private final BufferedReader input;
    private final boolean interactive;
    private final Path workingDirectory;
    private final RecentFolders recentFolders;
    private final Supplier<Optional<Path>> filePicker;

    TexSuiteCli() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.console() != null, Path.of("").toAbsolutePath(),
                RecentFolders.defaultStorage(), DocumentInput::openNativePicker);
    }

    TexSuiteCli(BufferedReader input, boolean interactive, Path workingDirectory,
            Path recentFolderStorage, Supplier<Optional<Path>> filePicker) {
        this.input = input;
        this.interactive = interactive;
        this.workingDirectory = workingDirectory;
        this.recentFolders = new RecentFolders(recentFolderStorage);
        this.filePicker = filePicker;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TexSuiteCli()).execute(args);
        System.exit(exitCode);
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
        try {
            DocumentSnapshot snapshot = new DocumentLoader().load(selectedFile.get());
            commandSpec.commandLine().getOut().printf(
                    "Snapshot: %d source file(s), %d include site(s), %d asset reference(s).%n",
                    snapshot.files().size(), snapshot.includeSites().size(), snapshot.assets().size());
            commandSpec.commandLine().getOut().printf("Snapshot fingerprint: %s%n",
                    snapshot.fingerprint());
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
                        commandSpec.commandLine().getOut().printf("  %s at %s:%d%n",
                                diagnostic.code(), DocumentInput.safeDisplay(diagnostic.source()),
                                diagnostic.byteOffset()));
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
            out.printf("  diagnostic %s:%d code=%s detail=%s%n",
                    DocumentInput.safeDisplay(diagnostic.source()), diagnostic.byteOffset(),
                    diagnostic.code(), DocumentInput.safeDisplay(diagnostic.detail()));
        }
    }
}
