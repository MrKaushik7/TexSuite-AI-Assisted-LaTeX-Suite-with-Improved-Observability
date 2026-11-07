package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

final class ModelSessionTest {
    @TempDir Path directory;

    @BeforeEach
    void resolveTemporaryDirectory() throws IOException {
        directory = directory.toRealPath();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void initialSetupAcceptsProviderDefaultsAndPersistsWithoutACall(int choice) throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "text").toRealPath();
        var settings = new ModelSettings(directory.resolve("ai.json"), name -> null);
        var output = new StringWriter();
        String expectedModel = switch (choice) {
            case 1 -> "gpt-6-luna";
            case 2 -> "nvidia/nemotron-3-ultra-550b-a55b:free";
            default -> "gemini-2.5-flash";
        };

        assertEquals(0, session(source, "y\n" + choice + "\n\n\n\n" + (choice == 2 ? "\n" : "") + "quit\n",
                output, new StringWriter(), profile -> { throw new AssertionError("Setup must not create a provider"); }, settings));

        var profile = new ModelSettings(directory.resolve("ai.json"), name -> null).load();
        assertEquals(expectedModel, profile.model());
        assertEquals(choice == 1 ? ModelSettings.Provider.OPENAI : choice == 2
                ? ModelSettings.Provider.OPENROUTER : ModelSettings.Provider.GEMINI, profile.provider());
        assertEquals(profile.provider().keyEnvironment(), profile.keyEnvironment());
        assertEquals(60, profile.timeoutSeconds());
        assertEquals(choice == 2 ? ModelSettings.OutputMode.PROMPT_JSON : ModelSettings.OutputMode.JSON_SCHEMA,
                profile.outputMode());
        assertTrue(output.toString().contains(expectedModel + "; Enter=default, off=disable AI"));
        var reused = new StringWriter();
        assertEquals(0, session(source, "quit\n", reused, new StringWriter(),
                ignored -> { throw new AssertionError("No startup provider"); }, settings));
        assertFalse(reused.toString().contains("Configure AI now?"));
        assertEquals(profile, settings.load());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "secret with spaces", "secret\nvalue", "secret-é", "denied", "secret-valid"})
    void startupChecksLocalKeyWithoutCreatingProviderOrLeakingValues(String key) throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "private document").toRealPath();
        var settings = new ModelSettings(directory.resolve("ai.json"), name -> {
            if (!name.equals("CUSTOM_KEY")) return null;
            if ("denied".equals(key)) throw new SecurityException("secret-denied-details");
            return key;
        });
        settings.save(new ModelSettings.Profile(ModelSettings.Provider.OPENROUTER, "fixture-model", "CUSTOM_KEY", 60));
        var output = new StringWriter();
        var errors = new StringWriter();

        assertEquals(0, session(source, "5\nquit\n", output, errors,
                profile -> { throw new AssertionError("Startup must not create a provider"); }, settings));

        String transcript = output.toString();
        if ("secret-valid".equals(key)) {
            assertTrue(transcript.contains("CUSTOM_KEY is readable in this session"));
            assertTrue(transcript.contains("Validity/expiration has not been checked"));
            assertTrue(transcript.contains("updated export"));
            assertFalse(transcript.contains("export CUSTOM_KEY="));
        } else {
            assertTrue(transcript.contains("CUSTOM_KEY is missing, unreadable, or has invalid characters"));
            assertTrue(transcript.contains("type quit"));
            assertTrue(transcript.contains("export CUSTOM_KEY='your-api-key'"));
            assertTrue(transcript.contains("relaunch TexSuite from that same terminal"));
        }
        assertTrue(transcript.indexOf("API key:") < transcript.indexOf("What would you like to do?"));
        assertTrue(transcript.contains("startup sends no API request"));
        assertFalse((transcript + errors + Files.readString(directory.resolve("ai.json"))).contains("secret"));
        assertEquals("private document", Files.readString(source));
    }

    @Test
    void nemotronPresetStillAllowsExplicitOutputOverrideInSettings() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "text").toRealPath();
        var settings = new ModelSettings(directory.resolve("ai.json"), name -> null);
        settings.save(new ModelSettings.Profile("", "OPENAI_API_KEY", 60));

        assertEquals(0, session(source, "5\n8\n1\n2\n\n\n\n1\n3\n6\nquit\n", new StringWriter(),
                new StringWriter(), profile -> { throw new AssertionError("No provider without consent"); }, settings));
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b:free", settings.load().model());
        assertEquals(ModelSettings.OutputMode.JSON_SCHEMA, settings.load().outputMode());
    }

    @Test
    void startupChecksProfileCreatedDuringInitialSetup() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "text").toRealPath();
        var output = new StringWriter();
        var settings = new ModelSettings(directory.resolve("ai.json"), name -> null);

        assertEquals(0, session(source, "y\n2\nfixture-model\nCUSTOM_KEY\n\n1\nquit\n", output,
                new StringWriter(), profile -> { throw new AssertionError("No startup provider"); }, settings));

        assertTrue(output.toString().contains("export CUSTOM_KEY='your-api-key'"));
        assertTrue(output.toString().indexOf("AI settings saved globally") < output.toString().indexOf("API key:"));
    }

    @Test
    void disabledAiDoesNotReadOrWarnAboutKey() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "text").toRealPath();
        var output = new StringWriter();
        var settings = new ModelSettings(directory.resolve("ai.json"), name -> {
            if (name.equals("CUSTOM_KEY")) fail("Disabled AI must not read credentials");
            return null;
        });
        settings.save(new ModelSettings.Profile("", "CUSTOM_KEY", 60));

        assertEquals(0, session(source, "5\nquit\n", output, new StringWriter(),
                profile -> { throw new AssertionError("No startup provider"); }, settings));
        assertFalse(output.toString().contains("API key:"));
    }

    @Test
    void globalSettingsSelectBothNewProvidersWithTheirDefaultKeyReferences() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "private document").toRealPath();
        for (int choice : java.util.List.of(2, 3)) {
            var output = new StringWriter();
            var errors = new StringWriter();
            var provider = choice == 2 ? ModelSettings.Provider.OPENROUTER : ModelSettings.Provider.GEMINI;
            int code = session(source, "5\n8\n1\n" + choice + "\ntest-model\n\n\n" + (choice == 2 ? "2\n" : "") + "2\nn\n3\n6\n6\n",
                    output, errors, profile -> {
                        assertEquals(provider, profile.provider());
                        assertEquals(provider.keyEnvironment(), profile.keyEnvironment());
                        assertEquals(choice == 2 ? ModelSettings.OutputMode.PROMPT_JSON : ModelSettings.OutputMode.JSON_SCHEMA, profile.outputMode());
                        return new ModelClient() {
                            @Override public String destination() { return "http://fixture.invalid"; }
                            @Override public String model() { return profile.model(); }
                            @Override public String preview(Request request) { return "synthetic preview"; }
                            @Override public Result classify(Request request) { fail("Consent denied"); return null; }
                        };
                    });
            assertEquals(0, code, errors.toString());
            assertTrue(output.toString().contains("AI: " + provider.id()));
        }
    }

    @Test
    void settingsConfigureAndPersistOnlyProfileThenTestWithSeparateConsent() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "private document").toRealPath();
        var output = new StringWriter();
        var errors = new StringWriter();
        int[] calls = {0};
        var profiles = new java.util.ArrayList<ModelSettings.Profile>();
        int code = session(source, "5\n8\n1\n1\ngpt-6.1-sol\nCUSTOM_KEY\n25\n2\nn\n2\ny\n3\n6\n6\n",
                output, errors, profile -> {
                    profiles.add(profile);
                    return new ModelClient() {
                        @Override public String destination() { return "http://fixture.invalid"; }
                        @Override public String model() { return profile.model(); }
                        @Override public String preview(Request request) { return "synthetic preview"; }
                        @Override public Result classify(Request request) {
                            calls[0]++;
                            assertEquals("x", request.excerpts().getFirst().text());
                            return new Result(List.of(new Decision(request.candidates().getFirst().id(),
                                    Action.KEEP, "Synthetic test", 1)));
                        }
                    };
                });
        assertEquals(0, code, errors.toString());
        assertEquals(1, calls[0]);
        assertEquals(List.of(new ModelSettings.Profile("gpt-6.1-sol", "CUSTOM_KEY", 25),
                new ModelSettings.Profile("gpt-6.1-sol", "CUSTOM_KEY", 25)), profiles);
        assertTrue(output.toString().contains("Provider test passed"));
        assertFalse(output.toString().contains("private document"));
        assertEquals("private document", Files.readString(source));
        String config = Files.readString(directory.resolve("ai.json"));
        assertTrue(config.contains("CUSTOM_KEY"));
        assertFalse(config.contains("private document"));
    }

    @Test
    void quitDuringProviderConsentEndsSessionWithoutSending() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "private document").toRealPath();
        int code = session(source, "5\n8\n1\n1\ngpt-6.1-sol\n\n\n2\nquit\n",
                new StringWriter(), new StringWriter(), profile -> new ModelClient() {
                    @Override public String destination() { return "http://fixture.invalid"; }
                    @Override public String model() { return profile.model(); }
                    @Override public String preview(Request request) { return "synthetic preview"; }
                    @Override public Result classify(Request request) { fail("No consent"); return null; }
                });
        assertEquals(0, code);
        assertTrue(Files.exists(directory.resolve("ai.json")));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    private int session(Path source, String answers, StringWriter output, StringWriter errors,
            java.util.function.Function<ModelSettings.Profile, ModelClient> factory) throws IOException {
        var settings = new ModelSettings(directory.resolve("ai.json"), name -> null);
        settings.save(new ModelSettings.Profile("", "OPENAI_API_KEY", 60));
        return session(source, answers, output, errors, factory, settings);
    }

    private int session(Path source, String answers, StringWriter output, StringWriter errors,
            java.util.function.Function<ModelSettings.Profile, ModelClient> factory, ModelSettings settings) {
        return new DocumentSession(new BufferedReader(new StringReader(answers)), new PrintWriter(output),
                new PrintWriter(errors), source, false, () -> 0,
                new EditorPreferences(directory.resolve("editor.properties")), (file, app) -> Optional.empty(),
                Optional::empty, new TexCompileGate(null, true, null, null), factory, settings).run();
    }
}
