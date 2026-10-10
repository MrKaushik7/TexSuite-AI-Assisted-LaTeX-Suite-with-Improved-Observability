package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SessionUxTest {
    @TempDir Path directory;

    @Test
    void emptyRenameHasThreeUsefulLinesAndDebugRetainsReasons() throws Exception {
        Path source = Files.writeString(directory.resolve("tradeoff.tex"), "\\Pr_h ".repeat(12)).toRealPath();
        String original = Files.readString(source);
        for (boolean debug : List.of(false, true)) {
            var output = new StringWriter();
            var errors = new StringWriter();
            int code = new MathematicalRenameWorkflow(reader(""), new PrintWriter(output), new PrintWriter(errors),
                    debug, gate()).run(new RenameRequest(source, "Pr_h", "Probability with respect to h", "H", RenameRequest.Scope.FILE));
            assertEquals(0, code, errors.toString());
            if (debug) {
                assertTrue(output.toString().contains("EXCLUDED:"));
                assertTrue(output.toString().contains("REASON:"));
                assertTrue(output.toString().contains("bytes["));
            } else {
                assertEquals(3, output.toString().lines().count());
                assertTrue(output.toString().contains("0 candidate(s), 0 review, 12 excluded"));
                assertTrue(output.toString().contains("No eligible math matches."));
                assertFalse(output.toString().contains("Candidates are lexical"));
                assertFalse(output.toString().contains("Matched source"));
                assertFalse(output.toString().contains("root:"));
            }
            assertEquals(original, Files.readString(source));
        }
    }

    @Test
    void backFromEveryRenameAndTextInputAllowsAnotherMenuChoice() throws Exception {
        Path source = source();
        ModelSettings settings = settings();
        settings.save(disabled());
        for (String prefix : List.of("", "n\n", "n\nlength\n", "n\nlength\nm\n")) {
            Result result = session(source, "1\n" + prefix + "back\n2\nback\n6\n", settings, noClient());
            assertEquals(0, result.code(), result.errors());
            assertEquals(3, result.output().split("What would you like to do", -1).length - 1);
            assertTrue(result.output().contains("Find literal text:"));
        }
        for (String prefix : List.of("", "hello\n", "hello\nworld\n", "hello\nworld\n1\n",
                "hello\nworld\n1\n3\n", "hello\nworld\n1\n3\n1\n")) {
            Result result = session(source, "2\n" + prefix + "back\n1\nback\n6\n", settings, noClient());
            assertEquals(0, result.code(), result.errors());
            assertTrue(result.output().contains("Source symbol or literal LaTeX string:"));
        }
        assertEquals("\\begin{document}hello $n$\\end{document}", Files.readString(source));
        assertFalse(Files.exists(source.getParent().resolve(".tex-suite")));
    }

    @Test
    void backFromManualApprovalRemoteConsentAndBatchNeverWritesOrEndsSession() throws Exception {
        Path source = source();
        ModelSettings settings = settings();
        for (String answers : List.of("back\n", "y\nback\n")) {
            settings.save(disabled());
            Result result = session(source, "1\nn\nlength\nm\n\n" + answers + "6\n", settings, noClient());
            assertEquals(0, result.code(), result.errors());
            assertTrue(result.output().contains("Back to menu; no source changes."));
        }
        settings.save(new ModelSettings.Profile("fixture-model", "TEST_KEY", 60));
        int[] calls = {0};
        for (String answers : List.of("back\n", "y\nback\n")) {
            Result result = session(source, "1\nn\nlength\nm\n\n1\n" + answers + "6\n", settings,
                    profile -> fixture(profile, calls));
            assertEquals(0, result.code(), result.errors());
            assertTrue(result.output().contains("Back to menu; no source changes."));
        }
        assertEquals(1, calls[0]); // only the explicitly consented fixture classification
        assertFalse(Files.exists(source.getParent().resolve(".tex-suite")));
        assertEquals("\\begin{document}hello $n$\\end{document}", Files.readString(source));
    }

    @Test
    void startupSetupIsGlobalAndReusedAcrossNewSessionsAndProjectsWithoutACall() throws Exception {
        Path first = source();
        ModelSettings settings = settings();
        Result configured = session(first, "yes\n3\ngemini-2.5-flash\n\n100\n6\n", settings, noClient());
        assertEquals(0, configured.code(), configured.errors());
        assertTrue(configured.output().contains("Change them anytime in Settings 8"));
        var expected = new ModelSettings.Profile(ModelSettings.Provider.GEMINI, "gemini-2.5-flash", "GEMINI_API_KEY", 100);
        assertEquals(expected, new ModelSettings(directory.toRealPath().resolve("user/ai.json"), name -> null).load());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("user/ai.json"))));
        Path second = Files.createDirectories(directory.resolve("other-project")).toRealPath().resolve("main.tex");
        Files.writeString(second, "$n$");
        Files.createDirectories(second.getParent().resolve(".tex-suite"));
        Path legacy = Files.writeString(second.getParent().resolve(".tex-suite/config.json"),
                "{\"openai\":{\"model\":\"legacy-project-model\"}}");
        var profiles = new ArrayList<ModelSettings.Profile>();
        Result reused = session(second, "1\nn\nlength\nm\n\n2\nn\n6\n",
                new ModelSettings(directory.toRealPath().resolve("user/ai.json"), name -> null), profile -> {
                    profiles.add(profile);
                    return fixture(profile, new int[1]);
                });
        assertEquals(0, reused.code(), reused.errors());
        assertEquals(List.of(expected), profiles);
        assertFalse(reused.output().contains("Configure AI now?"));
        assertFalse(reused.output().contains("AI is unconfigured"));
        assertTrue(Files.readString(legacy).contains("legacy-project-model"));
        assertEquals("$n$", Files.readString(second));
    }

    @Test
    void startupDeclineAndBackKeepManualModeWhileSavedDisableSkipsTheOffer() throws Exception {
        Path source = source();
        ModelSettings settings = settings();
        for (String choice : List.of("n", "", "back")) {
            Result result = session(source, choice + "\n1\nback\n6\n", settings, noClient());
            assertEquals(0, result.code(), result.errors());
            assertTrue(result.output().contains("Configure AI now?"));
            assertFalse(settings.saved());
        }
        Result disabled = session(source, "y\n1\n\n\n\n6\n", settings, noClient());
        assertEquals(0, disabled.code(), disabled.errors());
        assertTrue(settings.saved());
        assertFalse(settings.load().configured());
        Result later = session(source, "6\n", settings, noClient());
        assertEquals(0, later.code(), later.errors());
        assertFalse(later.output().contains("Configure AI now?"));
    }

    @Test
    void backDuringConfigurationAndProviderTestKeepsTheSavedProfileAndMakesNoCall() throws Exception {
        Path source = source();
        ModelSettings settings = settings();
        var profile = new ModelSettings.Profile("fixture-model", "TEST_KEY", 60);
        settings.save(profile);
        byte[] original = Files.readAllBytes(directory.resolve("user/ai.json"));
        for (String prefix : List.of("", "1\n", "1\nnew-model\n", "1\nnew-model\nNEW_KEY\n",
                "2\nnew-model\nNEW_KEY\n60\n")) {
            Result result = session(source, "5\n8\n1\n" + prefix + "back\n6\n", settings, noClient());
            assertEquals(0, result.code(), result.errors());
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("user/ai.json")));
        }
        int[] calls = {0};
        Result test = session(source, "5\n8\n2\nback\n6\n", settings, item -> fixture(item, calls));
        assertEquals(0, test.code(), test.errors());
        assertEquals(0, calls[0]);
        assertEquals(profile, settings.load());
    }

    @Test
    void changingSettingsSavesImmediatelyAndFailedPersistenceKeepsOriginalBytes() throws Exception {
        Path source = source();
        ModelSettings settings = settings();
        settings.save(disabled());
        Result changed = session(source, "5\n8\n1\n2\nfixture-router\nROUTER_KEY\n25\n2\n3\n6\n6\n", settings, noClient());
        assertEquals(0, changed.code(), changed.errors());
        assertEquals(new ModelSettings.Profile(ModelSettings.Provider.OPENROUTER, "fixture-router", "ROUTER_KEY", 25,
                ModelSettings.OutputMode.PROMPT_JSON), settings.load());
        Path storage = directory.resolve("user/ai.json");
        String original = "{\"modelProvider\":\"openai\",\"openai\":{\"model\":\"\"},\"padding\":\"" + "x".repeat(16200) + "\"}";
        Files.writeString(storage, original);
        Result failed = session(source, "5\n8\n1\n1\n" + "m".repeat(128) + "\n" + "K".repeat(128) + "\n300\n3\n6\n6\n", settings, noClient());
        assertEquals(0, failed.code(), failed.errors());
        assertTrue(failed.output().contains("Could not save global AI settings; profile unchanged"));
        assertEquals(original, Files.readString(storage));
        assertFalse(settings.load().configured());
    }

    @Test
    void backFromRecoveryAndRevertReturnsToMenuWithoutChangingSourceOrJournal() throws Exception {
        Path source = source();
        ModelSettings settings = settings();
        settings.save(disabled());
        var snapshot = new DocumentLoader().loadFile(source);
        int start = Files.readString(source).indexOf("$n$") + 1;
        var edit = new TextEditPlan.Edit(snapshot.main(), start, start + 1,
                new byte[] {'n'}, 1, start + 1);
        Path operation = new TextEditPlan(snapshot, List.of(edit), "m", gate()).apply();
        byte[] journal = Files.readAllBytes(operation.resolve("recovery.properties"));
        Result revert = session(source, "7\nback\n1\nback\n6\n", settings, noClient());
        assertEquals(0, revert.code(), revert.errors());
        assertTrue(revert.output().contains("Source symbol or literal LaTeX string:"));
        assertEquals("", revert.errors());
        assertArrayEquals(journal, Files.readAllBytes(operation.resolve("recovery.properties")));
        assertTrue(Files.readString(source).contains("$m$"));

        var current = new DocumentLoader().loadFile(source);
        var pending = new TextEditPlan(current, List.of(new TextEditPlan.Edit(current.main(),
                start, start + 1, new byte[] {'m'}, 1, start + 1)), "n", gate(),
                (staged, target) -> { throw new Error("simulated interruption before move"); });
        assertThrows(Error.class, pending::apply);
        Path recovery = TextRecovery.pending(source.getParent()).getFirst();
        byte[] interrupted = Files.readAllBytes(recovery.resolve("recovery.properties"));
        Result restored = session(source, "5\n7\nback\n2\nback\n6\n", settings, noClient());
        assertEquals(0, restored.code(), restored.errors());
        assertTrue(restored.output().contains("Find literal text:"));
        assertEquals("", restored.errors());
        assertArrayEquals(interrupted, Files.readAllBytes(recovery.resolve("recovery.properties")));
        assertTrue(Files.readString(source).contains("$m$"));
    }

    private Path source() throws Exception {
        Path project = Files.createDirectories(directory.resolve("project"));
        return Files.writeString(project.resolve("main.tex"), "\\begin{document}hello $n$\\end{document}").toRealPath();
    }

    private ModelSettings settings() throws Exception {
        return new ModelSettings(directory.toRealPath().resolve("user/ai.json"), name -> null);
    }

    private static ModelSettings.Profile disabled() {
        return new ModelSettings.Profile("", "OPENAI_API_KEY", 60);
    }

    private static Function<ModelSettings.Profile, ModelClient> noClient() {
        return profile -> { fail("Setup/cancellation must not construct a provider client"); return null; };
    }

    private static ModelClient fixture(ModelSettings.Profile profile, int[] calls) {
        return new ModelClient() {
            @Override public String destination() { return "http://fixture.invalid"; }
            @Override public String model() { return profile.model(); }
            @Override public String preview(Request request) { return "synthetic payload"; }
            @Override public Result classify(Request request) {
                calls[0]++;
                return new Result(request.candidates().stream().map(candidate ->
                        new Decision(candidate.id(), Action.REPLACE, "Synthetic match", 1)).toList());
            }
        };
    }

    private Result session(Path source, String answers, ModelSettings settings,
            Function<ModelSettings.Profile, ModelClient> factory) {
        var output = new StringWriter();
        var errors = new StringWriter();
        int code = new DocumentSession(reader(answers), new PrintWriter(output), new PrintWriter(errors), source,
                false, () -> 0, new EditorPreferences(directory.resolve("editor.properties")),
                (file, application) -> Optional.empty(), Optional::empty, gate(), factory, settings).run();
        return new Result(code, output.toString(), errors.toString());
    }

    private static BufferedReader reader(String answers) {
        return new BufferedReader(new StringReader(answers));
    }

    private static TexCompileGate gate() {
        return new TexCompileGate(null, true, null, null);
    }

    private record Result(int code, String output, String errors) { }
}
