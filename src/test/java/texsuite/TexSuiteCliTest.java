package texsuite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.HeadlessException;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class TexSuiteCliTest {
    @TempDir
    Path temporaryDirectory;

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
    void pickerIconIsPackagedAsAReadableImage() throws Exception {
        try (InputStream iconFile = DocumentInput.class.getResourceAsStream("/texsuite-icon.png")) {
            assertNotNull(iconFile);
            BufferedImage icon = ImageIO.read(iconFile);
            assertNotNull(icon);
            assertEquals(512, icon.getWidth());
            assertEquals(512, icon.getHeight());
            assertEquals(0xFF000000, icon.getRGB(256, 80));
            assertEquals(0xFFFFFFFF, icon.getRGB(256, 170));
            assertEquals(0xFFFFFFFF, icon.getRGB(96, 250));
        }
    }

    @Test
    void relativeAndAbsolutePathsProduceReadOnlySnapshot() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "\\documentclass{article}\n");
        String original = Files.readString(source);

        RunResult relative = run(false, "", () -> Optional.empty(), "paper.tex");
        RunResult absolute = run(false, "", () -> Optional.empty(), source.toString());

        assertEquals(CommandLine.ExitCode.OK, relative.exitCode());
        assertEquals(CommandLine.ExitCode.OK, absolute.exitCode());
        assertTrue(relative.output().contains("Selected: " + source.toRealPath()));
        assertTrue(relative.output().contains("Snapshot: 1 source file(s)"));
        assertTrue(relative.output().contains("Read-only snapshot finished"));
        assertEquals(original, Files.readString(source));
    }

    @Test
    void developmentDebugShowsSnapshotMetadataWithoutCandidatesAndCanBeDisabled()
            throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"),
                "$n$ % n\n\\input{chapter}\n\\includegraphics{figure.pdf}\n");
        Files.writeString(temporaryDirectory.resolve("chapter.tex"), "$n$");
        Files.write(temporaryDirectory.resolve("figure.pdf"), new byte[] {1, 2});
        String original = Files.readString(source);

        RunResult defaultDebug = run(false, "", Optional::empty, "paper.tex");
        RunResult concise = run(false, "", Optional::empty, "--no-debug", "paper.tex");

        assertEquals(CommandLine.ExitCode.OK, defaultDebug.exitCode());
        assertEquals(CommandLine.ExitCode.OK, concise.exitCode());
        assertTrue(defaultDebug.output().contains("Debug snapshot (development)"));
        assertTrue(defaultDebug.output().contains("source paper.tex sha256="));
        assertTrue(defaultDebug.output().contains("source chapter.tex sha256="));
        assertTrue(defaultDebug.output().contains("include paper.tex:"));
        assertTrue(defaultDebug.output().contains("asset paper.tex:"));
        assertTrue(defaultDebug.output().contains("target=figure.pdf"));
        assertTrue(defaultDebug.output().contains("protected paper.tex bytes["));
        assertTrue(defaultDebug.output().contains("reason=COMMENT"));
        assertFalse(defaultDebug.output().contains("\\includegraphics{figure.pdf}"));
        assertFalse(defaultDebug.output().contains("CANDIDATE"));
        assertFalse(defaultDebug.output().contains("Literal n inventory:"));
        assertFalse(concise.output().contains("Literal n inventory:"));
        assertFalse(concise.output().contains("Debug snapshot"));
        assertFalse(concise.output().contains("target=figure.pdf"));
        assertEquals(original, Files.readString(source));
    }

    @Test
    void typoOffersSuggestionButDoesNotSelectItWithoutAnAnswer() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "hello");

        RunResult cancelled = run(true, "n\nquit\n", () -> Optional.empty(), "papr.tex");
        RunResult chosen = run(true, "n\npaper.tex\n", () -> Optional.empty(), "papr.tex");

        assertEquals(CommandLine.ExitCode.OK, cancelled.exitCode());
        assertTrue(cancelled.output().contains("Did you mean:"));
        assertFalse(cancelled.output().contains("  1."));
        assertFalse(cancelled.output().contains("Selected:"));
        assertTrue(chosen.output().contains("Selected: " + source.toRealPath()));
    }

    @Test
    void recentFolderProvidesSuggestionsOutsideWorkingDirectory() throws Exception {
        Path otherFolder = Files.createDirectory(temporaryDirectory.resolve("other"));
        Path source = Files.writeString(otherFolder.resolve("chapter.tex"), "hello");
        run(false, "", () -> Optional.empty(), source.toString());

        RunResult retry = run(true, "n\n" + source + "\n", () -> Optional.empty(), "chaper.tex");

        assertTrue(retry.output().contains("Selected: " + source.toRealPath()));
    }

    @Test
    void pickerCanChooseAFileAndCancellationReturnsToPrompt() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "hello");

        RunResult selected = run(true, "browse\n", () -> Optional.of(source));
        RunResult cancelled = run(true, "browse\nquit\n", Optional::empty);

        assertTrue(selected.output().contains("Selected: " + source.toRealPath()));
        assertTrue(cancelled.output().contains("No file selected"));
        assertFalse(cancelled.output().contains("Selected:"));
    }

    @Test
    void unavailablePickerLeavesTerminalRecoveryAvailable() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "hello");
        RunResult result = run(true, "browse\npaper.tex\n", () -> {
            throw new HeadlessException();
        });

        assertTrue(result.errors().contains("picker is unavailable"));
        assertTrue(result.output().contains("Selected: " + source.toRealPath()));
    }

    @Test
    void decliningBrowseSuppressesLaterAutomaticOffers() throws Exception {
        Files.writeString(temporaryDirectory.resolve("paper.tex"), "hello");

        RunResult result = run(true, "n\nwrong.tex\nquit\n", Optional::empty, "papr.tex");

        assertEquals(1, result.output().split("Browse for a file\\?", -1).length - 1);
        assertFalse(result.output().contains("suggestion number"));
        assertFalse(result.output().contains("  1."));
    }

    @Test
    void acceptingBrowseOfferUsesPickerAfterNoExactMatch() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "hello");

        RunResult result = run(true, "y\n", () -> Optional.of(source), "papr.tex");

        assertTrue(result.output().contains("Browse for a file? (y/N):"));
        assertTrue(result.output().contains("Selected: " + source.toRealPath()));
    }

    @Test
    void missingFileExplainsTheExactPathAndLimitedSearchScope() {
        RunResult result = run(true, "n\nquit\n", Optional::empty, "abstract.tex");

        assertTrue(result.errors().contains(
                "file does not exist at " + temporaryDirectory.resolve("abstract.tex")));
        assertTrue(result.output().contains(
                "No close .tex file found under " + temporaryDirectory + " or saved recent folders."));
        assertTrue(result.output().contains("Browse for a file? (y/N):"));
    }

    @Test
    void noninteractiveMissingPathFailsWithoutOpeningPicker() {
        RunResult result = run(false, "", () -> {
            throw new AssertionError("Picker must not open");
        }, "missing.tex");

        assertEquals(CommandLine.ExitCode.USAGE, result.exitCode());
        assertTrue(result.errors().contains("file does not exist"));
    }

    @Test
    void malformedUtf8CannotReachDocumentSnapshot() throws Exception {
        Files.write(temporaryDirectory.resolve("bad.tex"),
                new byte[] {(byte) 0xC3, (byte) 0x28});

        RunResult result = run(false, "", () -> Optional.empty(), "bad.tex");

        assertEquals(CommandLine.ExitCode.USAGE, result.exitCode());
        assertTrue(result.errors().contains("not valid UTF-8"));
        assertFalse(result.output().contains("Snapshot:"));
    }

    @Test
    void endOfInteractiveInputIsAnErrorNotAnIntentionalQuit() {
        RunResult result = run(true, "", () -> Optional.empty(), "missing.tex");

        assertEquals(CommandLine.ExitCode.USAGE, result.exitCode());
        assertTrue(result.errors().contains("Input ended"));
    }

    @Test
    void filesystemRootSelectionReturnsToRecoveryWithoutCrashing() {
        RunResult result = run(true, "n\nquit\n", Optional::empty,
                temporaryDirectory.getRoot().toString());

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.errors().contains("this is a directory"));
        assertTrue(result.output().contains("Document selection cancelled"));
    }

    @Test
    void snapshotDiagnosticsAndLoadErrorsEscapeControlCharactersInPaths() throws Exception {
        String filename = "bad\u001b[31m.tex";
        Path source = Files.writeString(temporaryDirectory.resolve(filename), "$n");
        RunResult diagnostic = run(false, "", Optional::empty, "--no-debug", filename);

        assertEquals(CommandLine.ExitCode.OK, diagnostic.exitCode());
        assertTrue(diagnostic.output().contains("bad?[31m.tex"));
        assertFalse(diagnostic.output().contains("\u001b"));

        Files.writeString(source, "\\input{missing}");
        Files.writeString(temporaryDirectory.resolve("main.tex"),
                "\\input{linked}");
        Files.createSymbolicLink(temporaryDirectory.resolve("linked.tex"), source);
        Files.writeString(source, "\\input{../outside}");
        RunResult failure = run(false, "", Optional::empty, "main.tex");

        assertEquals(CommandLine.ExitCode.USAGE, failure.exitCode());
        assertTrue(failure.errors().contains("bad?[31m.tex"));
        assertFalse(failure.errors().contains("\u001b"));
    }

    private RunResult run(boolean interactive, String answers,
            Supplier<Optional<Path>> picker, String... arguments) {
        BufferedReader input = new BufferedReader(new StringReader(answers));
        TexSuiteCli cli = new TexSuiteCli(input, interactive, temporaryDirectory,
                temporaryDirectory.resolve("recent.properties"), picker);
        CommandLine commandLine = new CommandLine(cli);
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(errors, true));
        int exitCode = commandLine.execute(arguments);
        return new RunResult(exitCode, output.toString(), errors.toString());
    }

    private record RunResult(int exitCode, String output, String errors) {
    }
}
