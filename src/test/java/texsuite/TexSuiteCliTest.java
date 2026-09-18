package texsuite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

final class TexSuiteCliTest {
    @Test
    void helpDescribesTheCommandWithoutStartingAWorkflow() {
        CommandLine commandLine = new CommandLine(TexSuiteCli.class);
        StringWriter output = new StringWriter();
        commandLine.setOut(new PrintWriter(output));

        int exitCode = commandLine.execute("--help");

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        assertTrue(output.toString().contains("Usage: texsuite"));
        assertTrue(output.toString().contains("Root LaTeX file to open"));
    }

    @Test
    void fileArgumentFailsClearlyUntilDocumentSelectionExists() {
        CommandLine commandLine = new CommandLine(TexSuiteCli.class);
        StringWriter errors = new StringWriter();
        commandLine.setErr(new PrintWriter(errors));

        int exitCode = commandLine.execute("paper.tex");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode);
        assertTrue(errors.toString().contains("no file was read or changed"));
    }
}
