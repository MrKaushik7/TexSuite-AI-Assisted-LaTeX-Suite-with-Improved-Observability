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
        commandSpec.commandLine().getOut().println(
                "Document input validated (UTF-8). Document loading and editing are not implemented yet; no source file was changed.");
        return CommandLine.ExitCode.OK;
    }
}
