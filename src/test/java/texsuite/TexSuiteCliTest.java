package texsuite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.concurrent.TimeUnit;
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

    @Test
    void diagnosticLocationsUseSnapshotBytesAndCodePointColumns() throws Exception {
        for (String newline : new String[] {"\n", "\r\n", "\r"}) {
            String text = "α" + newline + "😀\t$";
            Path chapter = Files.writeString(temporaryDirectory.resolve("chapter.tex"), text);
            Files.writeString(temporaryDirectory.resolve("main.tex"), "\\input{chapter}");
            int offset = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String location = "chapter.tex:2:4 (byte " + offset + ")";

            RunResult concise = run(false, "", Optional::empty, "--no-debug", "main.tex");
            RunResult debug = run(false, "", Optional::empty, "main.tex");

            assertEquals(CommandLine.ExitCode.OK, concise.exitCode());
            assertTrue(concise.output().contains("UNCLOSED_STRUCTURE at " + location));
            assertTrue(debug.output().contains("diagnostic " + location
                    + " code=UNCLOSED_STRUCTURE"));
            assertTrue(concise.output().contains("memory only; no snapshot file is saved"));
            assertEquals(text, Files.readString(chapter));
        }
    }

    @Test
    void interactiveRenameRequiresExplicitInputsAndDefaultsToOneFile() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("chapter.tex"),
                "$x$\\input{missing}\\includegraphics{missing.pdf}");
        byte[] before = Files.readAllBytes(source);
        RunResult result = run(true, "0\n3\n1\n \nx\n \nlength\nx\n"
                + "\\number\nother\n\n6\n", Optional::empty,
                "--no-debug", "chapter.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.output().contains("Choose a number from 1 to 6"));
        assertTrue(result.output().contains("This operation is unavailable"));
        assertTrue(result.output().contains("source must not be blank"));
        assertTrue(result.output().contains("Describe the intended meaning"));
        assertTrue(result.output().contains("replacement must differ"));
        assertTrue(result.output().contains("Choose 1 for this file"));
        assertTrue(result.output().contains("Scope: FILE"));
        assertTrue(result.output().contains("Rename request: x -> \\number"));
        assertTrue(result.output().contains("Read-only inventory: 1 file(s), 1 candidate(s)"));
        assertFalse(result.output().contains("Debug snapshot"));
        assertFalse(result.output().contains("missing.pdf sha256"));
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @Test
    void explicitProjectScopeFindsIncludesAndIncompleteClosureStopsInventory()
            throws Exception {
        Path main = Files.writeString(temporaryDirectory.resolve("main.tex"),
                "$n$\\input{chapter}");
        Files.writeString(temporaryDirectory.resolve("chapter.tex"), "$n$");
        RunResult complete = run(true, "1\nn\nlength\n\\number\n2\n6\n",
                Optional::empty, "--no-debug", "main.tex");

        assertEquals(CommandLine.ExitCode.OK, complete.exitCode());
        assertTrue(complete.output().contains("Scope: PROJECT"));
        assertTrue(complete.output().contains("2 file(s), 2 candidate(s)"));

        Files.writeString(main, "$n$\\ifnum1=1\\input{missing}\\fi");
        RunResult incomplete = run(true, "1\nn\nlength\n\\number\n2\n6\n",
                Optional::empty, "--no-debug", "main.tex");

        assertEquals(CommandLine.ExitCode.OK, incomplete.exitCode());
        assertTrue(incomplete.output().contains("Static include closure is incomplete"));
        assertTrue(incomplete.output().contains("MISSING_CONDITIONAL_INCLUDE"));
        assertFalse(incomplete.output().contains("Read-only inventory:"));
    }

    @Test
    void quitAndEofDuringRenameHaveDistinctResults() throws Exception {
        Files.writeString(temporaryDirectory.resolve("main.tex"), "$n$");
        for (String answers : new String[] {"quit\n", "1\nquit\n",
                "1\nn\nquit\n", "1\nn\nlength\nquit\n",
                "1\nn\nlength\n\\number\nquit\n"}) {
            RunResult cancelled = run(true, answers, Optional::empty, "main.tex");
            assertEquals(CommandLine.ExitCode.OK, cancelled.exitCode());
            assertFalse(cancelled.output().contains("Read-only inventory:"));
        }
        RunResult ended = run(true, "1\nn\n", Optional::empty, "main.tex");
        assertEquals(CommandLine.ExitCode.USAGE, ended.exitCode());
        assertTrue(ended.errors().contains("Input ended"));
        assertFalse(ended.output().contains("Read-only inventory:"));
    }

    @Test
    void settingsShowsNeutralSnapshotAndEscapesRequestText() throws Exception {
        String filename = "odd\u001b[31m.tex";
        Files.writeString(temporaryDirectory.resolve(filename), "$n$");
        RunResult result = run(true, "5\n1\n6\n1\nn\nlength\u001b[31m\n"
                + "\\number\n\n6\n", Optional::empty, "--no-debug", filename);

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.output().contains("Snapshot: 1 source file(s)"));
        assertTrue(result.output().contains("Rename request: n -> \\number"));
        assertTrue(result.output().contains("meaning: length?[31m"));
        assertFalse(result.output().contains("\u001b"));
        assertTrue(result.output().indexOf("Read-only inventory:")
                > result.output().indexOf("Snapshot: 1 source file(s)"));
    }

    @Test
    void renameInventoryKeepsDiagnosticsOutOfNormalOutput() throws Exception {
        String original = "é 😀 Let $n + 1$ grow.\r\n\\iftrue $n$\\fi\rEnd.";
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), original);
        RunResult result = run(true, "1\nn\nlength\nm\n\n6\n",
                Optional::empty, "--no-debug", "paper.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.output().contains("1 candidate(s), 1 review"));
        assertTrue(result.output().contains("discovery-only"));
        assertFalse(result.output().contains("CANDIDATE:"));
        assertFalse(result.output().contains("REVIEW:"));
        assertFalse(result.output().contains("bytes["));
        assertFalse(result.output().matches("(?s).*[a-f0-9]{64}.*"));
        assertEquals(original, Files.readString(source));
    }

    @Test
    void renameInventoryBoundsUnicodeContextAndKeepsDebugIdentity() throws Exception {
        String original = "é😀".repeat(100) + "$n$" + "😀é".repeat(100);
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), original);
        RunResult result = run(true, "1\nn\nlength\nm\n\n6\n",
                Optional::empty, "paper.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        String candidate = result.output().lines().filter(line -> line.contains("CANDIDATE:"))
                .findFirst().orElseThrow();
        assertTrue(candidate.contains("…"));
        assertTrue(candidate.contains("$⟦n⟧$"));
        assertFalse(candidate.contains("�"));
        assertTrue(candidate.length() < 220);
        assertTrue(result.output().matches("(?s).*id=[a-f0-9]{64} bytes\\[.*"));
        assertEquals(original, Files.readString(source));
    }

    @Test
    void interactiveRenamePreservesLongLiteralReplacementAndSourceSpans() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "$n + n$");
        String replacement = " \\text{héllo_😀 \\\"yes\\\"} ";
        RunResult result = run(true, "1\nn\nlength\n" + replacement + "\n\n6\n",
                Optional::empty, "--no-debug", "paper.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.output().contains("Rename request: n -> " + replacement));
        assertTrue(result.output().contains("2 candidate(s)"));
        assertFalse(result.output().contains("⟦n⟧"));
        assertEquals("$n + n$", Files.readString(source));
    }

    @Test
    void replacementPromptKeepsPaddedQuitButRejectsBlankAndNoOp() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "$n$");
        RunResult result = run(true, "1\nn\nlength\n \nn\n quit \n\n6\n",
                Optional::empty, "--no-debug", "paper.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.output().contains("replacement must not be blank"));
        assertTrue(result.output().contains("replacement must differ"));
        assertTrue(result.output().contains("Rename request: n ->  quit "));
        assertTrue(result.output().contains("1 candidate(s)"));
        assertEquals("$n$", Files.readString(source));
    }

    @Test
    void replacementPromptRejectsUnicodeLineSeparators() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "$n$");
        RunResult result = run(true, "1\nn\nlength\nn\u2028x\nm\n\n6\n",
                Optional::empty, "--no-debug", "paper.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.output().contains("replacement must be one line"));
        assertTrue(result.output().contains("Rename request: n -> m"));
        assertFalse(result.output().contains("\u2028"));
        assertEquals("$n$", Files.readString(source));
    }

    @Test
    void renameAcceptsLiteralSourceStringsWithoutRePrompting() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"),
                "$hu + hu$ what");
        RunResult math = run(true, "1\nhu\nquantity\nhello\n\n6\n",
                Optional::empty, "--no-debug", "paper.tex");
        RunResult prose = run(true, "1\nwhat\nword\nhello\n\n6\n",
                Optional::empty, "--no-debug", "paper.tex");

        assertEquals(CommandLine.ExitCode.OK, math.exitCode());
        assertFalse(math.output().contains("Source must be exactly one ASCII letter"));
        assertTrue(math.output().contains("Rename request: hu -> hello"));
        assertTrue(math.output().contains("2 candidate(s)"));
        assertFalse(math.output().contains("⟦hu⟧"));
        assertEquals(CommandLine.ExitCode.OK, prose.exitCode());
        assertFalse(prose.output().contains("Source must be exactly one ASCII letter"));
        assertTrue(prose.output().contains("Rename request: what -> hello"));
        assertTrue(prose.output().contains("0 candidate(s), 0 review, 1 excluded"));
        assertEquals("$hu + hu$ what", Files.readString(source));
    }

    @Test
    void renameWithNoMatchesReportsZeroAndChangesNoSource() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("main.tex"), "$x$");
        RunResult result = run(true, "1\nn\nlength\n\\number\n\n6\n",
                Optional::empty, "--no-debug", "main.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.output().contains("1 file(s), 0 candidate(s), 0 review, 0 excluded"));
        assertTrue(result.output().contains("no source changes"));
        assertEquals("$x$", Files.readString(source));
    }

    @Test
    void fatalProjectLoadStopsWithNonzeroExitAndNoInventory() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("main.tex"),
                "$n$\\input{missing}");
        RunResult result = run(true, "1\nn\nlength\n\\number\n2\n",
                Optional::empty, "--no-debug", "main.tex");

        assertEquals(CommandLine.ExitCode.USAGE, result.exitCode());
        assertTrue(result.errors().contains("included source is missing"));
        assertFalse(result.output().contains("Read-only inventory:"));
        assertEquals("$n$\\input{missing}", Files.readString(source));
    }

    @Test
    void redirectedProcessUsesNeutralSnapshotWithoutConsoleOverrides() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("redirected.tex"), "$n$");
        Path output = temporaryDirectory.resolve("process.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + temporaryDirectory,
                "-cp", System.getProperty("surefire.test.class.path",
                        System.getProperty("java.class.path")),
                "texsuite.TexSuiteCli", "--no-debug", source.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "redirected CLI did not exit");
            String text = Files.readString(output);
            assertEquals(CommandLine.ExitCode.OK, process.exitValue(), text);
            assertTrue(text.contains("Read-only snapshot finished"), text);
            assertFalse(text.contains("What would you like to do?"), text);
            assertFalse(text.contains("Read-only inventory:"), text);
            assertEquals("$n$", Files.readString(source));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void retargetedSelectionStopsRenameWithClearError() throws Exception {
        Path selected = Files.writeString(temporaryDirectory.resolve("selected.tex"), "$n$")
                .toRealPath();
        Path other = Files.writeString(temporaryDirectory.resolve("other.tex"), "$x$");
        Files.delete(selected);
        Files.createSymbolicLink(selected, other);
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        RenameWorkflow workflow = new RenameWorkflow(
                new BufferedReader(new StringReader("1\nn\nlength\nx\n\n")),
                new PrintWriter(output, true), new PrintWriter(errors, true), selected,
                false, () -> CommandLine.ExitCode.OK);

        assertEquals(CommandLine.ExitCode.USAGE, workflow.run());
        assertTrue(errors.toString().contains("Selected file identity changed"));
        assertFalse(output.toString().contains("Read-only inventory:"));
        assertEquals("$x$", Files.readString(other));
    }

    @Test
    void generalSourceReplacementPreviewsAndAppliesWithBackup() throws Exception {
        String original = "\\customsettings{widget=blue}\n"
                + "\\hypersetup{linkcolor=blue,citecolor=blue}\n";
        Path source = Files.writeString(temporaryDirectory.resolve("colours.tex"), original);
        RunResult result = run(true, "2\nblue\nred\n\n2\napply\n6\n",
                Optional::empty, "--no-debug", "--allow-no-compile", "colours.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode(), result.errors());
        assertTrue(result.output().contains("3 match(es), 3 proposed"));
        assertTrue(result.output().contains("customsettings{widget=⟦blue⟧}"));
        assertTrue(result.output().contains("Applied 3 replacement(s)"));
        assertEquals(original.replace("blue", "red"), Files.readString(source));
        try (var backups = Files.walk(temporaryDirectory.resolve(".tex-suite/backups"))) {
            Path backup = backups.filter(path -> path.getFileName().toString().equals("colours.tex"))
                    .findFirst().orElseThrow();
            assertEquals(original, Files.readString(backup));
        }
    }

    @Test
    void documentTextLeavesCitationAndCommentUnchanged() throws Exception {
        String original = "\\begin{document}hello \\cite{hello} % hello\n\\end{document}";
        Path source = Files.writeString(temporaryDirectory.resolve("main.tex"), original);
        RunResult result = run(true, "2\nhello\nworld\n\n\napply\n6\n",
                Optional::empty, "--no-debug", "--allow-no-compile", "main.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode(), result.errors());
        assertTrue(result.output().contains("3 match(es), 1 proposed, 2 skipped"));
        assertEquals(original.replaceFirst("hello", "world"), Files.readString(source));
    }

    @Test
    void applyRequiresExplicitNoCompileOverride() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("plain.tex"),
                "\\begin{document}hello\\end{document}");
        String original = Files.readString(source);
        RunResult result = run(true, "2\nhello\nworld\n\n\napply\n6\n",
                Optional::empty, "--no-debug", "plain.tex");

        assertEquals(CommandLine.ExitCode.USAGE, result.exitCode());
        assertTrue(result.errors().contains("--allow-no-compile"));
        assertEquals(original, Files.readString(source));
        assertFalse(Files.exists(temporaryDirectory.resolve(".tex-suite/backups")));
    }

    @Test
    void projectTextReplacementStagesAndBacksUpAllSelectedSources() throws Exception {
        Path main = Files.writeString(temporaryDirectory.resolve("main.tex"),
                "\\begin{document}café\\input{chapter}\\end{document}");
        Path chapter = Files.writeString(temporaryDirectory.resolve("chapter.tex"), "café");
        RunResult result = run(true, "2\ncafé\nbistro\n2\n1\napply\n6\n",
                Optional::empty, "--no-debug", "--allow-no-compile", "main.tex");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode(), result.errors());
        assertTrue(result.output().contains("2 match(es), 2 proposed"));
        assertEquals("\\begin{document}bistro\\input{chapter}\\end{document}",
                Files.readString(main));
        assertEquals("bistro", Files.readString(chapter));
        try (var backups = Files.list(temporaryDirectory.resolve(".tex-suite/backups"))) {
            Path recovery = backups.findFirst().orElseThrow();
            assertTrue(Files.readString(recovery.resolve("recovery.properties"))
                    .contains("status=complete"));
            assertEquals("café", Files.readString(recovery.resolve("chapter.tex")));
        }
    }

    @Test
    void pendingRecoveryBlocksAnotherApply() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("plain.tex"), "hello");
        Path recovery = temporaryDirectory.resolve(".tex-suite/backups/previous");
        Files.createDirectories(recovery);
        Files.writeString(recovery.resolve("recovery.properties"), "status=pending\n");
        RunResult result = run(true, "2\nhello\nworld\n\n2\napply\n",
                Optional::empty, "--no-debug", "--allow-no-compile", "plain.tex");

        assertEquals(CommandLine.ExitCode.USAGE, result.exitCode());
        assertTrue(result.errors().contains("previous text edit needs recovery"));
        assertEquals("hello", Files.readString(source));
    }

    @Test
    void savedChangeAfterPreviewRejectsApply() throws Exception {
        String original = "\\customsettings{widget=blue}";
        Path source = Files.writeString(temporaryDirectory.resolve("stale.tex"), original);
        BufferedReader input = new BufferedReader(new StringReader("2\nblue\nred\n\n2\napply\n\n6\n")) {
            @Override
            public String readLine() throws java.io.IOException {
                String answer = super.readLine();
                if ("apply".equals(answer)) Files.writeString(source, original + "% saved edit");
                return answer;
            }
        };
        TexSuiteCli cli = new TexSuiteCli(input, true, temporaryDirectory,
                temporaryDirectory.resolve("recent.properties"), Optional::empty);
        CommandLine command = new CommandLine(cli);
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        command.setOut(new PrintWriter(output, true)).setErr(new PrintWriter(errors, true));

        assertEquals(CommandLine.ExitCode.OK,
                command.execute("--no-debug", "--allow-no-compile", "stale.tex"));
        assertTrue(output.toString().contains("Saved source changed; reloading"));
        assertEquals(2, output.toString().split("Proposed changes to saved source:", -1).length - 1);
        assertEquals(original + "% saved edit", Files.readString(source));
    }

    @Test
    void editorLaunchUsesValidatedFileOnceAndSettingsPersist() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("editor.tex"), "hello");
        Path canonicalSource = source.toRealPath();
        int[] launches = {0};
        EditorLauncher launcher = (file, application) -> {
            assertEquals(canonicalSource, file);
            assertEquals(null, application);
            launches[0]++;
            return Optional.empty();
        };
        Path recent = temporaryDirectory.resolve("recent.properties");
        TexSuiteCli first = new TexSuiteCli(new BufferedReader(new StringReader("5\n2\n6\n6\n")),
                true, temporaryDirectory, recent, Optional::empty);
        first.setDependenciesForTests(launcher, TexCompileGate::new);

        assertEquals(CommandLine.ExitCode.OK,
                new CommandLine(first).execute("--no-debug", "editor.tex"));
        assertEquals(1, launches[0]);

        TexSuiteCli second = new TexSuiteCli(new BufferedReader(new StringReader("6\n")),
                true, temporaryDirectory, recent, Optional::empty);
        second.setDependenciesForTests(launcher, TexCompileGate::new);

        assertEquals(CommandLine.ExitCode.OK,
                new CommandLine(second).execute("--no-debug", "editor.tex"));
        assertEquals(1, launches[0]);
        assertTrue(Files.readString(temporaryDirectory.resolve("editor.properties"))
                .contains("automatic=false"));
    }

    @Test
    void namedEditorCanBeSelectedAndOpenedFromSettings() throws Exception {
        Files.writeString(temporaryDirectory.resolve("editor.tex"), "hello");
        java.util.List<String> applications = new java.util.ArrayList<>();
        EditorLauncher launcher = (file, application) -> {
            applications.add(application);
            return Optional.empty();
        };
        Path recent = temporaryDirectory.resolve("recent.properties");
        TexSuiteCli first = new TexSuiteCli(new BufferedReader(new StringReader(
                "5\n3\nTextEdit\n4\n6\n6\n")), true, temporaryDirectory, recent,
                Optional::empty);
        first.setDependenciesForTests(launcher, TexCompileGate::new);

        assertEquals(CommandLine.ExitCode.OK,
                new CommandLine(first).execute("--no-debug", "editor.tex"));

        TexSuiteCli second = new TexSuiteCli(new BufferedReader(new StringReader("6\n")),
                true, temporaryDirectory, recent, Optional::empty);
        second.setDependenciesForTests(launcher, TexCompileGate::new);

        assertEquals(CommandLine.ExitCode.OK,
                new CommandLine(second).execute("--no-debug", "editor.tex"));
        assertEquals(java.util.Arrays.asList(null, "TextEdit", "TextEdit"), applications);
    }

    @Test
    void conditionalSkipsExplainTheActualReason() throws Exception {
        Files.writeString(temporaryDirectory.resolve("main.tex"),
                "\\begin{document}\\iftrue Engineer\\else Engineer\\fi\\end{document}");
        RunResult result = run(true, "2\nEngineer\nYes\n1\n1\n6\n",
                Optional::empty, "--no-debug", "main.tex");
        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.output().contains("inside a TeX conditional"), result.output());
        assertFalse(result.output().contains("Choose a different region"));
    }

    @Test
    void commentedDocumentEndDoesNotHideBodyText() throws Exception {
        Files.writeString(temporaryDirectory.resolve("main.tex"),
                "\\begin{document}\n% \\end{document}\nEngineer\n\\end{document}");
        RunResult result = run(true, "2\nEngineer\nYes\n1\n1\n\n6\n",
                Optional::empty, "--no-debug", "main.tex");
        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.output().contains("1 match(es), 1 proposed"), result.output());
    }

    @Test
    void conditionalIncludeDoesNotBecomeUnconditionalTextProposal() throws Exception {
        Files.writeString(temporaryDirectory.resolve("main.tex"),
                "\\begin{document}\\iftrue\\input{child}\\fi\\end{document}");
        Files.writeString(temporaryDirectory.resolve("child.tex"), "Engineer");
        RunResult result = run(true, "2\nEngineer\nYes\n2\n1\n6\n",
                Optional::empty, "--no-debug", "main.tex");
        assertTrue(result.output().contains("1 match(es), 0 proposed"), result.output());
        assertTrue(result.output().contains("only conditionally reachable"), result.output());
    }

    @Test
    void recoverySettingsRequireExplicitRestore() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("plain.tex"), "hello");
        var plan = new TextEditPlan(new DocumentLoader().loadFile(source), java.util.List.of(
                new TextEditPlan.Edit(Path.of("plain.tex"), 0, 5,
                        "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1, 1)),
                "world", new TexCompileGate(null, true, null, null));
        Path recovery = plan.apply();
        Path journal = recovery.resolve("recovery.properties");
        Files.writeString(journal, Files.readString(journal).replace("status=complete", "status=pending"));
        RunResult cancelled = run(true, "5\n7\n\n6\n6\n", Optional::empty,
                "--no-debug", "plain.tex");
        assertEquals(CommandLine.ExitCode.OK, cancelled.exitCode(), cancelled.errors());
        assertEquals("world", Files.readString(source));
        RunResult restored = run(true, "5\n7\nrestore\n6\n6\n", Optional::empty,
                "--no-debug", "plain.tex");
        assertEquals(CommandLine.ExitCode.OK, restored.exitCode(), restored.errors());
        assertTrue(restored.output().contains("Original files restored"));
        assertEquals("hello", Files.readString(source));
    }

    @Test
    void compileMainOptionReachesGateWithoutExpandingEditScope() throws Exception {
        Path main = Files.writeString(temporaryDirectory.resolve("main.tex"), "hello\\input{chapter}");
        Path chapter = Files.writeString(temporaryDirectory.resolve("chapter.tex"), "hello");
        var input = new BufferedReader(new StringReader("2\nhello\nworld\n1\n1\napply\n6\n"));
        var cli = new TexSuiteCli(input, true, temporaryDirectory,
                temporaryDirectory.resolve("recent.properties"), Optional::empty);
        cli.setDependenciesForTests((path, app) -> Optional.empty(), (allow, selectedMain) -> {
            assertEquals(main.toAbsolutePath().normalize(), selectedMain);
            return new TexCompileGate(Path.of("/fake/pdflatex"), allow, selectedMain,
                    (command, cwd, timeout) -> {
                        assertEquals("hello\\input{chapter}", Files.readString(cwd.resolve("main.tex")));
                        assertEquals("world", Files.readString(cwd.resolve("chapter.tex")));
                        Files.writeString(cwd.resolve(".texsuite-validation/texsuite-validation.pdf"), "pdf");
                        return 0;
                    });
        });

        var command = new CommandLine(cli);
        command.setOut(new PrintWriter(new StringWriter()));
        command.setErr(new PrintWriter(new StringWriter()));

        assertEquals(CommandLine.ExitCode.OK,
                command.execute("--no-debug", "--compile-main", "main.tex", "chapter.tex"));
        assertEquals("hello\\input{chapter}", Files.readString(main));
        assertEquals("world", Files.readString(chapter));
    }

    private RunResult run(boolean interactive, String answers,
            Supplier<Optional<Path>> picker, String... arguments) {
        BufferedReader input = new BufferedReader(new StringReader(answers));
        TexSuiteCli cli = new TexSuiteCli(input, interactive, temporaryDirectory,
                temporaryDirectory.resolve("recent.properties"), picker);
        cli.setDependenciesForTests((path, app) -> Optional.empty(),
                (allow, main) -> new TexCompileGate(null, allow, main, null));

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
