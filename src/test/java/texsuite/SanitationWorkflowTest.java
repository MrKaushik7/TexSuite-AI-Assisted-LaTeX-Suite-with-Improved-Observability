package texsuite;

import static org.junit.jupiter.api.Assertions.*;
import static texsuite.SanitationPlannerTest.bytes;
import static texsuite.SanitationPlannerTest.gate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

final class SanitationWorkflowTest {
    @TempDir Path directory;

    @Test
    void selectionPreviewApplyAndNoOpAreTruthful() throws Exception {
        Path source = source("One.  \r\nTwo.\t\rLast.");
        var result = run(source, "bad\n1 1 2\ninvalid\nlf\ny\n", gate());
        assertEquals(0, result.code(), result.errors());
        assertTrue(result.output().contains("␠␠ -> ∅"));
        assertTrue(result.output().contains("<CR><LF> -> <LF>"));
        assertTrue(result.output().contains("final-newline presence is preserved"));
        assertFalse(result.output().contains("Source line endings and final newline are preserved."));
        assertTrue(result.output().contains("Applied 4 sanitation fix(es)"));
        assertEquals("One.\nTwo.\nLast.", Files.readString(source));
        int histories = EditHistory.list(directory.toRealPath()).size();
        result = run(source, "\n", gate());
        assertEquals(0, result.code());
        assertTrue(result.output().contains("No fixes selected"));
        assertEquals(histories, EditHistory.list(directory.toRealPath()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n\n", "all\n\n\n", "all\n\nback\n", "back\n", "all\n\nquit\n", "", "all\n"})
    void cancellationBackAndEofNeverWrite(String answers) throws Exception {
        Path source = source("Plain.  \n");
        var result = run(source, answers, gate());
        assertEquals(0, result.code(), result.errors());
        assertEquals("Plain.  \n", Files.readString(source));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void individualSelectionPreservesOtherWhitespaceAndEols() throws Exception {
        Path source = source("One.  \nTwo.\t\n");
        var result = run(source, "1\n\ny\n", gate());
        assertEquals(0, result.code(), result.errors());
        assertTrue(result.output().contains("Source line endings and final newline are preserved."));
        assertEquals("One.\nTwo.\t\n", Files.readString(source));
    }

    @Test
    void blockedNormalizationMustBeReselectedAndDoesNotPartiallyConvert() throws Exception {
        Path source = source("% comment\r\nPlain.  \r\n");
        var result = run(source, "all\nlf\n\ny\n", gate());
        assertEquals(0, result.code(), result.errors());
        assertTrue(result.output().contains("withheld as a whole"));
        assertEquals("% comment\r\nPlain.\r\n", Files.readString(source));
    }

    @Test
    void staleApprovalDiscardsSelectionAndRequiresNewApproval() throws Exception {
        Path source = source("Plain.  \n");
        int[] approvals = {0};
        BufferedReader input = new BufferedReader(new StringReader("all\n\ny\n\n\n")) {
            @Override public String readLine() throws IOException {
                String answer = super.readLine();
                if ("y".equals(answer)) { approvals[0]++; Files.writeString(source, "External.  \n"); }
                return answer;
            }
        };
        var result = run(source, input, gate());
        assertEquals(0, result.code(), result.errors());
        assertEquals(1, approvals[0]);
        assertTrue(result.output().contains("discarding selection"));
        assertEquals("External.  \n", Files.readString(source));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void compilerFailureCannotCreateAppliedHistoryOrChangeSource() throws Exception {
        Path source = source("\\documentclass{article}\n\\begin{document}\nPlain.  \n\\end{document}\n");
        byte[] original = Files.readAllBytes(source);
        int[] calls = {0};
        var compiler = new TexCompileGate(Path.of("/fake/pdflatex"), false, source,
                (command, cwd, timeout) -> { calls[0]++; return 1; });
        var result = run(source, "all\n\ny\n", compiler);
        assertEquals(CommandLine.ExitCode.USAGE, result.code());
        assertEquals(1, calls[0]);
        assertArrayEquals(original, Files.readAllBytes(source));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void dependencySourceRemainsUnchangedAfterSelectedFileApply() throws Exception {
        Path chapter = Files.writeString(directory.resolve("chapter.tex"), "Chapter.  \n");
        Path source = source("\\begin{document}\nPlain.  \n\\input{chapter}\n\\end{document}\n");
        var result = run(source, "all\n\ny\n", gate());
        assertEquals(0, result.code(), result.errors());
        assertEquals("Chapter.  \n", Files.readString(chapter));
        assertEquals("\\begin{document}\nPlain.\n\\input{chapter}\n\\end{document}\n", Files.readString(source));
    }

    @Test
    void missingIncludeAndInvalidEncodingAreUsefulErrorsWithNoWrites() throws Exception {
        Path source = source("\\input{missing}\nPlain.  \n");
        var missing = run(source, "", gate());
        assertEquals(CommandLine.ExitCode.USAGE, missing.code());
        assertTrue(missing.errors().contains("included source is missing"));
        Files.write(source, new byte[]{(byte) 0xff});
        var invalid = run(source, "", gate());
        assertEquals(CommandLine.ExitCode.USAGE, invalid.code());
        assertTrue(invalid.errors().contains("UTF-8"));
        assertArrayEquals(new byte[]{(byte) 0xff}, Files.readAllBytes(source));
    }

    @Test
    void noSafeFixesDoesNotAskForApproval() throws Exception {
        Path source = source("\\obeyspaces\nPlain.  \n");
        var result = run(source, "", gate());
        assertEquals(0, result.code());
        assertTrue(result.output().contains("No supported safe fixes"));
        assertFalse(result.output().contains("Apply these"));
    }

    @Test
    void menuThreeRunsOfflineAndMergeStaysUnavailable() throws Exception {
        directory = directory.toRealPath();
        Path source = source("Plain.  \n");
        var settings = new ModelSettings(directory.resolve("ai.json"), name -> null);
        settings.save(new ModelSettings.Profile("", "OPENAI_API_KEY", 60));
        var output = new StringWriter();
        var errors = new StringWriter();
        var session = new DocumentSession(new BufferedReader(new StringReader("3\nall\n\ny\n4\n6\n")),
                new PrintWriter(output, true), new PrintWriter(errors, true), source, false, () -> 0,
                new EditorPreferences(directory.resolve("editor.json")), (path, application) -> Optional.empty(),
                () -> Optional.empty(), gate(), profile -> { fail("Sanitation must not create a model client"); return null; }, settings);
        assertEquals(0, session.run(), errors.toString());
        assertTrue(output.toString().contains("Sanitation:"));
        assertTrue(output.toString().contains("This operation is unavailable"));
        assertEquals("Plain.\n", Files.readString(source));
    }

    private Path source(String value) throws Exception { return Files.writeString(directory.resolve("main.tex"), value).toRealPath(); }

    private Result run(Path source, String answers, TexCompileGate compiler) {
        return run(source, new BufferedReader(new StringReader(answers)), compiler);
    }

    private Result run(Path source, BufferedReader input, TexCompileGate compiler) {
        var out = new StringWriter();
        var err = new StringWriter();
        int code = new SanitationWorkflow(input, new PrintWriter(out, true), new PrintWriter(err, true), source, compiler).run();
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int code, String output, String errors) { }
}
