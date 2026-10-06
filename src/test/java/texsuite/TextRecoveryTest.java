package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
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
