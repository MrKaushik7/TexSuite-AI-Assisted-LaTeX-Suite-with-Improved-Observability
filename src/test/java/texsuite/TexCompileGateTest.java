package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TexCompileGateTest {
    @TempDir Path directory;

    @Test
    void successfulCompileUsesUpdatedCopyThenCommitsOnlyChapter() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "\\input{chapter}");
        Path chapter = Files.writeString(directory.resolve("chapter.tex"), "hello");
        Path style = Files.writeString(directory.resolve("local.sty"), "local style");
        Path[] copy = {null};
        TexCompileGate gate = new TexCompileGate(Path.of("/fake/pdflatex"), false, main,
                (command, cwd, timeout) -> {
                    copy[0] = cwd;
                    assertNotEquals(directory, cwd);
                    assertEquals("hello", Files.readString(chapter));
                    assertEquals("world", Files.readString(cwd.resolve("chapter.tex")));
                    assertEquals(Files.readString(style), Files.readString(cwd.resolve("local.sty")));
                    assertTrue(command.contains("-no-shell-escape"));
                    assertEquals("./main.tex", command.getLast());
                    Files.writeString(cwd.resolve(".texsuite-validation/texsuite-validation.pdf"), "pdf");
                    return 0;
                });
        Path recovery = plan(chapter, gate).apply();
        assertEquals("world", Files.readString(chapter));
        assertEquals("\\input{chapter}", Files.readString(main));
        assertEquals("hello", Files.readString(recovery.resolve("chapter.tex")));
        assertFalse(Files.exists(copy[0]));
    }

    @Test
    void failureCannotBeBypassedByOverrideAndCopyIsRemoved() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello");
        Path[] copy = {null};
        var gate = new TexCompileGate(Path.of("/fake/pdflatex"), true, null,
                (command, cwd, timeout) -> { copy[0] = cwd; return 1; });
        assertThrows(IOException.class, () -> plan(source, gate).apply());
        assertEquals("hello", Files.readString(source));
        assertFalse(Files.exists(copy[0]));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void successExitWithoutOutputIsNotValidation() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello");
        var gate = new TexCompileGate(Path.of("/fake/pdflatex"), false, null,
                (command, cwd, timeout) -> 0);
        assertThrows(IOException.class, () -> plan(source, gate).apply());
        assertEquals("hello", Files.readString(source));
    }

    @Test
    void editorSaveDuringCompileInvalidatesApproval() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello");
        Path style = Files.writeString(directory.resolve("local.sty"), "old");
        var gate = new TexCompileGate(Path.of("/fake/pdflatex"), false, null,
                (command, cwd, timeout) -> {
                    Files.writeString(style, "external edit");
                    Files.writeString(cwd.resolve(".texsuite-validation/texsuite-validation.pdf"), "pdf");
                    return 0;
                });
        IOException failure = assertThrows(StaleSourceException.class, () -> plan(source, gate).apply());
        assertTrue(failure.getMessage().startsWith("Saved source changed"));
        assertEquals("hello", Files.readString(source));
        assertEquals("external edit", Files.readString(style));
    }

    @Test
    void missingCompilerRequiresExplicitOverride() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello");
        assertThrows(IOException.class, () -> plan(source,
                new TexCompileGate(null, false, null, null)).apply());
        plan(source, new TexCompileGate(null, true, null, null)).apply();
        assertEquals("world", Files.readString(source));
    }

    @Test
    void actualProcessTimeoutStopsValidationWithoutWriting() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello");
        var gate = new TexCompileGate(Path.of("/fake/pdflatex"), true, null,
                (command, cwd, timeout) -> TexCompileGate.runProcess(
                        List.of("/bin/sleep", "5"), cwd, 0));
        IOException failure = assertThrows(IOException.class, () -> plan(source, gate).apply());
        assertTrue(failure.getMessage().contains("timed out"));
        assertEquals("hello", Files.readString(source));
    }

    @Test
    void compilerErrorIsBoundedSanitizedAndSurvivesCopyCleanup() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello");
        Path[] copy = {null};
        var gate = new TexCompileGate(Path.of("/fake/pdflatex"), false, null,
                (command, cwd, timeout) -> {
                    copy[0] = cwd;
                    byte[] diagnostic = ("! Missing package " + "x".repeat(500) + "\nPRIVATE SOURCE")
                            .getBytes(StandardCharsets.UTF_8);
                    diagnostic[18] = (byte) 0xff;
                    diagnostic[19] = 0x1b;
                    Files.write(cwd.resolve(".texsuite-validation/texsuite-validation.log"), diagnostic);
                    return 1;
                });
        IOException error = assertThrows(IOException.class, () -> plan(source, gate).apply());

        assertTrue(error.getMessage().contains("main.tex (exit 1)"));
        assertTrue(error.getMessage().contains("! Missing package"));
        assertFalse(error.getMessage().contains("PRIVATE SOURCE"));
        assertFalse(error.getMessage().contains("\u001b"));
        assertTrue(error.getMessage().length() < 450);
        assertEquals("hello", Files.readString(source));
        assertFalse(Files.exists(copy[0]));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    private TextEditPlan plan(Path source, TexCompileGate gate) throws Exception {
        return new TextEditPlan(new DocumentLoader().loadFile(source),
                List.of(new TextEditPlan.Edit(source.getFileName(), 0, 5,
                        "hello".getBytes(StandardCharsets.UTF_8), 1, 1)), "world", gate);
    }
}
