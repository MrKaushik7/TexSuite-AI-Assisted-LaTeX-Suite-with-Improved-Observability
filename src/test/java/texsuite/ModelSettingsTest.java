package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModelSettingsTest {
    @TempDir Path directory;

    @org.junit.jupiter.api.BeforeEach
    void canonicalRoot() throws Exception {
        directory = directory.toRealPath();
    }

    @Test
    void outputModeIsExplicitPersistedAndNeverSilentlyDowngraded() throws Exception {
        var profile = new ModelSettings.Profile(ModelSettings.Provider.OPENROUTER,
                "nvidia/nemotron-3-ultra-550b-a55b:free", "OPENROUTER_API_KEY", 60, ModelSettings.OutputMode.PROMPT_JSON);
        new ModelSettings(directory.resolve(".tex-suite/config.json"), name -> null).save(profile);
        assertEquals(profile, new ModelSettings(directory.resolve(".tex-suite/config.json"), name -> null).load());
        Files.delete(directory.resolve(".tex-suite/config.json"));
        var schema = new ModelSettings(directory.resolve(".tex-suite/config.json"), name -> name.equals("TEXSUITE_MODEL_PROVIDER") ? "openrouter" : null).load();
        assertEquals(ModelSettings.OutputMode.JSON_SCHEMA, schema.outputMode());
        assertThrows(java.io.IOException.class, () -> new ModelSettings(directory.resolve(".tex-suite/config.json"), name -> java.util.Map.of(
                "TEXSUITE_MODEL_PROVIDER", "gemini", "TEXSUITE_GEMINI_OUTPUT_MODE", "prompt_json").get(name)).load());
    }

    @Test
    void providersUseSeparateEnvironmentDefaultsAndPreserveSavedProfiles() throws Exception {
        for (var provider : ModelSettings.Provider.values()) {
            var settings = new ModelSettings(directory.resolve(".tex-suite/config.json"), variable -> java.util.Map.of(
                    "TEXSUITE_MODEL_PROVIDER", provider.id(),
                    "TEXSUITE_" + provider.name() + "_MODEL", "test-model").get(variable));
            Files.deleteIfExists(directory.resolve(".tex-suite/config.json"));
            var profile = settings.load();
            assertEquals(provider, profile.provider());
            assertEquals(provider.keyEnvironment(), profile.keyEnvironment());
            assertEquals("test-model", profile.model());
        }
        var settings = new ModelSettings(directory.resolve(".tex-suite/config.json"), variable -> null);
        for (var provider : ModelSettings.Provider.values()) {
            var profile = new ModelSettings.Profile(provider, "test-model", provider.keyEnvironment(), 60);
            settings.save(profile);
            assertEquals(profile, new ModelSettings(directory.resolve(".tex-suite/config.json"), variable -> null).load());
        }
        var config = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                Files.readString(directory.resolve(".tex-suite/config.json")));
        for (var provider : ModelSettings.Provider.values()) assertEquals("test-model", config.path(provider.id()).path("model").asText());
        Files.delete(directory.resolve(".tex-suite/config.json"));
        assertThrows(java.io.IOException.class, () -> new ModelSettings(directory.resolve(".tex-suite/config.json"),
                name -> name.equals("TEXSUITE_MODEL_PROVIDER") ? "unknown" : null).load());
    }

    @Test
    void requiresExplicitModelAndDefaultsOnlyKeyReferenceAndTimeout() throws Exception {
        var profile = new ModelSettings(directory.resolve(".tex-suite/config.json"), variable -> null).load();
        assertFalse(profile.configured());
        assertEquals("OPENAI_API_KEY", profile.keyEnvironment());
        assertEquals(60, profile.timeoutSeconds());
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void savedProfileOverridesEnvironmentAndSavePreservesOtherSettings() throws Exception {
        var external = Map.of("TEXSUITE_OPENAI_MODEL", "environment-model", "TEXSUITE_OPENAI_TIMEOUT", "20");
        var settings = new ModelSettings(directory.resolve(".tex-suite/config.json"), external::get);
        assertEquals("environment-model", settings.load().model());
        Files.createDirectories(directory.resolve(".tex-suite"));
        Path file = directory.resolve(".tex-suite/config.json");
        Files.writeString(file, "{\"editor\":{\"keep\":true},\"openai\":{\"model\":\"project-model\"}}");
        assertEquals("project-model", settings.load().model());
        assertEquals(20, settings.load().timeoutSeconds());
        var profile = new ModelSettings.Profile("gpt-6.1-sol", "CUSTOM_KEY", 35);
        settings.save(profile);
        assertEquals(profile, new ModelSettings(directory.resolve(".tex-suite/config.json"), external::get).load());
        String persisted = Files.readString(file);
        assertTrue(persisted.contains("editor"));
        assertTrue(persisted.contains("CUSTOM_KEY"));
        assertFalse(persisted.contains("environment-model"));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void invalidAndOversizedConfigurationsFailWithoutQuotingTheirContents() throws Exception {
        Files.createDirectory(directory.resolve(".tex-suite"));
        Path file = directory.resolve(".tex-suite/config.json");
        for (String value : new String[] {"private-secret", "[]", "{\"openai\":null}",
                "{\"openai\":{\"model\":\"private-secret\\n\"}}", "{\"openai\":{\"timeoutSeconds\":0}}",
                "{\"openai\":{\"model\":true}}", "{\"openai\":{},\"openai\":{}}", "{} {}", "x".repeat(16385)}) {
            Files.writeString(file, value);
            var failure = assertThrows(IOException.class, () -> new ModelSettings(directory.resolve(".tex-suite/config.json"), variable -> null).load());
            assertFalse(failure.getMessage().contains("private-secret"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void refusesSymlinkStorageWithoutWritingOrUsingAnUnsavedProfile() throws Exception {
        Path elsewhere = Files.createDirectory(directory.resolve("other"));
        Files.createSymbolicLink(directory.resolve(".tex-suite"), elsewhere);
        var settings = new ModelSettings(directory.resolve(".tex-suite/config.json"), variable -> null);
        var profile = new ModelSettings.Profile("gpt-6.1-sol", "OPENAI_API_KEY", 60);
        assertThrows(IOException.class, () -> settings.save(profile));
        assertThrows(IOException.class, settings::load);
        try (var paths = Files.list(elsewhere)) { assertEquals(0, paths.count()); }
    }

    @Test
    void rejectsSecretValuesControlCharactersAndOutOfRangeTimeoutsAsProfileFields() {
        for (String value : new String[] {"Bearer secret", "sk-secret!", "OPENAI_API_KEY\n", ""}) {
            assertThrows(IllegalArgumentException.class, () -> new ModelSettings.Profile("model", value, 60));
        }
        assertThrows(IllegalArgumentException.class, () -> new ModelSettings.Profile("model\n", "KEY", 60));
        assertThrows(IllegalArgumentException.class, () -> new ModelSettings.Profile("model", "KEY", 301));
    }
}
