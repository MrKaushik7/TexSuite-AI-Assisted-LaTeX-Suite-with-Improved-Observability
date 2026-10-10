package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class EditHistoryTest {
    @TempDir Path directory;

    @Test
    void exactUnicodeCrlfAndUnequalLengthPositionsHaveEscapedEditorLinksAndJournalStatus() throws Exception {
        Path root = directory.toRealPath();
        Path source = Files.writeString(root.resolve("math # %.tex"), "α😀 n n\r\nn");
        var snapshot = new DocumentLoader().loadFile(source);
        var edits = List.of(edit(source.getFileName(), 7, 8), edit(source.getFileName(), 9, 10), edit(source.getFileName(), 12, 13));
        var intent = new EditHistory.Intent("text-replacement", source.getFileName().toString(), "FILE", "SOURCE",
                "n", "length", "long", "manual", null, null);
        Path backup = new TextEditPlan(snapshot, edits, "long", gate(), intent).apply();
        var entry = EditHistory.list(root).getFirst();
        assertEquals("complete", entry.status());
        assertFalse(entry.legacy());
        assertEquals("UTF-16", entry.record().columnEncoding());
        assertEquals(List.of(7, 12, 18), entry.record().edits().stream().map(EditHistory.Change::afterStartByte).toList());
        assertEquals(List.of(new EditHistory.Position(1, 5), new EditHistory.Position(1, 7), new EditHistory.Position(2, 1)),
                entry.record().edits().stream().map(EditHistory.Change::before).toList());
        assertEquals(List.of(new EditHistory.Position(1, 5), new EditHistory.Position(1, 10), new EditHistory.Position(2, 1)),
                entry.record().edits().stream().map(EditHistory.Change::after).toList());
        assertEquals("α😀 n n\r\nn", Files.readString(backup.resolve(source.getFileName())));
        String link = EditHistory.link(source, entry.record().edits().getFirst().after());
        assertTrue(link.contains("math%20%23%20%25.tex:1:5"), link);
        assertFalse(entry.files().getFirst().currentLinkStale());
        StringWriter output = new StringWriter();
        EditHistory.print(root, new PrintWriter(output), false);
        assertTrue(output.toString().contains("n -> long"));
        assertTrue(output.toString().contains("Backup link: vscode://file/"));
        byte[] details = Files.readAllBytes(backup.resolve("changes.json"));
        TextRecovery.revert(root, backup);
        assertEquals("restored", EditHistory.list(root).getFirst().status());
        assertArrayEquals(details, Files.readAllBytes(backup.resolve("changes.json")));
        assertEquals("α😀 n n\r\nn", Files.readString(source));
    }

    @Test
    void oversizedHistoryBlocksAllSourceMoves() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "n").toRealPath();
        var snapshot = new DocumentLoader().loadFile(source);
        var intent = new EditHistory.Intent("text-replacement", "main.tex", "FILE", "SOURCE", "n",
                "x".repeat(16 * 1024 * 1024), "m", "manual", null, null);
        int[] moves = {0};
        var plan = new TextEditPlan(snapshot, List.of(edit(Path.of("main.tex"), 0, 1)), "m", gate(),
                (staged, target) -> moves[0]++, intent);
        IOException failure = assertThrows(IOException.class, plan::apply);
        assertTrue(failure.getMessage().contains("history exceeds"));
        assertEquals(0, moves[0]);
        assertEquals("n", Files.readString(source));
        assertTrue(TextRecovery.pending(directory.toRealPath()).isEmpty());
        assertTrue(EditHistory.list(directory.toRealPath()).isEmpty());
        try (var paths = Files.walk(directory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void historyExistsBeforeEverySourceMoveAndTamperingIsRejected() throws Exception {
        Path root = directory.toRealPath();
        Path source = Files.writeString(root.resolve("main.tex"), "n");
        var plan = new TextEditPlan(new DocumentLoader().loadFile(source), List.of(edit(Path.of("main.tex"), 0, 1)),
                "m", gate(), (staged, target) -> {
                    Path recovery = TextRecovery.pending(root).getFirst();
                    assertTrue(Files.isRegularFile(recovery.resolve("changes.json")));
                    assertEquals("n", Files.readString(target));
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                });
        Path recovery = plan.apply();
        Path history = recovery.resolve("changes.json");
        Files.writeString(history, Files.readString(history).replace("\"oldText\" : \"n\"", "\"oldText\" : \"x\""));
        assertThrows(IOException.class, () -> EditHistory.list(root));
        assertEquals("m", Files.readString(source));
    }

    @Test
    void externalEditsMarkLinksStaleAndBlockWholeOperationRevert() throws Exception {
        Path recovery = twoFilePlan().apply();
        Path root = directory.toRealPath();
        Files.writeString(root.resolve("main.tex"), "later external edit");
        assertTrue(EditHistory.list(root).getFirst().files().stream().anyMatch(EditHistory.FileState::currentLinkStale));
        assertThrows(IOException.class, () -> TextRecovery.revert(root, recovery));
        assertEquals("world", Files.readString(root.resolve("chapter.tex")));
        assertEquals("later external edit", Files.readString(root.resolve("main.tex")));
        assertEquals("complete", EditHistory.list(root).getFirst().status());
        assertTrue(TextRecovery.pending(root).isEmpty());
    }

    @Test
    void interruptedWholeOperationRevertResumesViaPendingRecovery() throws Exception {
        Path recovery = twoFilePlan().apply();
        Path root = directory.toRealPath();
        assertThrows(Interruption.class, () -> TextRecovery.revert(root, recovery, (staged, target) -> {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            throw new Interruption();
        }));
        assertEquals(List.of(recovery), TextRecovery.pending(root));
        assertEquals("hello", Files.readString(root.resolve("chapter.tex")));
        assertEquals("world\\input{chapter}", Files.readString(root.resolve("main.tex")));
        assertEquals("revert", EditHistory.list(root).getFirst().recoveryAction());
        assertThrows(IOException.class, () -> TextRecovery.lastCompleted(root));
        assertThrows(IOException.class, () -> twoFilePlan().apply());
        TextRecovery.restore(root, recovery);
        assertEquals("hello\\input{chapter}", Files.readString(root.resolve("main.tex")));
        assertEquals("restored", EditHistory.list(root).getFirst().status());
        assertTrue(TextRecovery.pending(root).isEmpty());
        assertTrue(Files.exists(recovery.resolve("changes.json")));
    }

    @Test
    void legacyHistoryIsLabelledAndCliJsonIsParseableWithoutSessionOrCompiler() throws Exception {
        Path recovery = twoFilePlan().apply();
        Files.delete(recovery.resolve("changes.json"));
        var cli = new TexSuiteCli(new BufferedReader(new StringReader("")), true, directory,
                directory.resolve("recent.properties"), Optional::empty);
        cli.setDependenciesForTests((path, application) -> { fail("History must not open an editor"); return Optional.empty(); },
                (allow, main) -> { fail("History must not construct a compiler"); return null; });
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(cli).setOut(new PrintWriter(output));
        assertEquals(0, command.execute("main.tex", "--history", "--json"));
        var json = ModelProtocol.JSON.readTree(output.toString());
        assertTrue(json.isArray());
        assertTrue(json.get(0).get("legacy").asBoolean());
        assertTrue(json.get(0).get("record").isNull());
        assertEquals("complete", json.get(0).get("status").asText());
        assertFalse(output.toString().contains("Selected:"));
    }

    @Test
    void menuRequiresRevertConfirmationAndSettingsShowsHistory() throws Exception {
        directory = directory.toRealPath();
        new ModelSettings(directory.resolve("ai.json"), name -> null)
                .save(new ModelSettings.Profile("", "OPENAI_API_KEY", 60));
        twoFilePlan().apply();
        for (boolean confirm : List.of(false, true)) {
            StringWriter output = new StringWriter();
            var cli = new TexSuiteCli(new BufferedReader(new StringReader("5\n9\n6\n7\n" + (confirm ? "y" : "") + "\n6\n")),
                    true, directory, directory.resolve("recent.properties"), Optional::empty);
            cli.setDependenciesForTests((path, application) -> Optional.empty(), (allow, main) -> gate());
            var command = new CommandLine(cli).setOut(new PrintWriter(output));
            assertEquals(0, command.execute("main.tex"));
            assertTrue(output.toString().contains("Are you sure you want to revert this change? [y/N]"));
            assertTrue(output.toString().contains("Edit history"));
            assertEquals(confirm ? "hello" : "world", Files.readString(directory.resolve("chapter.tex")));
            assertEquals(confirm ? "hello\\input{chapter}" : "world\\input{chapter}", Files.readString(directory.resolve("main.tex")));
        }
    }

    @Test
    void onlyLatestCompletedOperationCanBeRevertedAndUndoCanContinueBackward() throws Exception {
        Path first = twoFilePlan().apply();
        Path root = directory.toRealPath();
        var snapshot = new DocumentLoader().load(root.resolve("main.tex"));
        var edits = List.of(new TextEditPlan.Edit(Path.of("chapter.tex"), 0, 5, "world".getBytes(StandardCharsets.UTF_8), 1, 1),
                new TextEditPlan.Edit(Path.of("main.tex"), 0, 5, "world".getBytes(StandardCharsets.UTF_8), 1, 1));
        Path second = new TextEditPlan(snapshot, edits, "again", gate()).apply();
        assertEquals(second, TextRecovery.lastCompleted(root));
        assertThrows(IOException.class, () -> TextRecovery.revert(root, first));
        assertEquals("again", Files.readString(root.resolve("chapter.tex")));
        TextRecovery.revert(root, second);
        assertEquals("world", Files.readString(root.resolve("chapter.tex")));
        assertEquals(first, TextRecovery.lastCompleted(root));
        TextRecovery.revert(root, first);
        assertEquals("hello", Files.readString(root.resolve("chapter.tex")));
        assertNull(TextRecovery.lastCompleted(root));
    }

    private TextEditPlan twoFilePlan() throws Exception {
        Path main = directory.resolve("main.tex");
        if (!Files.exists(main)) {
            Files.writeString(main, "hello\\input{chapter}");
            Files.writeString(directory.resolve("chapter.tex"), "hello");
        }
        var snapshot = new DocumentLoader().load(main);
        String expected = Files.readString(main).substring(0, 5);
        return new TextEditPlan(snapshot, List.of(
                new TextEditPlan.Edit(Path.of("chapter.tex"), 0, 5, expected.getBytes(StandardCharsets.UTF_8), 1, 1),
                new TextEditPlan.Edit(Path.of("main.tex"), 0, 5, expected.getBytes(StandardCharsets.UTF_8), 1, 1)), "world", gate());
    }

    private static TexCompileGate gate() { return new TexCompileGate(null, true, null, null); }

    private static TextEditPlan.Edit edit(Path path, int start, int end) {
        return new TextEditPlan.Edit(path, start, end, "n".getBytes(StandardCharsets.UTF_8), 1, 1);
    }

    private static final class Interruption extends Error { }
}
