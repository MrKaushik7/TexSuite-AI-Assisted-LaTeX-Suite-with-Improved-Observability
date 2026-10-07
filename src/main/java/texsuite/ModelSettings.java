package texsuite;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Function;

/** Explicit provider profiles. Configuration stores environment names, never key values. */
final class ModelSettings {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_CONFIG_BYTES = 16_384;

    private final Path storage;
    private final Function<String, String> environment;
    private Profile session;

    ModelSettings(Path root) {
        this(root, System::getenv);
    }

    ModelSettings(Path root, Function<String, String> environment) {
        this.storage = root.resolve(".tex-suite/config.json");
        this.environment = environment;
    }

    Profile load() throws IOException {
        if (session != null) return session;

        ObjectNode config = read();
        try {
            Provider provider = Provider.parse(setting(config, "modelProvider", "TEXSUITE_MODEL_PROVIDER", "openai"));
            JsonNode values = config.path(provider.id());
            if (!values.isMissingNode() && !values.isObject()) throw new IOException("Invalid model profile.");
            String prefix = "TEXSUITE_" + provider.name() + "_";
            return new Profile(provider, setting(values, "model", prefix + "MODEL", ""),
                    setting(values, "keyEnvironment", prefix + "KEY_ENV", provider.keyEnvironment()),
                    Integer.parseInt(setting(values, "timeoutSeconds", prefix + "TIMEOUT", "60")),
                    OutputMode.parse(setting(values, "outputMode", prefix + "OUTPUT_MODE", "json_schema")));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid provider, model, key-environment name or timeout.");
        }
    }

    void useSession(Profile profile) {
        session = profile;
    }

    private String setting(JsonNode values, String field, String variable, String fallback) throws IOException {
        JsonNode value = values.get(field);
        if (value != null) {
            if (!value.isTextual() && !(field.equals("timeoutSeconds") && value.isIntegralNumber())) {
                throw new IOException("Invalid model profile field: " + field);
            }
            return value.asText();
        }
        String external = environment.apply(variable);
        return external == null ? fallback : external;
    }

    void save(Profile profile) throws IOException {
        ObjectNode config = read();
        config.put("modelProvider", profile.provider().id());
        ObjectNode values = config.putObject(profile.provider().id());
        values.put("model", profile.model());
        values.put("keyEnvironment", profile.keyEnvironment());
        values.put("timeoutSeconds", profile.timeoutSeconds());
        values.put("outputMode", profile.outputMode().id());
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(config);
        if (bytes.length > MAX_CONFIG_BYTES) throw new IOException("Project settings are too large.");

        Path directory = storage.getParent();
        Files.createDirectories(directory);
        checkStorage();
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path temporary = Files.createTempFile(directory, "model-", ".json");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.write(temporary, bytes);
            checkStorage();
            Files.move(temporary, storage, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            session = profile;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private ObjectNode read() throws IOException {
        checkStorage();
        if (!Files.exists(storage, LinkOption.NOFOLLOW_LINKS)) return JSON.createObjectNode();
        if (Files.size(storage) > MAX_CONFIG_BYTES) throw new IOException("Project settings are too large.");
        byte[] bytes;
        try (var input = Files.newInputStream(storage)) {
            bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
        }
        if (bytes.length > MAX_CONFIG_BYTES) throw new IOException("Project settings are too large.");

        JsonNode config;
        try {
            config = JSON.readTree(bytes);
        } catch (IOException exception) {
            throw new IOException("Invalid project settings JSON.");
        }
        if (!(config instanceof ObjectNode object)) throw new IOException("Project settings must be an object.");
        return object;
    }

    private void checkStorage() throws IOException {
        Path directory = storage.getParent();
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(storage)) {
            throw new IOException("Model settings storage must not be a symbolic link.");
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(directory) || !directory.toRealPath().equals(directory))) {
            throw new IOException("Model settings directory identity changed.");
        }
        if (Files.exists(storage, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(storage)) {
            throw new IOException("Model settings path must be a regular file.");
        }
    }

    enum Provider {
        OPENAI("openai", "OPENAI_API_KEY"), OPENROUTER("openrouter", "OPENROUTER_API_KEY"), GEMINI("gemini", "GEMINI_API_KEY");

        private final String id;
        private final String keyEnvironment;

        Provider(String id, String keyEnvironment) {
            this.id = id;
            this.keyEnvironment = keyEnvironment;
        }

        String id() { return id; }
        String keyEnvironment() { return keyEnvironment; }

        static Provider parse(String value) {
            for (Provider provider : values()) if (provider.id.equals(value)) return provider;
            throw new IllegalArgumentException("Unknown model provider.");
        }
    }

    enum OutputMode {
        JSON_SCHEMA("json_schema"), PROMPT_JSON("prompt_json");

        private final String id;

        OutputMode(String id) { this.id = id; }
        String id() { return id; }

        static OutputMode parse(String value) {
            for (OutputMode mode : values()) if (mode.id.equals(value)) return mode;
            throw new IllegalArgumentException("Unknown model output mode.");
        }
    }

    record Profile(Provider provider, String model, String keyEnvironment, int timeoutSeconds, OutputMode outputMode) {
        Profile(Provider provider, String model, String keyEnvironment, int timeoutSeconds) {
            this(provider, model, keyEnvironment, timeoutSeconds, OutputMode.JSON_SCHEMA);
        }

        Profile(String model, String keyEnvironment, int timeoutSeconds) {
            this(Provider.OPENAI, model, keyEnvironment, timeoutSeconds);
        }

        Profile {
            if (outputMode == null || outputMode == OutputMode.PROMPT_JSON && provider != Provider.OPENROUTER) {
                throw new IllegalArgumentException("Prompt JSON is available only for OpenRouter.");
            }
            if (provider == null) throw new IllegalArgumentException("Provider is required.");
            if (provider == Provider.GEMINI && model != null && !model.isEmpty()
                    && !model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("Gemini requires a bare model ID.");
            }
            if (model == null || (!model.isEmpty() && !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))) {
                throw new IllegalArgumentException("Invalid model ID.");
            }
            if (keyEnvironment == null || !keyEnvironment.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                throw new IllegalArgumentException("Invalid key environment-variable name.");
            }
            if (timeoutSeconds < 1 || timeoutSeconds > 300) {
                throw new IllegalArgumentException("Timeout must be between 1 and 300 seconds.");
            }
        }

        boolean configured() {
            return !model.isEmpty();
        }
    }
}
