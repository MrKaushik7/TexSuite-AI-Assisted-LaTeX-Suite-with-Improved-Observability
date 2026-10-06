package texsuite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
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
