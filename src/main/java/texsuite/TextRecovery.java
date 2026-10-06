package texsuite;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Restores an interrupted apply only when both backup and current file hashes are known. */
final class TextRecovery {
    private TextRecovery() { }

    static List<Path> pending(Path root) throws IOException {
        Path backups = root.resolve(".tex-suite/backups");
        if (!Files.exists(backups)) return List.of();
        if (!backups.toRealPath().equals(backups)) throw new IOException("Unsafe backup folder.");
        try (var folders = Files.list(backups)) {
            List<Path> result = new ArrayList<>();
            for (Path folder : folders.sorted().toList()) {
                if (Files.isDirectory(folder) && Files.exists(folder.resolve("recovery.properties"))
                        && isPendingJournal(folder.resolve("recovery.properties"))) result.add(folder);
            }
            return List.copyOf(result);
        }
    }

    static List<Path> targets(Path root, Path recovery) throws IOException {
        return read(root, recovery).stream().map(Entry::relative).toList();
    }

    static void restore(Path root, Path recovery) throws IOException {
        // Validate the whole set before the first write; repeated restoration is safe after interruption.
        List<Entry> entries = read(root, recovery);
        var staged = new java.util.LinkedHashMap<Path, Path>();
        try {
            for (Entry entry : entries) {
                Path target = root.resolve(entry.relative());
                byte[] backup = readBytes(recovery.resolve(entry.relative()));
                if (!TextEditPlan.sha256(backup).equals(entry.original())) {
                    throw new IOException("Backup changed; recovery stopped: " + entry.relative());
                }
                staged.put(target, TextEditPlan.stage(target, backup));
            }
            for (Entry entry : entries) checkCurrent(root.resolve(entry.relative()), entry);
            for (Entry entry : entries) {
                Path target = root.resolve(entry.relative());
                checkCurrent(target, entry);
                Files.move(staged.get(target), target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Properties journal = load(recovery.resolve("recovery.properties"));
            journal.setProperty("status", "restored");
            writeJournalValues(recovery, journal);
        } finally {
            for (Path path : staged.values()) Files.deleteIfExists(path);
        }
    }

    static boolean isPendingJournal(Path journal) {
        Properties values = new Properties();
        try (var input = Files.newInputStream(journal)) {
            if (Files.size(journal) > 1024 * 1024) return true;
            values.load(input);
            String status = values.getProperty("status", "pending");
            return !status.equals("complete") && !status.equals("restored");
        } catch (IOException | IllegalArgumentException exception) {
            return true;
        }
    }

    static void writeJournal(Path recovery, String status, DocumentSnapshot snapshot,
            Map<Path, byte[]> updates) throws IOException {
        Properties values = new Properties();
        values.setProperty("status", status);
        values.setProperty("count", Integer.toString(updates.size()));
        int index = 0;
        for (Map.Entry<Path, byte[]> entry : updates.entrySet()) {
            values.setProperty("file." + index, entry.getKey().toString());
            values.setProperty("original." + index, snapshot.files().get(entry.getKey()).hash());
            values.setProperty("replacement." + index, TextEditPlan.sha256(entry.getValue()));
            index++;
        }
        writeJournalValues(recovery, values);
    }

    private static void writeJournalValues(Path recovery, Properties values) throws IOException {
        Path journal = recovery.resolve("recovery.properties");
        Path temporary = Files.createTempFile(recovery, ".recovery-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "TexSuite text edit recovery");
            }
            Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<Entry> read(Path root, Path recovery) throws IOException {
        Path backups = root.resolve(".tex-suite/backups");
        if (!recovery.getParent().equals(backups) || !recovery.toRealPath().equals(recovery)
                || !root.toRealPath().equals(root)) throw new IOException("Unsafe recovery path.");
        Path journal = recovery.resolve("recovery.properties");
        if (!journal.toRealPath().equals(journal)) throw new IOException("Unsafe recovery journal.");
        Properties values = load(journal);
        if (!"pending".equals(values.getProperty("status"))) {
            throw new IOException("Recovery record is not a valid pending transaction.");
        }
        try {
            int count = Integer.parseInt(values.getProperty("count", "0"));
            if (count < 1 || count > 512) throw new IllegalArgumentException();
            List<Entry> entries = new ArrayList<>();
            var seen = new HashSet<Path>();
            for (int index = 0; index < count; index++) {
                Path relative = Path.of(values.getProperty("file." + index, ""));
                String original = values.getProperty("original." + index, "");
                String replacement = values.getProperty("replacement." + index, "");
                if (relative.toString().isBlank() || relative.isAbsolute()
                        || !relative.normalize().equals(relative) || relative.startsWith("..")
                        || relative.startsWith(".tex-suite") || !seen.add(relative)
                        || !original.matches("[a-f0-9]{64}") || !replacement.matches("[a-f0-9]{64}")) {
                    throw new IllegalArgumentException();
                }
                Path backup = recovery.resolve(relative);
                if (!backup.toRealPath().equals(backup)
                        || !TextEditPlan.sha256(readBytes(backup)).equals(original)) {
                    throw new IOException("Backup is unsafe or damaged: " + relative);
                }
                Entry entry = new Entry(relative, original, replacement);
                checkCurrent(root.resolve(relative), entry);
                entries.add(entry);
            }
            return List.copyOf(entries);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid recovery record; manual inspection required.", exception);
        }
    }

    private static void checkCurrent(Path target, Entry entry) throws IOException {
        if (!target.toRealPath().equals(target) || !Files.isRegularFile(target)) {
            throw new IOException("Recovery target identity changed: " + entry.relative());
        }
        String hash = TextEditPlan.sha256(readBytes(target));
        if (!hash.equals(entry.original()) && !hash.equals(entry.replacement())) {
            throw new IOException("Recovery would overwrite an external edit: " + entry.relative());
        }
    }

    private static byte[] readBytes(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IOException("Recovery file exceeds 16 MiB.");
            return bytes;
        }
    }

    private static Properties load(Path path) throws IOException {
        if (Files.size(path) > 1024 * 1024) throw new IOException("Recovery record is too large.");
        Properties values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Malformed recovery record.", exception);
        }
        return values;
    }

    private record Entry(Path relative, String original, String replacement) { }
}
