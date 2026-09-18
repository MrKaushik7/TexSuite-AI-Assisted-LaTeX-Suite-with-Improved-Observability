package texsuite;

import java.nio.file.Path;
import java.util.concurrent.Callable;
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
    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "FILE",
            description = "Root LaTeX file to open.")
    private Path file;

    @Spec
    private CommandSpec commandSpec;

    TexSuiteCli() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TexSuiteCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        if (file == null) {
            commandSpec.commandLine().getOut().println(
                    "TexSuite is installed. The guided document workflow is the next implementation slice.");
            return CommandLine.ExitCode.OK;
        }

        commandSpec.commandLine().getErr().printf(
                "Document selection is not implemented yet; no file was read or changed: %s%n", file);
        return CommandLine.ExitCode.USAGE;
    }
}
