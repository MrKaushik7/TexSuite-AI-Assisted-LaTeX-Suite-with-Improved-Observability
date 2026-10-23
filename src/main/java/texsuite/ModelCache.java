package texsuite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/** Private project-local classification results, never saved permission to edit. */
final class ModelCache {
    static final int MAX_ENTRIES = 256;
    static final int MAX_ENTRY_BYTES = 128 * 1024;
    private static final int VERSION = 1;

    private final Path root;
    private final Path data;
    private final Path directory;

    ModelCache(Path root) {
        this.root = root;
        this.data = root.resolve(".tex-suite");
        this.directory = data.resolve("cache");
    }

    static String key(String operation, String scope, String region, ModelClient client,
            ModelClient.Request request, ContextRetriever.Batch batch) throws IOException, ModelClient.Failure {
        ObjectNode identity = ModelProtocol.JSON.createObjectNode();
        identity.put("version", VERSION);
        identity.put("operation", operation);
        identity.put("scope", scope);
        identity.put("region", region);
        identity.put("provider", client.decisionSource());
        identity.put("endpoint", client.destination());
        identity.put("model", client.model());
        identity.put("policy", "classification-v1");
        identity.put("instructions", ModelProtocol.INSTRUCTIONS);
        identity.put("schema", ModelProtocol.SCHEMA);
        identity.set("request", ModelProtocol.JSON.valueToTree(request));
        identity.set("payload", ModelProtocol.JSON.readTree(client.preview(request)));
        var sources = identity.putArray("sources");
        for (var slice : batch.slices()) {
            sources.addObject().put("id", slice.id()).put("hash", slice.sourceHash());
        }
        return fingerprint(identity);
    }

    static String fingerprint(JsonNode value) throws IOException {
        return TextEditPlan.sha256(ModelProtocol.JSON.writeValueAsBytes(canonical(value)));
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = ModelProtocol.JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) result.set(name, canonical(value.get(name)));
            return result;
        }
        if (value.isArray()) {
            var result = ModelProtocol.JSON.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    boolean enabled() throws IOException {
        checkDirectory();
        Path settings = directory.resolve("settings.json");
        if (!Files.exists(settings, LinkOption.NOFOLLOW_LINKS)) return true;

        JsonNode config = ModelProtocol.JSON.readTree(read(settings, 128));
        if (config == null || !config.isObject() || config.size() != 1 || !config.path("enabled").isBoolean()) {
            throw new IOException("Invalid cache settings.");
        }
        return config.path("enabled").booleanValue();
    }

    void setEnabled(boolean enabled) throws IOException {
        write(directory.resolve("settings.json"), ModelProtocol.JSON.writeValueAsBytes(
                ModelProtocol.JSON.createObjectNode().put("enabled", enabled)));
    }

    ModelClient.Result read(String key, ModelClient.Request request) throws IOException, ModelClient.Failure {
        checkDirectory();
        Path path = entry(key);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;

        JsonNode record = ModelProtocol.JSON.readTree(read(path, MAX_ENTRY_BYTES));
        if (record == null || !record.isObject() || record.size() != 3
                || !record.path("version").isIntegralNumber() || !record.path("version").canConvertToInt()
                || record.path("version").intValue() != VERSION
                || !record.path("key").isTextual() || !record.path("key").textValue().equals(key)
                || !record.has("result")) {
            throw new IOException("Invalid cache record.");
        }
        return ModelProtocol.decisions(ModelProtocol.JSON.writeValueAsString(record.get("result")), request);
    }

    void store(String key, ModelClient.Request request, ModelClient.Result result)
            throws IOException, ModelClient.Failure {
        result.validate(request);
        checkDirectory();
        Path path = entry(key);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && entries().size() >= MAX_ENTRIES) {
            throw new IOException("Cache is full; clear it in Settings 10.");
        }

        ObjectNode record = ModelProtocol.JSON.createObjectNode().put("version", VERSION).put("key", key);
        var decisions = record.putObject("result").putArray("decisions");
        for (var decision : result.decisions()) {
            String action = switch (decision.action()) {
                case REPLACE -> "replace";
                case KEEP -> "keep";
                case NEEDS_HUMAN_REVIEW -> "needsHumanReview";
            };
            decisions.addObject().put("id", decision.id()).put("action", action)
                    .put("reason", decision.reason()).put("confidence", decision.confidence());
        }
        byte[] bytes = ModelProtocol.JSON.writeValueAsBytes(record);
        if (bytes.length > MAX_ENTRY_BYTES) throw new IOException("Cache result is too large.");

        write(path, bytes);
    }

    Stats stats() throws IOException {
        long bytes = 0;
        var entries = entries();
        for (Path path : entries) bytes += Files.size(path);
        return new Stats(entries.size(), bytes);
    }

    int clear() throws IOException {
        var entries = entries();
        for (Path path : entries) {
            checkDirectory();
            checkFile(path);
            Files.delete(path);
        }
        return entries.size();
    }

    private List<Path> entries() throws IOException {
        checkDirectory();
        List<Path> entries = new ArrayList<>();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return entries;

        int scanned = 0;
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                if (++scanned > MAX_ENTRIES * 2) throw new IOException("Cache directory is too large.");
                if (path.getFileName().toString().matches("[a-f0-9]{64}\\.json")) {
                    checkFile(path);
                    entries.add(path);
                }
            }
        }
        return entries;
    }

    private Path entry(String key) throws IOException {
        if (!key.matches("[a-f0-9]{64}")) throw new IOException("Invalid cache key.");
        return directory.resolve(key + ".json");
    }

    private byte[] read(Path path, int limit) throws IOException {
        checkFile(path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Cache file is too large.");
            return bytes;
        }
    }

    private void write(Path path, byte[] bytes) throws IOException {
        checkDirectory();
        for (Path folder : List.of(data, directory)) {
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(folder);
            checkDirectory();
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) checkFile(path);

        Path temporary = Files.createTempFile(directory, "cache-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.write(temporary, bytes);
            checkDirectory();
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) checkFile(path);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void checkDirectory() throws IOException {
        if (!root.toRealPath().equals(root)) throw new IOException("Cache project identity changed.");
        for (Path path : List.of(data, directory)) {
            if (Files.isSymbolicLink(path) || Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path))) {
                throw new IOException("Cache storage must be a local directory, not a symbolic link.");
            }
        }
    }

    private void checkFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cache entries must be regular files, not symbolic links.");
        }
    }

    record Stats(int entries, long bytes) { }
}
