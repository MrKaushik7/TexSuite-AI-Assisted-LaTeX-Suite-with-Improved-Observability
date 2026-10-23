package texsuite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class TextEditPlan {
    private final DocumentSnapshot snapshot;
    private final List<Edit> edits;
    private final Committer committer;
    private final TexCompileGate compileGate;
    private final EditHistory.Intent intent;

    TextEditPlan(DocumentSnapshot snapshot, List<Edit> edits, String replacement,
            TexCompileGate compileGate) {
        this(snapshot, edits, replacement, compileGate, (staged, target) -> Files.move(staged, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    TextEditPlan(DocumentSnapshot snapshot, List<Edit> edits, String replacement,
            TexCompileGate compileGate, Committer committer) {
        this(snapshot, edits, replacement, compileGate, committer, new EditHistory.Intent("literal-edit",
                snapshot.main().toString(), snapshot.coverage().name(), "UNSPECIFIED",
                edits.isEmpty() ? "" : new String(edits.getFirst().expected(), StandardCharsets.UTF_8),
                "explicitly approved literal changes", replacement, "manual", null, null));
    }

    TextEditPlan(DocumentSnapshot snapshot, List<Edit> edits, String replacement,
            TexCompileGate compileGate, EditHistory.Intent intent) {
        this(snapshot, edits, replacement, compileGate, (staged, target) -> Files.move(staged, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), intent);
    }

    TextEditPlan(DocumentSnapshot snapshot, List<Edit> edits, String replacement,
            TexCompileGate compileGate, Committer committer, EditHistory.Intent intent) {
        this.snapshot = snapshot;
        this.edits = edits.stream().map(edit -> new Edit(edit.path(), edit.start(), edit.end(),
                edit.expected(), edit.line(), edit.column(), replacement, null)).toList();
        this.committer = committer;
        this.compileGate = compileGate;
        this.intent = intent;
    }

    TextEditPlan(DocumentSnapshot snapshot, List<Edit> edits, TexCompileGate compileGate) {
        this(snapshot, edits, compileGate, (staged, target) -> Files.move(staged, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    TextEditPlan(DocumentSnapshot snapshot, List<Edit> edits, TexCompileGate compileGate,
            Committer committer) {
        this.snapshot = snapshot;
        this.edits = List.copyOf(edits);
        this.compileGate = compileGate;
        this.committer = committer;
        this.intent = new EditHistory.Intent("sanitation", snapshot.main().toString(), "FILE",
                "SOURCE", "", "explicitly selected deterministic whitespace fixes", "",
                "deterministic", null, null);
    }

    int size() {
        return edits.size();
    }

    Map<Path, byte[]> preview() throws IOException {
        validate();
        return prepareUpdates();
    }

    void validate() throws IOException {
        if (edits.size() > 200) throw new IOException("At most 200 edits can be applied at once; select fewer fixes.");
        if (!new DocumentLoader().isCurrent(snapshot)) {
            throw new StaleSourceException("Saved source changed; reload and review a new preview.");
        }
        if (intent.operation().equals("sanitation")) validateSanitation();
        for (Map.Entry<Path, List<Edit>> group : byFile().entrySet()) {
            if (!snapshot.files().containsKey(group.getKey())) throw new IOException("Unknown edit file.");
            byte[] original = snapshot.files().get(group.getKey()).bytes();
            var scanner = new TexSourceScanner(new String(original, StandardCharsets.UTF_8));
            int previousEnd = 0;
            for (Edit edit : group.getValue()) {
                if (edit.start() < previousEnd || edit.end() > original.length
                        || edit.start() >= edit.end()
                        || !Arrays.equals(original, edit.start(), edit.end(), edit.expected(),
                                0, edit.expected().length)) {
                    throw new IOException("Edit spans overlap or expected source bytes differ.");
                }
                try {
                    scanner.charIndex(edit.start());
                    scanner.charIndex(edit.end());
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Edit span is not aligned to UTF-8 boundaries.", exception);
                }
                if (edit.replacement() == null || (intent.operation().equals("sanitation")
                        && (!edit.path().equals(snapshot.main()) || !SanitationPlanner.validFix(
                                edit.rule(), new String(edit.expected(), StandardCharsets.UTF_8), edit.replacement())))) {
                    throw new IOException("Invalid sanitation patch or provenance.");
                }
                previousEnd = edit.end();
            }
            byte[] updated = render(original, group.getValue());
            List<String> before = structureCodes(original);
            List<String> after = structureCodes(updated);
            if (!before.equals(after)) {
                throw new IOException("Replacement changes the source's structural diagnostics.");
            }
        }
    }

    private void validateSanitation() throws IOException {
        var report = new SanitationPlanner().inspect(snapshot);
        var allowed = new ArrayList<>(report.trailing());
        allowed.addAll(report.lf());
        allowed.addAll(report.crlf());
        for (var edit : edits) {
            if (allowed.stream().noneMatch(candidate -> samePatch(edit, candidate))) {
                throw new IOException("Sanitation patch is outside the supported safe fixes.");
            }
        }
        var endings = edits.stream().filter(edit -> SanitationPlanner.EOL.equals(edit.rule())).toList();
        if (!endings.isEmpty()) {
            var wholeGroup = endings.getFirst().replacement().equals("\n") ? report.lf() : report.crlf();
            if (endings.size() != wholeGroup.size()
                    || wholeGroup.stream().anyMatch(candidate -> endings.stream().noneMatch(edit -> samePatch(edit, candidate)))) {
                throw new IOException("Line-ending normalization must include every eligible changed terminator.");
            }
        }
    }

    private static boolean samePatch(Edit edit, Edit candidate) {
        return edit.path().equals(candidate.path()) && edit.start() == candidate.start() && edit.end() == candidate.end()
                && Arrays.equals(edit.expected(), candidate.expected())
                && java.util.Objects.equals(edit.replacement(), candidate.replacement())
                && java.util.Objects.equals(edit.rule(), candidate.rule());
    }

    Path apply() throws IOException {
        return apply(() -> { });
    }

    Path apply(TexCompileGate.Freshness contextFreshness) throws IOException {
        if (edits.isEmpty()) throw new IOException("Empty selection cannot be applied.");
        validate();
        contextFreshness.check();
        Path data = snapshot.root().resolve(".tex-suite");
        Path backups = data.resolve("backups");
        if (Files.isSymbolicLink(data) || Files.isSymbolicLink(backups)) {
            throw new IOException("Backup folder must not be a symbolic link.");
        }
        if (!TextRecovery.pending(snapshot.root()).isEmpty()) {
            throw new IOException("A previous text edit needs recovery before another apply. "
                    + "Use Settings > recover interrupted edits.");
        }
        Map<Path, byte[]> updates = prepareUpdates();
        TexCompileGate.Freshness compilation = compileGate.validate(snapshot, updates);
        validate();
        contextFreshness.check();
        Path recovery = createRecoveryDirectory(backups);
        Map<Path, Path> staged = new TreeMap<>();
        List<Path> changed = new ArrayList<>();
        boolean journalWritten = false;
        try {
            stageBackups(recovery, updates, staged);
            EditHistory.write(recovery, snapshot, edits, updates, intent);
            TextRecovery.writeJournal(recovery, "pending", snapshot, updates);
            journalWritten = true;
            if (!new DocumentLoader().isCurrent(snapshot)) {
                throw new StaleSourceException("Saved source changed before apply; reload and review again.");
            }
            compilation.check();
            contextFreshness.check();
            commitStaged(staged, changed);
            TextRecovery.writeJournal(recovery, "complete", snapshot, updates);
        } catch (IOException failure) {
            boolean recovered = rollbackChanged(recovery, updates, changed, failure);
            if (journalWritten && recovered) {
                try {
                    TextRecovery.writeJournal(recovery, "restored", snapshot, updates);
                } catch (IOException journalFailure) {
                    failure.addSuppressed(journalFailure);
                    recovered = false;
                }
            }
            if (!recovered) {
                throw new IOException("Recovery required at " + recovery, failure);
            }
            throw failure;
        } finally {
            for (Path temporary : staged.values()) Files.deleteIfExists(temporary);
        }
        return recovery;
    }

    // Computes replacement bytes without touching saved source.
    private Map<Path, byte[]> prepareUpdates() {
        Map<Path, byte[]> updates = new TreeMap<>();
        for (var group : byFile().entrySet()) {
            updates.put(group.getKey(), render(snapshot.files().get(group.getKey()).bytes(), group.getValue()));
        }
        return updates;
    }

    private static Path createRecoveryDirectory(Path backups) throws IOException {
        Files.createDirectories(backups);
        privateDirectory(backups.getParent());
        privateDirectory(backups);
        Path recovery = backups.resolve(UUID.randomUUID().toString());
        Files.createDirectory(recovery);
        privateDirectory(recovery);
        return recovery;
    }

    // Caller owns partial progress so finally can clean up after a staging failure.
    private void stageBackups(Path recovery, Map<Path, byte[]> updates,
            Map<Path, Path> staged) throws IOException {
        for (Map.Entry<Path, byte[]> group : updates.entrySet()) {
            Path target = snapshot.root().resolve(group.getKey());
            byte[] expected = snapshot.files().get(group.getKey()).bytes();
            if (!target.toRealPath().equals(target)
                    || !Arrays.equals(expected, Files.readAllBytes(target))) {
                throw new StaleSourceException("Saved source changed during apply; reload and review again.");
            }
            Path backup = recovery.resolve(group.getKey()).normalize();
            if (!backup.startsWith(recovery)) throw new IOException("Backup path escapes root.");
            Files.createDirectories(backup.getParent());
            privateDirectory(backup.getParent());
            Files.write(backup, expected);
            Files.setPosixFilePermissions(backup,
                    PosixFilePermissions.fromString("rw-------"));
            byte[] updated = group.getValue();
            staged.put(group.getKey(), stage(target, updated));
        }
    }

    // Record a changed target only after its atomic move succeeds.
    private void commitStaged(Map<Path, Path> staged, List<Path> changed) throws IOException {
        for (Map.Entry<Path, Path> entry : staged.entrySet()) {
            Path target = snapshot.root().resolve(entry.getKey());
            if (!target.toRealPath().equals(target)
                    || !Arrays.equals(snapshot.files().get(entry.getKey()).bytes(),
                            Files.readAllBytes(target))) {
                throw new StaleSourceException("Saved source changed during apply; reload and review again.");
            }
            committer.move(entry.getValue(), target);
            changed.add(entry.getKey());
        }
    }

    private boolean rollbackChanged(Path recovery, Map<Path, byte[]> updates,
            List<Path> changed, IOException failure) {
        boolean recovered = true;
        for (int index = changed.size() - 1; index >= 0; index--) {
            Path relative = changed.get(index);
            try {
                Path target = snapshot.root().resolve(relative);
                if (!target.toRealPath().equals(target)
                        || !Arrays.equals(updates.get(relative), Files.readAllBytes(target))) {
                    throw new IOException("Externally changed file cannot be rolled back: " + relative);
                }
                byte[] original = Files.readAllBytes(recovery.resolve(relative));
                if (!sha256(original).equals(snapshot.files().get(relative).hash())) {
                    throw new IOException("Backup changed; cannot roll back: " + relative);
                }
                replaceAtomically(target, original);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                recovered = false;
            }
        }
        return recovered;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private Map<Path, List<Edit>> byFile() {
        Map<Path, List<Edit>> groups = new TreeMap<>();
        for (Edit edit : edits) {
            groups.computeIfAbsent(edit.path(), ignored -> new ArrayList<>()).add(edit);
        }
        for (List<Edit> group : groups.values()) {
            group.sort(Comparator.comparingInt(Edit::start));
        }
        return groups;
    }

    private byte[] render(byte[] original, List<Edit> group) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int previous = 0;
        for (Edit edit : group) {
            output.write(original, previous, edit.start() - previous);
            output.writeBytes(edit.replacement().getBytes(StandardCharsets.UTF_8));
            previous = edit.end();
        }
        output.write(original, previous, original.length - previous);
        return output.toByteArray();
    }

    private static List<String> structureCodes(byte[] bytes) {
        return new TexSourceScanner(new String(bytes, StandardCharsets.UTF_8)).scan().problems()
                .stream().map(TexSourceScanner.Problem::code).sorted().toList();
    }

    private static void privateDirectory(Path folder) throws IOException {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
    }

    static void replaceAtomically(Path target, byte[] updated) throws IOException {
        Path staged = stage(target, updated);
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    static Path stage(Path target, byte[] updated) throws IOException {
        Path staged = Files.createTempFile(target.getParent(), ".texsuite-", ".tmp");
        try {
            Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(target));
            Files.write(staged, updated);
            return staged;
        } catch (IOException failure) {
            Files.deleteIfExists(staged);
            throw failure;
        }
    }

    record Edit(Path path, int start, int end, byte[] expected, int line, int column,
            String replacement, String rule) {
        Edit(Path path, int start, int end, byte[] expected, int line, int column) {
            this(path, start, end, expected, line, column, null, null);
        }

        Edit {
            expected = expected.clone();
        }

        @Override
        public byte[] expected() {
            return expected.clone();
        }
    }

    @FunctionalInterface
    interface Committer {
        void move(Path staged, Path target) throws IOException;
    }
}
