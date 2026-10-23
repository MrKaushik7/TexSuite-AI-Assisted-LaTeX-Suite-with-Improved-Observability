package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModelCacheTest {
    @TempDir Path directory;

    @Test
    void repeatAcrossWorkflowsUsesZeroCallsAndStillNeedsApplyApproval() throws Exception {
        Path source = source();
        var first = new Fixture();
        assertEquals(0, run(source, "1\ny\ncancel\n", first).code());
        assertEquals(1, first.calls);
        assertEquals("$n$", Files.readString(source));
        assertFalse(Files.exists(root().resolve(".tex-suite/backups")));

        var second = new Fixture();
        var cancelled = run(source, "1\ncancel\n", second);
        assertEquals(0, cancelled.code());
        assertTrue(cancelled.output().contains("AI cache hit"));
        assertFalse(cancelled.output().contains("Send this request?"));
        assertEquals(0, second.calls);
        assertEquals("$n$", Files.readString(source));
        assertFalse(Files.exists(root().resolve(".tex-suite/backups")));

        assertEquals(0, run(source, "1\napply\n", second).code());
        assertEquals(0, second.calls);
        assertEquals("$m$", Files.readString(source));
        assertEquals("complete", EditHistory.list(root()).getFirst().status());
    }

    @Test
    void disableRegenerateAndClearControlRealCallCounts() throws Exception {
        Path source = source();
        var cache = new ModelCache(root());
        var client = new Fixture();
        run(source, "1\ny\ncancel\n", client);
        cache.setEnabled(false);
        assertFalse(new ModelCache(root()).enabled());
        run(source, "1\ny\ncancel\n", client);
        assertEquals(2, client.calls);
        assertEquals(1, cache.stats().entries());

        cache.setEnabled(true);
        run(source, "1\ncancel\n", client);
        assertEquals(2, client.calls);
        client.action = ModelClient.Action.KEEP;
        var regenerated = run(source, "3\ny\ncancel\n", client);
        assertTrue(regenerated.output().contains("bypassing cached decisions"));
        assertEquals(3, client.calls);
        assertTrue(run(source, "1\ncancel\n", client).output().contains("selected: 0; kept: 1"));
        assertEquals(3, client.calls);

        assertEquals(1, cache.clear());
        run(source, "1\ny\ncancel\n", client);
        assertEquals(4, client.calls);
        assertEquals("$n$", Files.readString(source));
    }

    @Test
    void deniedRegenerationDoesNotCallOrReplaceExistingEntry() throws Exception {
        Path source = source();
        var client = new Fixture();
        run(source, "1\ny\ncancel\n", client);
        run(source, "3\nn\nn\n", client);
        assertEquals(1, client.calls);
        assertTrue(run(source, "1\ncancel\n", client).output().contains("AI cache hit"));
        assertEquals(1, client.calls);
    }

    @Test
    void repairedResultsUseOriginalKeyButFailuresAreNotStored() throws Exception {
        Path source = source();
        var client = new Fixture();
        client.repair = true;
        assertEquals(0, run(source, "1\ny\ny\ny\ncancel\n", client).code());
        assertEquals(2, client.calls);
        assertTrue(run(source, "1\ncancel\n", new Fixture()).output().contains("AI cache hit"));

        new ModelCache(root()).clear();
        client.fail = true;
        run(source, "1\ny\nn\n", client);
        assertEquals(0, new ModelCache(root()).stats().entries());
    }

    @Test
    void cacheWriteFailureKeepsResultWithoutRepeatingCall() throws Exception {
        Path source = source();
        var client = new Fixture();
        client.afterCall = () -> Files.writeString(root().resolve(".tex-suite"), "blocked cache");
        var result = run(source, "1\ny\ncancel\n", client);
        assertEquals(0, result.code(), result.errors());
        assertEquals(1, client.calls);
        assertTrue(result.output().contains("could not be cached"));
        assertTrue(result.output().contains("selected: 1"));
        assertEquals("$n$", Files.readString(source));
    }

    @Test
    void corruptForeignOversizedAndVersionedRecordsBecomeMisses() throws Exception {
        Path source = source();
        var client = new Fixture();
        run(source, "1\ny\ncancel\n", client);
        Path entry = cachedFile();
        String valid = Files.readString(entry);
        for (String invalid : List.of("{", valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"id\":\"", "\"id\":\"foreign"),
                valid.replace("\"confidence\":0.9", "\"confidence\":2.0"),
                valid + " ".repeat(ModelCache.MAX_ENTRY_BYTES), valid + "{}")) {
            Files.writeString(entry, invalid);
            int before = client.calls;
            var result = run(source, "1\ny\ncancel\n", client);
            assertEquals(0, result.code(), result.errors());
            assertEquals(before + 1, client.calls);
            assertFalse(result.output().contains("AI cache hit"));
        }
        assertEquals("$n$", Files.readString(source));
    }

    @Test
    void changesToSourceMeaningAndReplacementMiss() throws Exception {
        Path source = source();
        var client = new Fixture();
        run(source, "1\ny\ncancel\n", client);
        run(source, "1\ny\ncancel\n", client, "other meaning", "m");
        run(source, "1\ny\ncancel\n", client, "length", " m ");
        Files.writeString(source, "$n+n$");
        run(source, "1\ny\ncancel\n", client);
        assertEquals(4, client.calls);
    }

    @Test
    void staleSourceDuringHitCannotReuseApprovalOrCallProvider() throws Exception {
        Path source = source();
        run(source, "1\ny\ncancel\n", new Fixture());
        var client = new Fixture();
        client.beforePreview = () -> Files.writeString(source, "$n$ external change");
        var result = run(source, "1\n", client);
        assertEquals(0, result.code(), result.errors());
        assertEquals(0, client.calls);
        assertFalse(result.output().contains("AI cache hit"));
        assertEquals("$n$ external change", Files.readString(source));
        assertFalse(Files.exists(root().resolve(".tex-suite/backups")));
    }

    @Test
    void cachedDecisionsStillGoThroughCompilationBeforeWriting() throws Exception {
        Path source = Files.writeString(root().resolve("main.tex"),
                "\\documentclass{article}\n\\begin{document}$n$\\end{document}");
        String original = Files.readString(source);
        run(source, "1\ny\ncancel\n", new Fixture());
        var client = new Fixture();
        int[] compiles = {0};
        var output = new StringWriter();
        var errors = new StringWriter();
        int code = new MathematicalRenameWorkflow(reader("1\napply\n"), new PrintWriter(output),
                new PrintWriter(errors), false, new TexCompileGate(Path.of("/fake/pdflatex"), false, source,
                        (command, cwd, timeout) -> { compiles[0]++; return 1; }), client)
                .run(new RenameRequest(source, "n", "length", "m", RenameRequest.Scope.FILE));
        assertNotEquals(0, code);
        assertTrue(output.toString().contains("AI cache hit"));
        assertEquals(1, compiles[0]);
        assertEquals(0, client.calls);
        assertEquals(original, Files.readString(source));
        assertFalse(Files.exists(root().resolve(".tex-suite/backups")));
    }

    @Test
    void laterBatchFailureDoesNotApplyPartialCachedResults() throws Exception {
        Path source = Files.writeString(root().resolve("main.tex"), "$n+n+n+n+n+n+n+n+n$");
        String original = Files.readString(source);
        var first = new Fixture();
        first.failAt = 2;
        var failed = run(source, "1\ny\ny\nn\n", first);
        assertEquals(2, first.calls);
        assertTrue(failed.output().contains("All AI decisions for this operation were discarded"));
        assertEquals(original, Files.readString(source));
        assertEquals(1, new ModelCache(root()).stats().entries());

        var retry = new Fixture();
        var recovered = run(source, "1\ny\ncancel\n", retry);
        assertEquals(1, retry.calls);
        assertTrue(recovered.output().contains("AI cache hit"));
        assertTrue(recovered.output().contains("selected: 9"));
        assertEquals(original, Files.readString(source));
        assertFalse(Files.exists(root().resolve(".tex-suite/backups")));
    }

    @Test
    void textReplacementUsesCacheButManualModeDoesNot() throws Exception {
        Path source = Files.writeString(root().resolve("main.tex"), "hello world");
        var client = new Fixture();
        for (String answers : List.of("hello\nhi\n\n\n\ngreeting\n1\ny\ncancel\n",
                "hello\nhi\n\n\n\ngreeting\n1\ncancel\n")) {
            var output = new StringWriter();
            var errors = new StringWriter();
            int code = new TextReplacementWorkflow(reader(answers), new PrintWriter(output), new PrintWriter(errors),
                    source, false, new TexCompileGate(null, true, null, null), client).run();
            assertEquals(0, code, errors.toString());
            if (!answers.contains("\ny\n")) assertTrue(output.toString().contains("AI cache hit"), output.toString());
        }
        assertEquals(1, client.calls);
        assertEquals("hello world", Files.readString(source));
        var output = new StringWriter();
        new TextReplacementWorkflow(reader("hello\nhi\n\n\n\ngreeting\n2\nn\n"), new PrintWriter(output),
                new PrintWriter(new StringWriter()), source, false, new TexCompileGate(null, true, null, null), client).run();
        assertFalse(output.toString().contains("AI cache hit"));
        assertEquals(1, client.calls);
    }

    @Test
    void matchingModesCannotShareCacheEvenWhenCandidatesAreIdentical() throws Exception {
        Path source = Files.writeString(root().resolve("main.tex"), "hi hi.");
        var client = new Fixture();
        String[] modes = {"", "2", "1", "2"};
        for (int index = 0; index < modes.length; index++) {
            boolean hit = index >= 2;
            var output = new StringWriter();
            var errors = new StringWriter();
            String answers = "hi\nhello\n\n\n" + modes[index] + "\ngreeting\n1\n"
                    + (hit ? "" : "y\n") + "cancel\n";
            int code = new TextReplacementWorkflow(reader(answers), new PrintWriter(output), new PrintWriter(errors),
                    source, false, new TexCompileGate(null, true, null, null), client).run();
            assertEquals(0, code, errors.toString());
            assertEquals(hit, output.toString().contains("AI cache hit"), output.toString());
            assertEquals(Math.min(index + 1, 2), client.calls);
        }
        assertEquals("hi hi.", Files.readString(source));
        assertEquals(2, new ModelCache(root()).stats().entries());
    }

    @Test
    void cachedReplaceAllCannotSelectEmbeddedWordsOrProtectedText() throws Exception {
        String original = "\\begin{document}\nThe greeting is hi today.\nWe say hi again.\n"
                + "This sentence ends with hi.\nthis high \\cite{hi}\n% hi\n\\end{document}";
        Path source = Files.writeString(root().resolve("main.tex"), original);
        var client = new Fixture();
        for (boolean hit : List.of(false, true)) {
            var output = new StringWriter();
            var errors = new StringWriter();
            int code = new TextReplacementWorkflow(reader("hi\nhello\n\n\n\ngreeting\n1\n"
                    + (hit ? "apply\n" : "y\ncancel\n")), new PrintWriter(output), new PrintWriter(errors),
                    source, false, new TexCompileGate(null, true, null, null), client).run();
            assertEquals(0, code, errors.toString());
            assertEquals(hit, output.toString().contains("AI cache hit"), output.toString());
            assertEquals(1, client.calls);
            assertEquals(3, client.lastRequest.candidates().size());
            assertTrue(output.toString().contains("selected: 3"), output.toString());
            if (!hit) assertEquals(original, Files.readString(source));
        }
        assertEquals(original.replace("hi today", "hello today").replace("hi again", "hello again")
                .replace("with hi.", "with hello."), Files.readString(source));
        assertEquals(3, EditHistory.list(root()).getFirst().record().edits().size());
    }

    @Test
    void projectIsolationPrivatePermissionsAndBoundedStorage() throws Exception {
        var cache = new ModelCache(root());
        var request = request("hi");
        String key = "a".repeat(64);
        cache.store(key, request, result(request));
        Path other = Files.createDirectory(root().resolve("other"));
        assertNull(new ModelCache(other).read(key, request));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root().resolve(".tex-suite/cache"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cachedFile())));
        assertFalse(Files.readString(cachedFile()).contains("private source text"));
        for (int i = 1; i < ModelCache.MAX_ENTRIES; i++) cache.store(String.format("%064x", i), request, result(request));
        assertEquals(ModelCache.MAX_ENTRIES, cache.stats().entries());
        long storedBytes;
        try (var paths = Files.list(root().resolve(".tex-suite/cache"))) {
            storedBytes = paths.mapToLong(path -> {
                try { return Files.size(path); } catch (IOException exception) { throw new UncheckedIOException(exception); }
            }).sum();
        }
        assertEquals(storedBytes, cache.stats().bytes());
        assertThrows(IOException.class, () -> cache.store("b".repeat(64), request, result(request)));
        cache.store(key, request, result(request)); // replacing an existing key needs no extra slot
        cache.setEnabled(false);
        Path unrelated = Files.writeString(root().resolve(".tex-suite/cache/keep.txt"), "keep");
        assertEquals(ModelCache.MAX_ENTRIES, cache.clear());
        assertFalse(cache.enabled());
        assertEquals("keep", Files.readString(unrelated));
    }

    @Test
    void linksInvalidKeysAndMalformedSettingsAreRejected() throws Exception {
        var cache = new ModelCache(root());
        var request = request("hi");
        String key = "a".repeat(64);
        Path outside = Files.createDirectory(root().resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "untouched");
        Path metadata = root().resolve(".tex-suite");
        Files.createSymbolicLink(metadata, outside);
        assertThrows(IOException.class, cache::enabled);
        assertThrows(IOException.class, () -> cache.read(key, request));
        assertThrows(IOException.class, () -> cache.store(key, request, result(request)));
        Files.delete(metadata);
        Files.createDirectory(metadata);
        Files.createSymbolicLink(metadata.resolve("cache"), outside);
        assertThrows(IOException.class, cache::stats);
        assertThrows(IOException.class, () -> cache.read(key, request));
        Files.delete(metadata.resolve("cache"));
        Files.createDirectory(metadata.resolve("cache"));
        Files.createSymbolicLink(metadata.resolve("cache/" + key + ".json"), sentinel);
        assertThrows(IOException.class, () -> cache.read(key, request));
        assertThrows(IOException.class, cache::stats);
        assertThrows(IOException.class, cache::clear);
        assertThrows(IOException.class, () -> cache.store(key, request, result(request)));
        assertThrows(IOException.class, () -> cache.read("../escape", request));
        Files.writeString(metadata.resolve("cache/settings.json"), "{\"enabled\":true,\"enabled\":false}");
        assertThrows(IOException.class, cache::enabled);
        assertEquals("untouched", Files.readString(sentinel));
    }

    @Test
    void canonicalNestedObjectsKeepArrayOrderAndLiteralStrings() throws Exception {
        var first = ModelProtocol.JSON.readTree("{\"b\":[{\"y\":2,\"x\":1},3],\"a\":\" hi \"}");
        var reordered = ModelProtocol.JSON.readTree("{\"a\":\" hi \",\"b\":[{\"x\":1,\"y\":2},3]}");
        assertEquals(ModelCache.fingerprint(first), ModelCache.fingerprint(reordered));
        assertNotEquals(ModelCache.fingerprint(first), ModelCache.fingerprint(
                ModelProtocol.JSON.readTree("{\"a\":\" hi \",\"b\":[3,{\"x\":1,\"y\":2}]}")));
    }

    @Test
    void validateBeforePersistingAndPreserveAbstention() throws Exception {
        var cache = new ModelCache(root());
        var request = request("hi");
        String key = "a".repeat(64);
        var invalid = new ModelClient.Result(List.of(new ModelClient.Decision("foreign-id",
                ModelClient.Action.REPLACE, "Wrong occurrence", 0.9)));
        assertThrows(ModelClient.Failure.class, () -> cache.store(key, request, invalid));
        assertFalse(Files.exists(root().resolve(".tex-suite")));
        var uncertain = new ModelClient.Result(List.of(new ModelClient.Decision(request.candidates().getFirst().id(),
                ModelClient.Action.NEEDS_HUMAN_REVIEW, "Uncertain meaning", 0.2)));
        cache.store(key, request, uncertain);
        assertEquals(uncertain, cache.read(key, request));
    }

    @Test
    void exactReadLimitAndDirectoryScanLimitAreAcceptedButOneOverRejected() throws Exception {
        var cache = new ModelCache(root());
        var request = request("hi");
        String key = "a".repeat(64);
        cache.store(key, request, result(request));
        Path path = cachedFile();
        String record = Files.readString(path);
        Files.writeString(path, record + " ".repeat(ModelCache.MAX_ENTRY_BYTES - record.length()));
        assertEquals(result(request), cache.read(key, request));
        Files.writeString(path, " ", StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> cache.read(key, request));
        cache.clear();
        for (int i = 0; i < ModelCache.MAX_ENTRIES * 2; i++) {
            Files.writeString(root().resolve(".tex-suite/cache/unknown-" + i), "");
        }
        assertEquals(0, cache.stats().entries());
        Files.writeString(root().resolve(".tex-suite/cache/one-more"), "");
        assertThrows(IOException.class, cache::stats);
    }

    @Test
    void settingsControlsPersistAndClearOnlyCache() throws Exception {
        Path source = source();
        run(source, "1\ny\ncancel\n", new Fixture());
        Path history = Files.createDirectories(root().resolve(".tex-suite/backups"));
        Path keep = Files.writeString(history.resolve("keep"), "history sentinel");
        ModelSettings settings = new ModelSettings(root().resolve("user/ai.json"), name -> null);
        settings.save(new ModelSettings.Profile("", "OPENAI_API_KEY", 60));
        var output = new StringWriter();
        int status = new DocumentSession(reader("5\n10\n2\n3\nclear\n4\n6\n6\n"),
                new PrintWriter(output), new PrintWriter(new StringWriter()), source, false, () -> 0,
                new EditorPreferences(root().resolve("editor.properties")), (file, app) -> Optional.empty(),
                Optional::empty, new TexCompileGate(null, true, null, null), profile -> null, settings).run();
        assertEquals(0, status);
        assertFalse(new ModelCache(root()).enabled());
        assertEquals(0, new ModelCache(root()).stats().entries());
        assertTrue(output.toString().contains("Cleared 1 cached result"));
        assertEquals("history sentinel", Files.readString(keep));
        assertEquals("$n$", Files.readString(source));
    }

    @Test
    void allMaterialKeyFieldsMatterAndActualProviderModesDiffer() throws Exception {
        var request = request("hi");
        var batch = batch();
        var client = new Fixture();
        String key = ModelCache.key("rename", "FILE", "MATH", client, request, batch);
        assertNotEquals(key, ModelCache.key("text", "FILE", "MATH", client, request, batch));
        assertNotEquals(key, ModelCache.key("rename", "PROJECT", "MATH", client, request, batch));
        assertNotEquals(key, ModelCache.key("rename", "FILE", "SOURCE", client, request, batch));
        assertNotEquals(key, ModelCache.key("rename", "FILE", "MATH", client, request(" hi "), batch));
        var budget = new ModelClient.Request(request.source(), request.meaning(), request.replacement(),
                request.candidates(), request.excerpts(), 1000, null);
        assertNotEquals(key, ModelCache.key("rename", "FILE", "MATH", client, budget, batch));
        var excerpt = new ModelClient.Request(request.source(), request.meaning(), request.replacement(),
                request.candidates(), List.of(new ModelClient.Excerpt("excerpt", List.of("paragraph"), "n new context")), 1500, null);
        assertNotEquals(key, ModelCache.key("rename", "FILE", "MATH", client, excerpt, batch));
        var slice = batch.slices().getFirst();
        var changedBatch = new ContextRetriever.Batch(batch.candidateIds(), List.of(new ContextRetriever.Slice(
                slice.id(), slice.path(), "different-hash", 0, 1, 1, 1, slice.roles(), slice.text())), 1500);
        assertNotEquals(key, ModelCache.key("rename", "FILE", "MATH", client, request, changedBatch));
        client.endpoint = "https://other.invalid";
        assertNotEquals(key, ModelCache.key("rename", "FILE", "MATH", client, request, batch));
        client.endpoint = "https://fixture.invalid";
        client.name = "other-model";
        assertNotEquals(key, ModelCache.key("rename", "FILE", "MATH", client, request, batch));
        var strict = new OpenRouterClient(new ModelSettings.Profile(ModelSettings.Provider.OPENROUTER,
                "fixture", "TEST_KEY", 60, ModelSettings.OutputMode.JSON_SCHEMA));
        var prompt = new OpenRouterClient(new ModelSettings.Profile(ModelSettings.Provider.OPENROUTER,
                "fixture", "TEST_KEY", 60, ModelSettings.OutputMode.PROMPT_JSON));
        assertNotEquals(ModelCache.key("rename", "FILE", "MATH", strict, request, batch),
                ModelCache.key("rename", "FILE", "MATH", prompt, request, batch));
    }

    private Path root() throws IOException { return directory.toRealPath(); }

    private Path source() throws IOException { return Files.writeString(root().resolve("main.tex"), "$n$"); }

    private Path cachedFile() throws IOException {
        try (var paths = Files.list(root().resolve(".tex-suite/cache"))) {
            return paths.filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}\\.json")).findFirst().orElseThrow();
        }
    }

    static ModelClient.Request request(String replacement) {
        return new ModelClient.Request("n", "length", replacement,
                List.of(new ModelClient.Candidate("a".repeat(64), "excerpt", 0, 1)),
                List.of(new ModelClient.Excerpt("excerpt", List.of("paragraph"), "n private source text")), 1500, null);
    }

    static ContextRetriever.Batch batch() {
        return new ContextRetriever.Batch(List.of("a".repeat(64)), List.of(new ContextRetriever.Slice(
                "excerpt", Path.of("main.tex"), "hash", 0, 21, 1, 1, List.of("paragraph"), "n private source text")), 1500);
    }

    static ModelClient.Result result(ModelClient.Request request) {
        return new ModelClient.Result(request.candidates().stream().map(candidate ->
                new ModelClient.Decision(candidate.id(), ModelClient.Action.REPLACE, "Synthetic match", 0.9)).toList());
    }

    private Run run(Path source, String answers, Fixture client) {
        return run(source, answers, client, "length", "m");
    }

    private Run run(Path source, String answers, Fixture client, String meaning, String replacement) {
        var output = new StringWriter();
        var errors = new StringWriter();
        int code = new MathematicalRenameWorkflow(reader(answers), new PrintWriter(output), new PrintWriter(errors),
                false, new TexCompileGate(null, true, null, null), client)
                .run(new RenameRequest(source, "n", meaning, replacement, RenameRequest.Scope.FILE));
        return new Run(code, output.toString(), errors.toString());
    }

    private static BufferedReader reader(String answers) { return new BufferedReader(new StringReader(answers)); }

    private record Run(int code, String output, String errors) { }

    private interface Effect { void run() throws IOException; }

    static final class Fixture implements ModelClient {
        private int calls;
        private Request lastRequest;
        private int failAt;
        private String endpoint = "https://fixture.invalid";
        private String name = "fixture-model";
        private boolean repair;
        private boolean fail;
        private Action action = Action.REPLACE;
        private Effect afterCall = () -> { };
        private Effect beforePreview = () -> { };

        @Override public String destination() { return endpoint; }
        @Override public String model() { return name; }
        @Override public String preview(Request request) throws Failure {
            try {
                beforePreview.run();
                return ModelProtocol.JSON.writeValueAsString(request);
            } catch (IOException exception) {
                throw new Failure(Problem.CONFIGURATION, "Fixture preview failed");
            }
        }
        @Override public Result classify(Request request) throws Failure {
            calls++;
            lastRequest = request;
            if (fail || calls == failAt) throw new Failure(Problem.RATE_LIMIT, "Fixture failure");
            if (repair && request.repairOutput() == null) throw new Failure(Problem.INVALID_OUTPUT, "Invalid output", "broken");
            try {
                afterCall.run();
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new Result(request.candidates().stream().map(candidate ->
                    new Decision(candidate.id(), action, "Synthetic match", 0.9)).toList());
        }
    }
}
