package texsuite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable source-bearing details; the existing recovery journal alone determines status. */
final class EditHistory {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private EditHistory() { }

    static void write(Path recovery, DocumentSnapshot snapshot, List<TextEditPlan.Edit> edits,
            String replacement, Map<Path, byte[]> updates, Intent intent) throws IOException {
        List<FileChange> files = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        var groups = new TreeMap<Path, List<TextEditPlan.Edit>>();
        for (var edit : edits) groups.computeIfAbsent(edit.path(), ignored -> new ArrayList<>()).add(edit);

        byte[] next = replacement.getBytes(StandardCharsets.UTF_8);
        for (var group : groups.entrySet()) {
            byte[] before = snapshot.files().get(group.getKey()).bytes();
            byte[] after = updates.get(group.getKey());
            files.add(new FileChange(group.getKey().toString(), TextEditPlan.sha256(before), TextEditPlan.sha256(after)));
            var original = new TexSourceScanner(new String(before, StandardCharsets.UTF_8));
            var updated = new TexSourceScanner(new String(after, StandardCharsets.UTF_8));
            group.getValue().sort(Comparator.comparingInt(TextEditPlan.Edit::start));
            int delta = 0;
            for (var edit : group.getValue()) {
                int start = edit.start() + delta;
                int end = start + next.length;
                changes.add(new Change(group.getKey().toString(), edit.start(), edit.end(), start, end,
                        position(original, edit.start()), position(updated, start),
                        new String(edit.expected(), StandardCharsets.UTF_8), replacement));
                delta += next.length - (edit.end() - edit.start());
            }
        }
        Record record = new Record(1, recovery.getFileName().toString(), Instant.now().toString(), "UTF-16",
                intent, files, changes);
        byte[] bytes = ModelProtocol.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(record);
        if (bytes.length > MAX_BYTES) throw new IOException("Exact edit history exceeds 16 MiB; no source changes.");

        Path temporary = Files.createTempFile(recovery, ".changes-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, recovery.resolve("changes.json"), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static List<Entry> list(Path root) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (Path recovery : TextRecovery.records(root)) {
            var journal = TextRecovery.journal(root, recovery);
            var files = TextRecovery.entries(journal);
            Path history = recovery.resolve("changes.json");
            Record record = null;
            if (Files.exists(history)) {
                if (!history.toRealPath().equals(history) || Files.size(history) > MAX_BYTES) {
                    throw new IOException("Unsafe or oversized edit history: " + recovery.getFileName());
                }
                try {
                    record = ModelProtocol.JSON.readValue(Files.readAllBytes(history), Record.class);
                    validate(record, recovery, files);
                } catch (IllegalArgumentException | NullPointerException | java.time.DateTimeException exception) {
                    throw new IOException("Invalid exact edit history: " + recovery.getFileName(), exception);
                }
            }
            var displayed = new ArrayList<FileState>();
            for (var file : files) {
                Path current = root.resolve(file.relative());
                String expected = journal.getProperty("status").equals("restored") ? file.original() : file.replacement();
                boolean stale = !Files.isRegularFile(current) || !current.toRealPath().equals(current)
                        || !TextEditPlan.sha256(TextRecovery.readBytes(current)).equals(expected);
                displayed.add(new FileState(file.relative().toString(), file.original(), file.replacement(), stale));
            }
            entries.add(new Entry(recovery.getFileName().toString(), journal.getProperty("status", "pending"),
                    journal.getProperty("action", "apply"), record == null, record, displayed));
        }
        return List.copyOf(entries);
    }

    static void print(Path root, PrintWriter out, boolean json) throws IOException {
        List<Entry> entries = list(root);
        if (json) {
            out.println(ModelProtocol.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(entries));
            return;
        }
        if (entries.isEmpty()) out.println("No edit history for this project root.");
        for (var entry : entries) {
            out.printf("Operation %s: %s (%s)%s%n", entry.operationId(), entry.status(), entry.recoveryAction(),
                    entry.legacy() ? " — legacy journal; exact change details unavailable" : "");
            Path recovery = root.resolve(".tex-suite/backups").resolve(entry.operationId());
            if (entry.record() == null) {
                for (var file : entry.files()) out.println("  " + DocumentInput.safeDisplay(file.path())
                        + (file.currentLinkStale() ? " (current source differs)" : ""));
                continue;
            }
            var record = entry.record();
            out.printf("  %s; %s; %s; %s%n", record.timestamp(), record.intent().operation(),
                    record.intent().scope(), record.intent().decisionSource());
            out.println("  Meaning/purpose: " + DocumentInput.safeDisplay(record.intent().meaning()));
            if (record.intent().provider() != null) out.printf("  Provider: %s; model: %s%n",
                    DocumentInput.safeDisplay(record.intent().provider()), DocumentInput.safeDisplay(record.intent().model()));
            for (var edit : record.edits()) {
                var state = entry.files().stream().filter(file -> file.path().equals(edit.path())).findFirst().orElseThrow();
                Position currentPosition = entry.status().equals("restored") ? edit.before() : edit.after();
                Path current = root.resolve(edit.path());
                out.printf("  %s:%d:%d%s: %s -> %s%n", DocumentInput.safeDisplay(edit.path()),
                        currentPosition.line(), currentPosition.column(), state.currentLinkStale() ? " (stale)" : "",
                        DocumentInput.safeDisplay(edit.oldText()), DocumentInput.safeDisplay(edit.newText()));
                out.println("    Current: " + link(current, currentPosition));
                Path backup = recovery.resolve(edit.path());
                out.printf("    Original: %s:%d:%d%n", DocumentInput.safeDisplay(backup), edit.before().line(), edit.before().column());
                out.println("    Backup link: " + link(backup, edit.before()));
            }
        }
    }

    private static void validate(Record record, Path recovery, List<TextRecovery.Entry> journal) throws IOException {
        if (record.version() != 1 || !"UTF-16".equals(record.columnEncoding()) || !record.operationId().equals(recovery.getFileName().toString())
                || record.intent() == null || record.edits().isEmpty() || record.edits().size() > 200
                || record.files().size() != journal.size()) throw new IOException("Invalid edit history header.");
        Instant.parse(record.timestamp());
        for (var file : journal) {
            var matching = record.files().stream().filter(item -> item.path().equals(file.relative().toString())).toList();
            if (matching.size() != 1 || !matching.getFirst().beforeHash().equals(file.original())
                    || !matching.getFirst().afterHash().equals(file.replacement())) {
                throw new IOException("Edit history differs from recovery journal.");
            }
            Path backup = recovery.resolve(file.relative());
            if (!backup.toRealPath().equals(backup)) throw new IOException("Unsafe history backup.");
            byte[] before = TextRecovery.readBytes(backup);
            if (!TextEditPlan.sha256(before).equals(file.original())) throw new IOException("Damaged history backup.");
            var edits = record.edits().stream().filter(item -> item.path().equals(file.relative().toString())).toList();
            if (edits.isEmpty()) throw new IOException("Missing exact edits for history file.");
            var rendered = new ByteArrayOutputStream();
            int previous = 0;
            int delta = 0;
            var scanner = new TexSourceScanner(new String(before, StandardCharsets.UTF_8));
            for (var edit : edits) {
                if (edit.startByte() < previous || edit.endByte() <= edit.startByte() || edit.endByte() > before.length
                        || !edit.oldText().equals(new String(before, edit.startByte(), edit.endByte() - edit.startByte(), StandardCharsets.UTF_8))
                        || !edit.before().equals(position(scanner, edit.startByte()))
                        || edit.afterStartByte() != edit.startByte() + delta
                        || !edit.oldText().equals(record.intent().source()) || !edit.newText().equals(record.intent().replacement())) {
                    throw new IOException("Exact history source span differs from backup.");
                }
                byte[] next = edit.newText().getBytes(StandardCharsets.UTF_8);
                if (edit.afterEndByte() != edit.afterStartByte() + next.length) throw new IOException("Invalid history replacement span.");
                rendered.write(before, previous, edit.startByte() - previous);
                rendered.writeBytes(next);
                previous = edit.endByte();
                delta += next.length - (edit.endByte() - edit.startByte());
            }
            rendered.write(before, previous, before.length - previous);
            byte[] after = rendered.toByteArray();
            if (!TextEditPlan.sha256(after).equals(file.replacement())) throw new IOException("History replacement hash differs.");
            var updated = new TexSourceScanner(new String(after, StandardCharsets.UTF_8));
            for (var edit : edits) {
                if (!edit.after().equals(position(updated, edit.afterStartByte()))) throw new IOException("Invalid history after position.");
            }
        }
        if (record.edits().stream().anyMatch(edit -> journal.stream().noneMatch(file -> file.relative().toString().equals(edit.path())))) {
            throw new IOException("History has an unknown file.");
        }
    }

    private static Position position(TexSourceScanner scanner, int byteOffset) throws IOException {
        try {
            int index = scanner.charIndex(byteOffset);
            return new Position(scanner.line(index), scanner.editorColumn(index));
        } catch (IllegalArgumentException exception) {
            throw new IOException("History position is not a UTF-8 boundary.", exception);
        }
    }

    static String link(Path file, Position position) throws IOException {
        try {
            return new URI("vscode", "file", file.toAbsolutePath().toString() + ":" + position.line()
                    + ":" + position.column(), null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IOException("Cannot encode history position link.", exception);
        }
    }

    record Intent(String operation, String target, String scope, String region, String source,
            String meaning, String replacement, String decisionSource, String provider, String model) { }

    record Position(int line, int column) { }

    record Change(String path, int startByte, int endByte, int afterStartByte, int afterEndByte,
            Position before, Position after, String oldText, String newText) { }

    record FileChange(String path, String beforeHash, String afterHash) { }

    record Record(int version, String operationId, String timestamp, String columnEncoding, Intent intent,
            List<FileChange> files, List<Change> edits) {
        Record {
            files = List.copyOf(files);
            edits = List.copyOf(edits);
        }
    }

    record FileState(String path, String beforeHash, String afterHash, boolean currentLinkStale) { }

    record Entry(String operationId, String status, String recoveryAction, boolean legacy,
            Record record, List<FileState> files) {
        Entry { files = List.copyOf(files); }
    }
}
