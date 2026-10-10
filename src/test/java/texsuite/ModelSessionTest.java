package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModelSessionTest {
    @TempDir Path directory;

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
        directory = directory.toRealPath();
        var settings = new ModelSettings(directory.resolve("ai.json"), name -> null);
        settings.save(new ModelSettings.Profile("", "OPENAI_API_KEY", 60));
        return new DocumentSession(new BufferedReader(new StringReader(answers)), new PrintWriter(output),
                new PrintWriter(errors), source, false, () -> 0,
                new EditorPreferences(directory.resolve("editor.properties")), (file, app) -> Optional.empty(),
                Optional::empty, new TexCompileGate(null, true, null, null), factory, settings).run();
    }
}
