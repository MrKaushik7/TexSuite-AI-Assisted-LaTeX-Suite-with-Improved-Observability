package texsuite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

final class TextEditPlanTest {
    @TempDir
    Path directory;

    @Test
    void secondCommitFailureRestoresFirstFileAndRecordsRecovery() throws Exception {
        String mainText = "\\begin{document}hello\\input{chapter}\\end{document}";
        Path main = Files.writeString(directory.resolve("main.tex"), mainText);
        Path chapter = Files.writeString(directory.resolve("chapter.tex"), "hello");
        DocumentSnapshot snapshot = new DocumentLoader().load(main);
        int[] moves = {0};
        TextEditPlan.Committer failSecond = (staged, target) -> {
            if (++moves[0] == 2) throw new IOException("injected second commit failure");
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        };
        TextEditPlan plan = new TextEditPlan(snapshot, List.of(
                new TextEditPlan.Edit(Path.of("chapter.tex"), 0, 5,
                        "hello".getBytes(StandardCharsets.UTF_8), 1, 1),
                new TextEditPlan.Edit(Path.of("main.tex"), mainText.indexOf("hello"),
                        mainText.indexOf("hello") + 5,
                        "hello".getBytes(StandardCharsets.UTF_8), 1, 17)),
                "world", new TexCompileGate(null, true, null, null), failSecond);

        IOException failure = assertThrows(IOException.class, plan::apply);
        assertTrue(failure.getMessage().contains("injected second commit failure"));
        assertEquals(2, moves[0]);
        assertEquals("hello", Files.readString(chapter));
        assertEquals(mainText, Files.readString(main));
        try (var backups = Files.list(directory.resolve(".tex-suite/backups"))) {
            Path recovery = backups.findFirst().orElseThrow();
            assertTrue(Files.readString(recovery.resolve("recovery.properties"))
                    .contains("status=restored"));
            assertEquals("hello", Files.readString(recovery.resolve("chapter.tex")));
            assertEquals(mainText, Files.readString(recovery.resolve("main.tex")));
        }
    }
    @Test
    void failedRollbackPreservesExternalEditAndPendingJournal() throws Exception {
        Path root = directory.toRealPath();
        Path main = Files.writeString(root.resolve("main.tex"), "hello\\input{chapter}");
        Path chapter = Files.writeString(root.resolve("chapter.tex"), "hello");
        int[] moves = {0};
        TextEditPlan plan = twoFilePlan(main, (staged, target) -> {
            if (++moves[0] == 2) {
                Files.writeString(chapter, "external edit");
                throw new IOException("injected failure after external edit");
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        });

        IOException failure = assertThrows(IOException.class, plan::apply);
        assertTrue(failure.getMessage().startsWith("Recovery required at "));
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertTrue(failure.getCause().getSuppressed()[0].getMessage().contains("Externally changed"));
        assertEquals("external edit", Files.readString(chapter));
        assertEquals("hello\\input{chapter}", Files.readString(main));
        assertEquals(1, TextRecovery.pending(root).size());
        assertNoStagedFiles(root);
    }

    @Test
    void failedJournalCompletionRollsBackSourceAndReportsRecoveryRequirement() throws Exception {
        Path root = directory.toRealPath();
        Path main = Files.writeString(root.resolve("main.tex"), "hello\\input{chapter}");
        Path chapter = Files.writeString(root.resolve("chapter.tex"), "hello");
        int[] moves = {0};
        TextEditPlan plan = twoFilePlan(main, (staged, target) -> {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            if (++moves[0] == 2) {
                Path recovery = TextRecovery.pending(root).getFirst();
                Path journal = recovery.resolve("recovery.properties");
                Files.delete(journal);
                Files.createDirectory(journal);
                Files.writeString(journal.resolve("obstruction"), "prevent atomic replacement");
            }
        });

        IOException failure = assertThrows(IOException.class, plan::apply);
        assertTrue(failure.getMessage().startsWith("Recovery required at "));
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals("hello", Files.readString(chapter));
        assertEquals("hello\\input{chapter}", Files.readString(main));
        assertEquals(1, TextRecovery.pending(root).size());
        assertNoStagedFiles(root);
    }

    @ParameterizedTest
    @CsvSource({"-1, 1, h", "0, 0, h", "3, 2, h", "0, 6, hello", "0, 5, wrong"})
    void apply_invalidRangeOrExpectedBytes_rejectsWithoutWrites(int start, int end, String expected)
            throws Exception {
        // Arrange: the contract requires a non-empty, in-bounds range matching saved bytes.
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        byte[] before = Files.readAllBytes(main);
        var plan = singleFilePlan(main, List.of(edit(start, end, expected)));

        // Act and assert: rejection must precede any source or history writes.
        assertThrows(IOException.class, plan::apply);
        assertArrayEquals(before, Files.readAllBytes(main));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void apply_overlappingRanges_rejectsWithoutWrites() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = singleFilePlan(main, List.of(edit(0, 3, "hel"), edit(2, 5, "llo")));

        assertThrows(IOException.class, plan::apply);

        assertEquals("hello", Files.readString(main));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void apply_adjacentRangesEndingAtEof_replacesBoth() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = singleFilePlan(main, List.of(edit(0, 2, "he"), edit(2, 5, "llo")));

        plan.apply();

        assertEquals("worldworld", Files.readString(main));
    }

    @Test
    void apply_emptySelection_rejectsWithoutCreatingHistory() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = singleFilePlan(main, List.of());

        assertThrows(IOException.class, plan::apply);

        assertEquals("hello", Files.readString(main));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void apply_sourceChangedAfterPreview_preservesExternalBytes() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = singleFilePlan(main, List.of(edit(0, 5, "hello")));
        plan.preview();
        Files.writeString(main, "external");

        assertThrows(StaleSourceException.class, plan::apply);

        assertEquals("external", Files.readString(main));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void apply_sourceChangedAtLastFreshnessCheck_preservesExternalBytes() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = singleFilePlan(main, List.of(edit(0, 5, "hello")));
        int[] checks = {0};

        assertThrows(StaleSourceException.class, () -> plan.apply(() -> {
            if (++checks[0] == 3) Files.writeString(main, "external");
        }));

        assertEquals(3, checks[0]);
        assertEquals("external", Files.readString(main));
        assertNoStagedFiles(directory);
    }

    @Test
    void apply_firstCommitFails_preservesBothSourcesAndCleansStaging() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello\\input{chapter}");
        Path chapter = Files.writeString(directory.resolve("chapter.tex"), "hello");
        var plan = twoFilePlan(main, (staged, target) -> {
            throw new IOException("injected first commit failure");
        });

        IOException failure = assertThrows(IOException.class, plan::apply);

        assertEquals("injected first commit failure", failure.getMessage());
        assertEquals("hello\\input{chapter}", Files.readString(main));
        assertEquals("hello", Files.readString(chapter));
        assertTrue(TextRecovery.pending(directory.toRealPath()).isEmpty());
        assertNoStagedFiles(directory);
    }

    @Test
    void preview_invalidSelectionRejectsBeforeRendering() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = singleFilePlan(main, List.of(edit(0, 5, "wrong")));

        assertThrows(IOException.class, plan::preview);

        assertEquals("hello", Files.readString(main));
    }

    @Test
    void apply_emptyExpectedRangeCannotBecomeAnInsertion() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = singleFilePlan(main, List.of(edit(0, 0, "")));

        assertThrows(IOException.class, plan::apply);

        assertEquals("hello", Files.readString(main));
    }

    @Test
    void apply_unsortedSelectionsAreRenderedInSourceOrderWithPrivateBackups() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = singleFilePlan(main, List.of(edit(2, 5, "llo"), edit(0, 2, "he")));

        Path recovery = plan.apply();

        assertEquals("worldworld", Files.readString(main));
        var privatePermissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
        for (Path folder : List.of(recovery, recovery.getParent(), recovery.getParent().getParent())) {
            assertEquals(privatePermissions, Files.getPosixFilePermissions(folder));
        }
        assertEquals("hello", Files.readString(recovery.resolve("main.tex")));
    }

    @Test
    void apply_unbalancedReplacementRejectsWithoutChangingSource() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = new TextEditPlan(new DocumentLoader().load(main), List.of(edit(0, 5, "hello")),
                "{", new TexCompileGate(null, true, null, null));

        assertThrows(IOException.class, plan::apply);

        assertEquals("hello", Files.readString(main));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void apply_dependencyChangedAfterCompilationStopsBeforeCommit() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        Path style = Files.writeString(directory.resolve("local.sty"), "original style");
        var gate = new TexCompileGate(Path.of("/fake/pdflatex"), false, main, (command, cwd, timeout) -> {
            Files.writeString(cwd.resolve(".texsuite-validation/texsuite-validation.pdf"), "fixture");
            return 0;
        });
        var plan = new TextEditPlan(new DocumentLoader().load(main), List.of(edit(0, 5, "hello")),
                "world", gate);
        int[] checks = {0};

        assertThrows(StaleSourceException.class, () -> plan.apply(() -> {
            if (++checks[0] == 2) Files.writeString(style, "external style");
        }));

        assertEquals("hello", Files.readString(main));
        assertEquals("external style", Files.readString(style));
        assertNoStagedFiles(directory);
    }

    @Test
    void apply_nestedSourceBackupDirectoriesArePrivate() throws Exception {
        Path nested = Files.createDirectories(directory.resolve("chapters"));
        Path chapter = Files.writeString(nested.resolve("one.tex"), "hello");
        Path main = Files.writeString(directory.resolve("main.tex"), "\\input{chapters/one}");
        var plan = new TextEditPlan(new DocumentLoader().load(main), List.of(new TextEditPlan.Edit(
                Path.of("chapters/one.tex"), 0, 5, "hello".getBytes(StandardCharsets.UTF_8), 1, 1)),
                "world", new TexCompileGate(null, true, null, null));

        Path recovery = plan.apply();

        assertEquals("world", Files.readString(chapter));
        assertEquals("hello", Files.readString(recovery.resolve("chapters/one.tex")));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(recovery.resolve("chapters")));
    }

    private TextEditPlan singleFilePlan(Path main, List<TextEditPlan.Edit> edits) throws Exception {
        return new TextEditPlan(new DocumentLoader().load(main), edits, "world",
                new TexCompileGate(null, true, null, null));
    }

    private static TextEditPlan.Edit edit(int start, int end, String expected) {
        return new TextEditPlan.Edit(Path.of("main.tex"), start, end,
                expected.getBytes(StandardCharsets.UTF_8), 1, 1);
    }

    private TextEditPlan twoFilePlan(Path main, TextEditPlan.Committer committer) throws Exception {
        return new TextEditPlan(new DocumentLoader().load(main), List.of(
                new TextEditPlan.Edit(Path.of("chapter.tex"), 0, 5,
                        "hello".getBytes(StandardCharsets.UTF_8), 1, 1),
                new TextEditPlan.Edit(Path.of("main.tex"), 0, 5,
                        "hello".getBytes(StandardCharsets.UTF_8), 1, 1)),
                "world", new TexCompileGate(null, true, null, null), committer);
    }

    private static void assertNoStagedFiles(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

}
