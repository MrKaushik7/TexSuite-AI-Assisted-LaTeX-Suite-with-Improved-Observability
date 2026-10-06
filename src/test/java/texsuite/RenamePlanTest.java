package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An approved plan must remain bound to eligible occurrences in its original saved source. */
final class RenamePlanTest {
    @TempDir Path directory;

    @Test
    void rejectsUnknownDuplicateProtectedAndUncertainIds() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "$n$ prose n \\iftrue $n$\\fi").toRealPath();
        var snapshot = new DocumentLoader().loadFile(source);
        var request = request(source);
        var occurrences = new RenameCandidateDiscovery().discover(snapshot, request).occurrences();
        String candidate = occurrences.stream().filter(item -> item.status() == RenameCandidateDiscovery.Status.CANDIDATE)
                .findFirst().orElseThrow().id();
        for (var ids : List.of(List.<String>of(), List.of("unknown"), List.of(candidate, candidate))) {
            assertThrows(IOException.class, () -> RenamePlan.create(snapshot, request, ids));
        }
        for (var occurrence : occurrences) {
            if (occurrence.status() != RenameCandidateDiscovery.Status.CANDIDATE) {
                assertThrows(IOException.class, () -> RenamePlan.create(snapshot, request, List.of(occurrence.id())));
            }
        }
        assertEquals(1, RenamePlan.create(snapshot, request, List.of(candidate)).editsFor(snapshot).size());
    }

    @Test
    void rejectsTamperedVersionAndRangesAndStaleSource() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "$n$").toRealPath();
        var snapshot = new DocumentLoader().loadFile(source);
        var request = request(source);
        var id = new RenameCandidateDiscovery().discover(snapshot, request).occurrences().getFirst().id();
        var plan = RenamePlan.create(snapshot, request, List.of(id));
        var wrongVersion = new RenamePlan(2, plan.operation(), plan.snapshotFingerprint(), plan.intent(), plan.decisionSource(), plan.edits());
        assertThrows(IOException.class, () -> wrongVersion.editsFor(snapshot));
        var edit = plan.edits().getFirst();
        var wrongRange = new RenamePlan(plan.version(), plan.operation(), plan.snapshotFingerprint(), plan.intent(), plan.decisionSource(),
                List.of(new RenamePlan.AcceptedEdit(edit.id(), edit.path(), edit.sourceHash(), 0, edit.endByte(), edit.expectedHash(), edit.line(), edit.column())));
        assertThrows(IOException.class, () -> wrongRange.editsFor(snapshot));
        Files.writeString(source, "$n+1$");
        assertThrows(StaleSourceException.class, () -> plan.editsFor(snapshot));
        assertThrows(StaleSourceException.class, () -> plan.save(snapshot));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void refusesSymlinkPlanStorage() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "$n$").toRealPath();
        var snapshot = new DocumentLoader().loadFile(source);
        var request = request(source);
        var id = new RenameCandidateDiscovery().discover(snapshot, request).occurrences().getFirst().id();
        var plan = RenamePlan.create(snapshot, request, List.of(id));
        Path other = Files.createDirectory(directory.resolve("other"));
        Files.createSymbolicLink(directory.resolve(".tex-suite"), other);
        assertThrows(IOException.class, () -> plan.save(snapshot));
        try (var files = Files.list(other)) { assertEquals(0, files.count()); }
    }

    private RenameRequest request(Path source) {
        return new RenameRequest(source, "n", "length", "\\numElements", RenameRequest.Scope.FILE);
    }
}
