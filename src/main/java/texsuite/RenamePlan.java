package texsuite;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Versioned approved intent, not an apply-success record or a replay authorization. */
record RenamePlan(int version, String operation, String snapshotFingerprint, Intent intent,
        String decisionSource, List<AcceptedEdit> edits) {
    RenamePlan {
        edits = List.copyOf(edits);
    }

    static RenamePlan create(DocumentSnapshot snapshot, RenameRequest request,
            List<String> acceptedIds) throws IOException {
        return create(snapshot, request, acceptedIds, "manual");
    }

    static RenamePlan create(DocumentSnapshot snapshot, RenameRequest request,
            List<String> acceptedIds, String decisionSource) throws IOException {
        if (!List.of("manual", "ai-reviewed", "openai-reviewed", "openrouter-reviewed", "gemini-reviewed").contains(decisionSource)) {
            throw new IOException("Unknown rename decision source.");
        }
        if (!new DocumentLoader().isCurrent(snapshot)) {
            throw new StaleSourceException("Saved source changed; reload and review a new preview.");
        }
        var ids = new HashSet<>(acceptedIds);
        if (ids.isEmpty() || ids.size() != acceptedIds.size()) {
            throw new IOException("Rename needs unique, explicitly accepted occurrence IDs.");
        }
        var inventory = new RenameCandidateDiscovery().discover(snapshot, request);
        var accepted = inventory.occurrences().stream().filter(item -> ids.contains(item.id())).toList();
        if (accepted.size() != ids.size() || accepted.stream()
                .anyMatch(item -> item.status() != RenameCandidateDiscovery.Status.CANDIDATE)) {
            throw new IOException("Rename may only edit scanner-approved occurrences in the selected scope.");
        }
        List<AcceptedEdit> edits = accepted.stream().map(item -> new AcceptedEdit(item.id(),
                item.path().toString(), item.sourceHash(), item.startByte(), item.endByte(),
                TextEditPlan.sha256(Arrays.copyOfRange(snapshot.files().get(item.path()).bytes(),
                        item.startByte(), item.endByte())), item.line(), item.column())).toList();
        return new RenamePlan(1, "mathematical-rename", snapshot.fingerprint(),
                new Intent(snapshot.main().toString(), request.scope().name(), request.source(),
                        request.meaning(), request.replacement()), decisionSource, edits);
    }

    private void validate(DocumentSnapshot snapshot) throws IOException {
        // Rebuild from the scanner inventory so saved IDs cannot authorize different spans.
        RenamePlan expected;
        try {
            RenameRequest request = new RenameRequest(snapshot.root().resolve(intent.target()),
                    intent.source(), intent.meaning(), intent.replacement(),
                    RenameRequest.Scope.valueOf(intent.scope()));
            expected = create(snapshot, request, edits.stream().map(AcceptedEdit::id).toList(), decisionSource);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid rename plan request.", exception);
        }
        if (!equals(expected)) {
            throw new IOException("Rename plan differs from the current approved occurrence inventory.");
        }
    }

    List<TextEditPlan.Edit> editsFor(DocumentSnapshot snapshot) throws IOException {
        validate(snapshot);
        byte[] source = intent.source().getBytes(StandardCharsets.UTF_8);
        return edits.stream().map(edit -> new TextEditPlan.Edit(Path.of(edit.path()),
                edit.startByte(), edit.endByte(), source, edit.line(), edit.column())).toList();
    }

    Path save(DocumentSnapshot snapshot) throws IOException {
        validate(snapshot);
        Path data = snapshot.root().resolve(".tex-suite");
        privateDirectory(data);
        Path plans = data.resolve("plans");
        privateDirectory(plans);
        Path destination = plans.resolve(UUID.randomUUID() + ".json");
        Path temporary = Files.createTempFile(plans, ".rename-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(this));
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return destination;
    }

    private static void privateDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Plan storage must not be a symbolic link.");
        Files.createDirectories(directory);
        if (!directory.toRealPath().equals(directory)) throw new IOException("Plan storage identity changed.");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    record Intent(String target, String scope, String source, String meaning, String replacement) { }

    record AcceptedEdit(String id, String path, String sourceHash, int startByte, int endByte,
            String expectedHash, int line, int column) { }
}
