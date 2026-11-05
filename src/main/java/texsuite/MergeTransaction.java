package texsuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

/** Merge adapter to the shared hash-checked backup, history and recovery journal. */
final class MergeTransaction {
    private MergeTransaction() { }

    static Path apply(MergePlanner.Proposal proposal, TexCompileGate gate) throws IOException {
        return apply(proposal, gate, warning -> { });
    }

    static Path apply(MergePlanner.Proposal proposal, TexCompileGate gate, java.util.function.Consumer<String> report) throws IOException {
        return apply(proposal, gate, (staged, target) -> Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING), report);
    }

    static Path apply(MergePlanner.Proposal proposal, TexCompileGate gate, TextEditPlan.Committer committer) throws IOException {
        return apply(proposal, gate, committer, warning -> { });
    }

    private static Path apply(MergePlanner.Proposal proposal, TexCompileGate gate, TextEditPlan.Committer committer,
            java.util.function.Consumer<String> report) throws IOException {
        var snapshot = proposal.master();
        Path root = snapshot.root();
        proposal.freshness().check();
        if (!TextRecovery.pending(root).isEmpty()) throw new IOException("Resolve pending recovery before merging.");
        var updates = new TreeMap<Path,byte[]>(proposal.updates());
        for (var file : proposal.creates().entrySet()) if (updates.putIfAbsent(file.getKey(),file.getValue()) != null) throw new IOException("Conflicting create/replace destination.");
        validateTargets(proposal,updates);
        var compiled = gate.validateMerge(snapshot, proposal.updates(), proposal.creates(), report);
        proposal.freshness().check(); compiled.check(); validateTargets(proposal,updates);
        Path backups = root.resolve(".tex-suite/backups");
        checkParents(backups.resolve("placeholder"));
        Files.createDirectories(backups);
        Files.setPosixFilePermissions(backups,PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(backups.getParent(),PosixFilePermissions.fromString("rwx------"));
        Path recovery = Files.createDirectory(backups.resolve(UUID.randomUUID().toString()));
        Files.setPosixFilePermissions(recovery,PosixFilePermissions.fromString("rwx------"));
        var staged = new TreeMap<Path,Path>();
        Properties journal = new Properties();
        journal.setProperty("version","2"); journal.setProperty("status","pending"); journal.setProperty("count",Integer.toString(updates.size()));
        boolean written = false;
        try {
            int i = 0;
            for (var entry : updates.entrySet()) {
                Path relative = entry.getKey(); Path target = root.resolve(relative);
                byte[] original = proposal.creates().containsKey(relative) ? null : snapshot.files().get(relative).bytes();
                if (original != null) {
                    Path backup = recovery.resolve(relative); Files.createDirectories(backup.getParent());
                    Files.write(backup,original); Files.setPosixFilePermissions(backup,PosixFilePermissions.fromString("rw-------"));
                }
                Path stage = Files.createTempFile(recovery,"payload-",".tmp");
                Files.setPosixFilePermissions(stage,PosixFilePermissions.fromString("rw-------"));
                Files.write(stage,entry.getValue()); staged.put(relative,stage);
                journal.setProperty("file."+i,relative.toString()); journal.setProperty("original."+i,original == null ? "ABSENT" : TextEditPlan.sha256(original));
                journal.setProperty("replacement."+i,TextEditPlan.sha256(entry.getValue())); i++;
            }
            writeHistory(recovery,proposal,updates);
            TextRecovery.writeJournalValues(recovery,journal); written = true;
            proposal.freshness().check(); compiled.check(); validateTargets(proposal,updates);
            // Imports precede master changes; recovery knows the entire set before either begins.
            for (Path relative : proposal.creates().keySet().stream().sorted().toList()) {
                Path target = root.resolve(relative); checkParents(target); Files.createDirectories(target.getParent()); checkParents(target);
                Files.createLink(target,staged.get(relative)); // Atomic no-clobber publication on the same filesystem.
            }
            for (Path relative : proposal.updates().keySet().stream().sorted().toList()) {
                Path target = root.resolve(relative);
                if (!target.toRealPath().equals(target) || !java.util.Arrays.equals(snapshot.files().get(relative).bytes(),TextRecovery.readBytes(target))) throw new StaleSourceException("Master changed during apply.");
                Files.setPosixFilePermissions(staged.get(relative),Files.getPosixFilePermissions(target));
                committer.move(staged.get(relative),target);
            }
            journal.setProperty("status","complete"); journal.setProperty("completedAt",Instant.now().toString()); TextRecovery.writeJournalValues(recovery,journal);
        } catch (IOException failure) {
            if (written) {
                try { TextRecovery.restore(root,recovery); }
                catch (IOException rollback) { failure.addSuppressed(rollback); throw new IOException("Merge recovery required at " + recovery,failure); }
            }
            throw failure;
        } finally {
            for (Path path : staged.values()) Files.deleteIfExists(path);
        }
        return recovery;
    }

    static void checkParents(Path target) throws IOException {
        Path current = target.toAbsolutePath().normalize().getParent();
        while (current != null) {
            if (Files.exists(current,LinkOption.NOFOLLOW_LINKS) && (!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(current))) throw new IOException("Unsafe destination parent: " + current);
            current = current.getParent();
        }
    }

    private static void validateTargets(MergePlanner.Proposal proposal, Map<Path,byte[]> updates) throws IOException {
        if (updates.isEmpty() || updates.size() > 512 || proposal.edits().size() > 200) throw new IOException("Merge exceeds operation bounds.");
        long total = 0;
        for (var entry : updates.entrySet()) {
            Path relative = entry.getKey();
            if (relative.toString().isBlank() || relative.toString().equals(".") || relative.isAbsolute() || !relative.normalize().equals(relative) || relative.startsWith("..") || relative.startsWith(".tex-suite")) throw new IOException("Unsafe merge destination.");
            total += entry.getValue().length;
            if (entry.getValue().length > 16*1024*1024 || total > 64L*1024*1024) throw new IOException("Merge import size limit exceeded.");
            Path target = proposal.master().root().resolve(relative); checkParents(target);
            if (proposal.creates().containsKey(relative)) {
                if (!relative.startsWith("texsuite-imports") || Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Import would overwrite an existing path: " + relative);
            } else if (!proposal.master().files().containsKey(relative) || !target.toRealPath().equals(target)
                    || !java.util.Arrays.equals(proposal.master().files().get(relative).bytes(),TextRecovery.readBytes(target))) throw new StaleSourceException("Master bytes changed.");
        }
        // Reconstruct the exact approved anchored edits, including zero-width insertions.
        for (var entry : proposal.updates().entrySet()) {
            byte[] original = proposal.master().files().get(entry.getKey()).bytes();
            var output = new java.io.ByteArrayOutputStream(); int previous = 0;
            int lastStart = -1;
            var scanner = new TexSourceScanner(new String(original,java.nio.charset.StandardCharsets.UTF_8));
            for (var edit : proposal.edits().stream().filter(e -> e.path().equals(entry.getKey())).sorted(java.util.Comparator.comparingInt(TextEditPlan.Edit::start)).toList()) {
                if (edit.start() == lastStart || edit.start() < previous || edit.end() < edit.start() || edit.end() > original.length
                        || !java.util.Arrays.equals(java.util.Arrays.copyOfRange(original,edit.start(),edit.end()),edit.expected())) throw new IOException("Merge anchor/span differs from approved source.");
                try { scanner.charIndex(edit.start()); scanner.charIndex(edit.end()); } catch (IllegalArgumentException e) { throw new IOException("Invalid UTF-8 merge boundary.",e); }
                output.write(original,previous,edit.start()-previous); output.writeBytes(edit.replacement().getBytes(java.nio.charset.StandardCharsets.UTF_8)); previous=edit.end();
                lastStart = edit.start();
            }
            output.write(original,previous,original.length-previous);
            if (!java.util.Arrays.equals(output.toByteArray(),entry.getValue())) throw new IOException("Merge edits do not reconstruct proposed bytes.");
        }
    }

    private static void writeHistory(Path recovery, MergePlanner.Proposal proposal, Map<Path,byte[]> updates) throws IOException {
        var files = new ArrayList<EditHistory.FileChange>(); var changes = new ArrayList<EditHistory.Change>();
        var position = new EditHistory.Position(1,1);
        for (var entry : updates.entrySet()) {
            boolean created = proposal.creates().containsKey(entry.getKey());
            byte[] before = created ? new byte[0] : proposal.master().files().get(entry.getKey()).bytes();
            files.add(new EditHistory.FileChange(entry.getKey().toString(),created ? "ABSENT" : TextEditPlan.sha256(before),TextEditPlan.sha256(entry.getValue())));
            changes.add(new EditHistory.Change(entry.getKey().toString(),0,before.length,0,entry.getValue().length,position,position,
                    Base64.getEncoder().encodeToString(before),Base64.getEncoder().encodeToString(entry.getValue()),"merge-file-base64"));
        }
        var intent = new EditHistory.Intent("merge",proposal.master().main().toString(),"EXPLICIT_IMPORTS","SOURCE","",
                String.join("; ",proposal.decisions()),"","manual",null,null);
        var record = new EditHistory.Record(3,recovery.getFileName().toString(),Instant.now().toString(),"UTF-16",intent,files,changes);
        byte[] bytes = ModelProtocol.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(record);
        if (bytes.length > 16*1024*1024) throw new IOException("Merge history exceeds 16 MiB; no source changes.");
        Path history = recovery.resolve("changes.json"); Files.write(history,bytes);
        Files.setPosixFilePermissions(history,PosixFilePermissions.fromString("rw-------"));
    }

    static void validateHistory(EditHistory.Record record, Path recovery, List<TextRecovery.Entry> journal) throws IOException {
        if (!record.operationId().equals(recovery.getFileName().toString()) || !record.intent().operation().equals("merge")
                || !record.intent().scope().equals("EXPLICIT_IMPORTS") || !record.columnEncoding().equals("UTF-16")
                || !record.intent().region().equals("SOURCE") || !record.intent().source().isEmpty()
                || !record.intent().replacement().isEmpty() || !record.intent().decisionSource().equals("manual")
                || record.intent().provider() != null || record.intent().model() != null
                || record.files().size() != journal.size() || record.edits().size() != journal.size()) throw new IOException("Invalid merge history header.");
        Instant.parse(record.timestamp());
        for (var file : journal) {
            var fs = record.files().stream().filter(f -> f.path().equals(file.relative().toString())).toList();
            var es = record.edits().stream().filter(e -> e.path().equals(file.relative().toString())).toList();
            if (fs.size()!=1 || es.size()!=1 || !fs.getFirst().beforeHash().equals(file.original()) || !fs.getFirst().afterHash().equals(file.replacement())) throw new IOException("Merge history differs from journal.");
            var edit = es.getFirst();
            byte[] before, after;
            try { before=Base64.getDecoder().decode(edit.oldText()); after=Base64.getDecoder().decode(edit.newText()); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid merge history bytes.",e); }
            if (!"merge-file-base64".equals(edit.rule()) || edit.startByte()!=0 || edit.endByte()!=before.length || edit.afterStartByte()!=0 || edit.afterEndByte()!=after.length
                    || !edit.before().equals(new EditHistory.Position(1,1)) || !edit.after().equals(new EditHistory.Position(1,1))
                    || !TextEditPlan.sha256(after).equals(file.replacement()) || (file.created() ? before.length!=0 : !TextEditPlan.sha256(before).equals(file.original()))) throw new IOException("Invalid merge history contents.");
            if (!file.created()) {
                Path backup = recovery.resolve(file.relative());
                if (!backup.toRealPath().equals(backup) || !java.util.Arrays.equals(before,TextRecovery.readBytes(backup))) throw new IOException("Merge history backup differs.");
            }
        }
    }
}
