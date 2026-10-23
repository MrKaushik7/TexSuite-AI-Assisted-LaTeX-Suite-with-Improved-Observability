package texsuite;

import static org.junit.jupiter.api.Assertions.*;
import static texsuite.SanitationPlannerTest.bytes;
import static texsuite.SanitationPlannerTest.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SanitationHistoryTest {
    @TempDir Path directory;

    @Test
    void mixedDeletionAndEolHaveExactV2HistoryAndUndo() throws Exception {
        Path main = source("Café.  \r\nSecond.\t\rLast.");
        byte[] before = Files.readAllBytes(main);
        var snapshot = new DocumentLoader().load(main);
        var report = new SanitationPlanner().inspect(snapshot);
        var edits = new java.util.ArrayList<>(report.trailing());
        edits.addAll(report.lf());
        Path backup = new TextEditPlan(snapshot, edits, gate()).apply();
        assertArrayEquals(bytes("Café.\nSecond.\nLast."), Files.readAllBytes(main));
        var record = EditHistory.list(directory.toRealPath()).getFirst().record();
        assertEquals(2, record.version());
        assertEquals("sanitation", record.intent().operation());
        assertEquals(List.of("  ", "\r\n", "\t", "\r"), record.edits().stream().map(EditHistory.Change::oldText).toList());
        assertEquals(List.of("", "\n", "", "\n"), record.edits().stream().map(EditHistory.Change::newText).toList());
        assertEquals(List.of(SanitationPlanner.TRAILING, SanitationPlanner.EOL, SanitationPlanner.TRAILING, SanitationPlanner.EOL),
                record.edits().stream().map(EditHistory.Change::rule).toList());
        byte[] history = Files.readAllBytes(backup.resolve("changes.json"));
        TextRecovery.revert(directory.toRealPath(), backup);
        assertArrayEquals(before, Files.readAllBytes(main));
        assertArrayEquals(history, Files.readAllBytes(backup.resolve("changes.json")));
        assertEquals("restored", EditHistory.list(directory.toRealPath()).getFirst().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rule", "newText", "oldText", "startByte", "endByte", "afterEndByte", "intent",
            "version", "extra", "extraPath", "scope", "provider", "target", "unknownVersion"})
    void tamperedV2HistoryIsRejected(String field) throws Exception {
        Path main = source("Plain.  \n");
        var snapshot = new DocumentLoader().load(main);
        Path backup = new TextEditPlan(snapshot, new SanitationPlanner().inspect(snapshot).trailing(), gate()).apply();
        Path history = backup.resolve("changes.json");
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) ModelProtocol.JSON.readTree(Files.readAllBytes(history));
        var edit = (com.fasterxml.jackson.databind.node.ObjectNode) json.withArray("edits").get(0);
        switch (field) {
            case "rule" -> edit.put("rule", "unknown");
            case "newText" -> edit.put("newText", "x");
            case "oldText" -> edit.put("oldText", " ");
            case "startByte" -> edit.put("startByte", 0);
            case "endByte" -> edit.put("endByte", 100);
            case "afterEndByte" -> edit.put("afterEndByte", 100);
            case "intent" -> ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("intent")).put("decisionSource", "model");
            case "version" -> json.put("version", 1);
            case "extra" -> edit.put("unknown", "field");
            case "extraPath" -> {
                var unknown = edit.deepCopy();
                unknown.put("path", "other.tex");
                json.withArray("edits").add(unknown);
            }
            case "scope" -> ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("intent")).put("scope", "PROJECT");
            case "provider" -> ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("intent")).put("provider", "openai");
            case "target" -> ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("intent")).put("target", "other.tex");
            case "unknownVersion" -> json.put("version", 3);
        }
        Files.write(history, ModelProtocol.JSON.writeValueAsBytes(json));
        assertThrows(IOException.class, () -> EditHistory.list(directory.toRealPath()));
        assertEquals("Plain.\n", Files.readString(main));
    }

    @Test
    void legacyV1StillOmitsRuleAndRejectsFalseIntent() throws Exception {
        Path main = source("n");
        var snapshot = new DocumentLoader().load(main);
        Path backup = new TextEditPlan(snapshot, List.of(new TextEditPlan.Edit(Path.of("main.tex"), 0, 1, bytes("n"), 1, 1)), "m", gate()).apply();
        var record = EditHistory.list(directory.toRealPath()).getFirst().record();
        assertEquals(1, record.version());
        Path history = backup.resolve("changes.json");
        assertFalse(Files.readString(history).contains("\"rule\""));
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) ModelProtocol.JSON.readTree(Files.readAllBytes(history));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("intent")).put("replacement", "different");
        Files.write(history, ModelProtocol.JSON.writeValueAsBytes(json));
        assertThrows(IOException.class, () -> EditHistory.list(directory.toRealPath()));
        TextRecovery.revert(directory.toRealPath(), backup);
        assertEquals("n", Files.readString(main));
    }

    @Test
    void staleOverlapInsertionUtf8AndWrongProvenanceCannotWrite() throws Exception {
        Path main = source("é  \n");
        var snapshot = new DocumentLoader().load(main);
        var valid = new SanitationPlanner().inspect(snapshot).trailing().getFirst();
        var invalids = List.of(
                List.of(valid, valid),
                List.of(new TextEditPlan.Edit(Path.of("main.tex"), 2, 2, bytes(""), 1, 2, "x", SanitationPlanner.TRAILING)),
                List.of(new TextEditPlan.Edit(Path.of("main.tex"), 1, 2, new byte[]{(byte) 0xa9}, 1, 1, "", SanitationPlanner.TRAILING)),
                List.of(new TextEditPlan.Edit(Path.of("main.tex"), 2, 4, bytes("  "), 1, 2, "", "unknown")),
                List.of(new TextEditPlan.Edit(Path.of("missing.tex"), 2, 4, bytes("  "), 1, 2, "", SanitationPlanner.TRAILING)));
        for (var edits : invalids) assertThrows(IOException.class, () -> new TextEditPlan(snapshot, edits, gate()).apply());
        assertArrayEquals(bytes("é  \n"), Files.readAllBytes(main));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
        Files.writeString(main, "external");
        assertThrows(StaleSourceException.class, () -> new TextEditPlan(snapshot, List.of(valid), gate()).apply());
        assertEquals("external", Files.readString(main));
    }

    @Test
    void protectedPatchesAndPartialEolGroupsAreRejectedAtTheEngineBoundary() throws Exception {
        Path main = source("% protected  \nPlain.\rOther.\r");
        var snapshot = new DocumentLoader().load(main);
        var protectedEdit = new TextEditPlan.Edit(Path.of("main.tex"), 11, 13, bytes("  "), 1, 12, "", SanitationPlanner.TRAILING);
        assertThrows(IOException.class, () -> new TextEditPlan(snapshot, List.of(protectedEdit), gate()).apply());
        var report = new SanitationPlanner().inspect(snapshot);
        assertEquals(2, report.lf().size());
        assertThrows(IOException.class, () -> new TextEditPlan(snapshot, List.of(report.lf().getFirst()), gate()).apply());
        assertEquals("% protected  \nPlain.\rOther.\r", Files.readString(main));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void mixedEolTargetsCannotPassTheCompleteGroupCheck() throws Exception {
        Path main = source("A\rB\rC\n");
        var snapshot = new DocumentLoader().load(main);
        var report = new SanitationPlanner().inspect(snapshot);
        var mixed = List.of(report.lf().getFirst(), report.crlf().get(1));
        IOException error = assertThrows(IOException.class, () -> new TextEditPlan(snapshot, mixed, gate()).apply());
        assertTrue(error.getMessage().contains("every eligible changed terminator"));
        assertEquals("A\rB\rC\n", Files.readString(main));
    }

    @Test
    void exactlyTwoHundredPatchesAndHistoryAreSupported() throws Exception {
        Path main = source("Plain.  \n".repeat(200));
        var snapshot = new DocumentLoader().load(main);
        var report = new SanitationPlanner().inspect(snapshot);
        assertEquals(200, report.trailing().size());
        Path backup = new TextEditPlan(snapshot, report.trailing(), gate()).apply();
        assertEquals(200, EditHistory.list(directory.toRealPath()).getFirst().record().edits().size());
        TextRecovery.revert(directory.toRealPath(), backup);
        assertEquals("Plain.  \n".repeat(200), Files.readString(main));
        Files.writeString(main, "Plain.\r".repeat(200));
        report = new SanitationPlanner().inspect(new DocumentLoader().load(main));
        assertEquals(200, report.lf().size());
        assertEquals(200, report.crlf().size());
    }

    @Test
    void failedMoveRollsBackAndInterruptionCanRecoverMixedEdits() throws Exception {
        Path main = source("First.  \r\nLast.");
        byte[] before = Files.readAllBytes(main);
        var snapshot = new DocumentLoader().load(main);
        var report = new SanitationPlanner().inspect(snapshot);
        var edits = new java.util.ArrayList<>(report.trailing());
        edits.addAll(report.lf());
        var failed = new TextEditPlan(snapshot, edits, gate(), (staged, target) -> { throw new IOException("injected"); });
        assertThrows(IOException.class, failed::apply);
        assertArrayEquals(before, Files.readAllBytes(main));
        assertEquals("restored", EditHistory.list(directory.toRealPath()).getFirst().status());
        var interrupted = new TextEditPlan(snapshot, edits, gate(), (staged, target) -> {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            throw new InterruptedCommit();
        });
        assertThrows(InterruptedCommit.class, interrupted::apply);
        Path pending = TextRecovery.pending(directory.toRealPath()).getFirst();
        assertEquals("First.\nLast.", Files.readString(main));
        TextRecovery.restore(directory.toRealPath(), pending);
        assertArrayEquals(before, Files.readAllBytes(main));
        assertTrue(TextRecovery.pending(directory.toRealPath()).isEmpty());
    }

    private Path source(String text) throws Exception {
        return Files.writeString(directory.resolve("main.tex"), text).toRealPath();
    }

    private static final class InterruptedCommit extends Error { }
}
