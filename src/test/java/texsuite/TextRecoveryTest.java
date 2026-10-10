package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Properties;
import org.junit.jupiter.api.io.TempDir;

final class TextRecoveryTest {
    @TempDir Path directory;

    @Test
    void interruptedCommitCanRestoreAllOriginals() throws Exception {
        Path recovery = interruptAfterFirstMove();
        Path root = directory.toRealPath();
        assertEquals(List.of(recovery), TextRecovery.pending(root));
        assertEquals("world", Files.readString(root.resolve("chapter.tex")));
        assertEquals(2, TextRecovery.targets(root, recovery).size());
        TextRecovery.restore(root, recovery);
        assertEquals("hello", Files.readString(root.resolve("chapter.tex")));
        assertEquals("hello\\input{chapter}", Files.readString(root.resolve("main.tex")));
        assertTrue(TextRecovery.pending(root).isEmpty());
    }

    @Test
    void externalEditBlocksEntireRecovery() throws Exception {
        Path recovery = interruptAfterFirstMove();
        Files.writeString(directory.resolve("main.tex"), "external change");
        assertThrows(IOException.class, () -> TextRecovery.restore(directory.toRealPath(), recovery));
        assertEquals("world", Files.readString(directory.resolve("chapter.tex")));
        assertEquals("external change", Files.readString(directory.resolve("main.tex")));
    }

    @Test
    void damagedBackupBlocksEntireRecovery() throws Exception {
        Path recovery = interruptAfterFirstMove();
        Files.writeString(recovery.resolve("main.tex"), "damaged");
        assertThrows(IOException.class, () -> TextRecovery.restore(directory.toRealPath(), recovery));
        assertEquals("world", Files.readString(directory.resolve("chapter.tex")));
        assertEquals("hello\\input{chapter}", Files.readString(directory.resolve("main.tex")));
    }

    @Test
    void journalEscapeAndSymlinkTargetsAreRejected() throws Exception {
        Path recovery = interruptAfterFirstMove();
        Path journal = recovery.resolve("recovery.properties");
        String original = Files.readString(journal);
        Files.writeString(journal, original.replace("file.0=chapter.tex", "file.0=../outside.tex"));
        assertThrows(IOException.class, () -> TextRecovery.restore(directory.toRealPath(), recovery));
        Files.writeString(journal, original);
        Files.delete(directory.resolve("chapter.tex"));
        Files.createSymbolicLink(directory.resolve("chapter.tex"), directory.resolve("main.tex"));
        assertThrows(IOException.class, () -> TextRecovery.restore(directory.toRealPath(), recovery));
        assertEquals("hello\\input{chapter}", Files.readString(directory.resolve("main.tex")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "negative", "tooMany", "missingCount", "missingFile",
            "missingHash", "badHash", "duplicate", "absolute", "escape", "metadata", "unnormalized"})
    void restore_invalidJournal_rejectsBeforeChangingAnySource(String corruption) throws Exception {
        Path recovery = interruptAfterFirstMove();
        Path root = directory.toRealPath();
        Path journal = recovery.resolve("recovery.properties");
        Properties values = new Properties();
        try (var input = Files.newInputStream(journal)) { values.load(input); }
        switch (corruption) {
            case "zero" -> values.setProperty("count", "0");
            case "negative" -> values.setProperty("count", "-1");
            case "tooMany" -> values.setProperty("count", "513");
            case "missingCount" -> values.remove("count");
            case "missingFile" -> values.remove("file.0");
            case "missingHash" -> values.remove("original.0");
            case "badHash" -> values.setProperty("replacement.0", "G".repeat(64));
            case "duplicate" -> values.setProperty("file.1", values.getProperty("file.0"));
            case "absolute" -> values.setProperty("file.0", root.resolve("main.tex").toString());
            case "escape" -> values.setProperty("file.0", "../outside.tex");
            case "metadata" -> values.setProperty("file.0", ".tex-suite/secret");
            case "unnormalized" -> values.setProperty("file.0", "folder/../chapter.tex");
            default -> throw new AssertionError(corruption);
        }
        try (var output = Files.newOutputStream(journal)) { values.store(output, null); }
        byte[] main = Files.readAllBytes(root.resolve("main.tex"));
        byte[] chapter = Files.readAllBytes(root.resolve("chapter.tex"));

        assertThrows(IOException.class, () -> TextRecovery.restore(root, recovery));

        assertArrayEquals(main, Files.readAllBytes(root.resolve("main.tex")));
        assertArrayEquals(chapter, Files.readAllBytes(root.resolve("chapter.tex")));
    }

    @Test
    void readBytes_exactLimitAccepted_oneExtraByteRejected() throws Exception {
        Path file = directory.resolve("backup");
        byte[] bytes = new byte[16 * 1024 * 1024];
        Files.write(file, bytes);

        assertArrayEquals(bytes, TextRecovery.readBytes(file));
        Files.write(file, new byte[] {1}, java.nio.file.StandardOpenOption.APPEND);

        assertThrows(IOException.class, () -> TextRecovery.readBytes(file));
    }

    @Test
    void journal_exactLimitAccepted_oneExtraByteRejectedWithoutSourceWrites() throws Exception {
        Path recovery = interruptAfterFirstMove();
        Path root = directory.toRealPath();
        Path journal = recovery.resolve("recovery.properties");
        byte[] original = Files.readAllBytes(journal);
        byte[] padded = new byte[1024 * 1024];
        java.util.Arrays.fill(padded, (byte) ' ');
        System.arraycopy(original, 0, padded, 0, original.length);
        padded[original.length] = '#'; // Fill the remaining bytes with a properties comment.
        Files.write(journal, padded);

        assertEquals("pending", TextRecovery.journal(root, recovery).getProperty("status"));
        Files.write(journal, new byte[] {' '}, java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> TextRecovery.restore(root, recovery));

        assertEquals("world", Files.readString(root.resolve("chapter.tex")));
        assertEquals("hello\\input{chapter}", Files.readString(root.resolve("main.tex")));
    }

    @Test
    void entries_maximumCountAccepted() throws Exception {
        Properties values = new Properties();
        values.setProperty("count", "512");
        for (int i = 0; i < 512; i++) {
            values.setProperty("file." + i, "file" + i + ".tex");
            values.setProperty("original." + i, "a".repeat(64));
            values.setProperty("replacement." + i, "b".repeat(64));
        }

        assertEquals(512, TextRecovery.entries(values).size());
    }

    @Test
    void isPendingJournal_completedAtExactLimitIsNotPending_oneOverIsUnsafe() throws Exception {
        Path journal = directory.resolve("journal");
        String prefix = "status=complete\n#";
        Files.writeString(journal, prefix + " ".repeat(1024 * 1024 - prefix.length()));

        assertFalse(TextRecovery.isPendingJournal(journal));
        Files.writeString(journal, " ", java.nio.file.StandardOpenOption.APPEND);

        assertTrue(TextRecovery.isPendingJournal(journal));
    }

    @Test
    void completedTargets_reportsExactFilesAndRejectsLaterExternalEdits() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        var plan = new TextEditPlan(new DocumentLoader().load(main), List.of(
                new TextEditPlan.Edit(Path.of("main.tex"), 0, 5,
                        "hello".getBytes(StandardCharsets.UTF_8), 1, 1)), "world",
                new TexCompileGate(null, true, null, null));

        Path recovery = plan.apply();

        assertEquals(List.of(Path.of("main.tex")), TextRecovery.completedTargets(directory.toRealPath(), recovery));
        assertNotNull(TextRecovery.journal(directory.toRealPath(), recovery).getProperty("completedAt"));
        Files.writeString(main, "external");
        assertThrows(IOException.class, () -> TextRecovery.completedTargets(directory.toRealPath(), recovery));
        assertEquals("external", Files.readString(main));
    }

    @Test
    void lastCompleted_legacyRecordsUseModificationTimeAndMalformedTimestampRejects() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello");
        Path[] records = new Path[2];
        for (int i = 0; i < 2; i++) {
            String before = Files.readString(main);
            var plan = new TextEditPlan(new DocumentLoader().load(main), List.of(new TextEditPlan.Edit(
                    Path.of("main.tex"), 0, before.length(), before.getBytes(StandardCharsets.UTF_8), 1, 1)),
                    i == 0 ? "world" : "again", new TexCompileGate(null, true, null, null));
            records[i] = plan.apply();
            Path journal = records[i].resolve("recovery.properties");
            Properties values = TextRecovery.journal(directory.toRealPath(), records[i]);
            values.remove("completedAt");
            try (var output = Files.newOutputStream(journal)) { values.store(output, null); }
            Files.setLastModifiedTime(journal, java.nio.file.attribute.FileTime.fromMillis(1000L * (i + 1)));
        }

        assertEquals(records[1], TextRecovery.lastCompleted(directory.toRealPath()));
        Path journal = records[1].resolve("recovery.properties");
        Properties values = TextRecovery.journal(directory.toRealPath(), records[1]);
        values.setProperty("completedAt", "not-a-timestamp");
        try (var output = Files.newOutputStream(journal)) { values.store(output, null); }

        assertThrows(IOException.class, () -> TextRecovery.lastCompleted(directory.toRealPath()));
        assertEquals("again", Files.readString(main));
    }

    private Path interruptAfterFirstMove() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"), "hello\\input{chapter}");
        Files.writeString(directory.resolve("chapter.tex"), "hello");
        var plan = new TextEditPlan(new DocumentLoader().load(main), List.of(
                new TextEditPlan.Edit(Path.of("chapter.tex"), 0, 5,
                        "hello".getBytes(StandardCharsets.UTF_8), 1, 1),
                new TextEditPlan.Edit(Path.of("main.tex"), 0, 5,
                        "hello".getBytes(StandardCharsets.UTF_8), 1, 1)), "world",
                new TexCompileGate(null, true, null, null), (staged, target) -> {
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    throw new SimulatedInterruption();
                });
        assertThrows(SimulatedInterruption.class, plan::apply);
        try (var backups = Files.list(directory.toRealPath().resolve(".tex-suite/backups"))) {
            return backups.findFirst().orElseThrow();
        }
    }

    private static final class SimulatedInterruption extends Error { }
}
