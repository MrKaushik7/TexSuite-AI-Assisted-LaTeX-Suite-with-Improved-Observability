package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verify remote consent, operation-wide fallback and human-only edit authorization. */
final class ModelReviewTest {
    @TempDir Path directory;

    @Test
    void reviewedAiCanBeCorrectedWithoutEditingProtectedSourceAndRecordsProvenance() throws Exception {
        String original = "$n+n+n$ prose n\n% n\n\\newcommand{\\name}{n}\n";
        Path source = source(original);
        var client = new Fake(request -> new ModelClient.Result(List.of(
                decision(request, 0, ModelClient.Action.KEEP),
                decision(request, 1, ModelClient.Action.REPLACE),
                decision(request, 2, ModelClient.Action.NEEDS_HUMAN_REVIEW))));
        var run = run(source, "1\ny\nmanual\ny\nn\ny\napply\n", client);
        assertEquals(0, run.code(), run.errors());
        assertEquals("$m+n+m$ prose n\n% n\n\\newcommand{\\name}{n}\n", Files.readString(source));
        var headings = List.of("=== AI KEEP: KEEP ORIGINAL ===",
                "=== AI REPLACE: REPLACE OCCURRENCE ===",
                "=== AI NEEDS_HUMAN_REVIEW: DECIDE MANUALLY ===");
        var questions = List.of("[AI KEEP] Override KEEP and replace this occurrence? [y/N, Enter keeps original]: ",
                "[AI REPLACE] Accept replacement for this occurrence? [y/N]: ",
                "[AI NEEDS_HUMAN_REVIEW] Replace this occurrence after manual review? [y/N]: ");
        int previous = -1;
        for (int i = 0; i < headings.size(); i++) {
            int heading = run.output().indexOf(headings.get(i), previous + 1);
            int excerpt = run.output().indexOf("    - ", heading);
            int question = run.output().indexOf(questions.get(i), heading);
            assertTrue(heading > previous && excerpt > heading && question > excerpt,
                    "Recommendation must lead its excerpt and repeat at the answer prompt: " + headings.get(i));
            previous = question;
        }
        assertTrue(run.output().contains("Replacement below applies only if you override KEEP."));
        assertEquals(1, client.requests.size());
        try (var paths = Files.list(directory.resolve(".tex-suite/plans"))) {
            String plan = Files.readString(paths.findFirst().orElseThrow());
            assertTrue(plan.contains("ai-reviewed"));
        }
    }

    @Test
    void enterKeepsOriginalForEveryAiRecommendation() throws Exception {
        String original = "$n+n+n$";
        Path source = source(original);
        var client = new Fake(request -> new ModelClient.Result(List.of(
                decision(request, 0, ModelClient.Action.KEEP),
                decision(request, 1, ModelClient.Action.REPLACE),
                decision(request, 2, ModelClient.Action.NEEDS_HUMAN_REVIEW))));

        var run = run(source, "1\ny\nmanual\n\n\n\n", client);

        assertEquals(0, run.code(), run.errors());
        assertEquals(original, Files.readString(source));
        assertEquals(1, client.requests.size());
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void deniedConsentManualChoiceQuitAndEofNeverCallProviderOrWrite() throws Exception {
        Path source = source("$n$");
        for (String answers : List.of("1\nn\nn\n", "2\nn\n", "1\nquit\n", "1\n")) {
            var client = new Fake(request -> result(request));
            var run = run(source, answers, client);
            assertEquals(0, run.code(), run.errors());
            assertEquals(0, client.requests.size());
            assertEquals("$n$", Files.readString(source));
            assertFalse(Files.exists(directory.resolve(".tex-suite")));
        }
    }

    @Test
    void failedLaterBatchDiscardsEarlierDecisionsAndFallbackPersistsManual() throws Exception {
        Path source = source("$n+n+n+n+n+n+n+n+n$");
        var client = new Fake(request -> result(request));
        client.failCall = 2;
        var run = run(source, "1\ny\ny\ny\ny\nn\nn\nn\nn\nn\nn\nn\nn\napply\n", client);
        assertEquals(0, run.code(), run.errors());
        assertEquals(2, client.requests.size());
        assertTrue(run.output().contains("All AI decisions for this operation were discarded"));
        assertFalse(run.output().contains("AI REPLACE"));
        assertEquals("$m+n+n+n+n+n+n+n+n$", Files.readString(source));
        try (var paths = Files.list(directory.resolve(".tex-suite/plans"))) {
            assertTrue(Files.readString(paths.findFirst().orElseThrow()).contains("\"decisionSource\" : \"manual\""));
        }
    }

    @Test
    void oneExplicitRepairSendsNoOriginalExcerptsAndRequiresSecondConsent() throws Exception {
        Path source = source("$n$");
        var client = new Fake(request -> result(request));
        client.schemaFailures = 1;
        var run = run(source, "1\ny\ny\ny\nn\n", client);
        assertEquals(0, run.code(), run.errors());
        assertEquals(2, client.requests.size());
        assertNull(client.requests.getFirst().repairOutput());
        assertEquals("broken-output", client.requests.get(1).repairOutput());
        assertTrue(client.requests.get(1).excerpts().isEmpty());
        assertEquals(2, run.output().split("Send this request", -1).length - 1);
        assertEquals("$n$", Files.readString(source));
    }

    @Test
    void repeatedSchemaFailureOrDeniedRepairDoesNotRetryAgain() throws Exception {
        Path source = source("$n$");
        for (String answers : List.of("1\ny\ny\ny\nn\n", "1\ny\nn\nn\n", "1\ny\ny\nn\nn\n")) {
            var client = new Fake(request -> result(request));
            client.schemaFailures = 3;
            var run = run(source, answers, client);
            assertEquals(0, run.code(), run.errors());
            assertTrue(client.requests.size() <= 2);
            assertEquals("$n$", Files.readString(source));
            assertFalse(Files.exists(directory.resolve(".tex-suite")));
        }
    }

    @Test
    void rejectsUnvalidatedProviderIdsBeforeHumanSelection() throws Exception {
        Path source = source("$n$");
        var client = new Fake(request -> new ModelClient.Result(List.of(
                new ModelClient.Decision("foreign", ModelClient.Action.REPLACE, "ok", 1))));
        var run = run(source, "1\ny\nn\n", client);
        assertEquals(0, run.code(), run.errors());
        assertTrue(run.output().contains("INVALID_OUTPUT"));
        assertFalse(run.output().contains("Rename this occurrence"));
        assertEquals("$n$", Files.readString(source));
    }

    @Test
    void changedSourceDuringCallRequiresFreshContextAndNewConsentThenStops() throws Exception {
        Path source = source("$n$");
        int[] changes = {0};
        var client = new Fake(request -> {
            try { Files.writeString(source, "$n$ " + ++changes[0]); }
            catch (IOException exception) { throw new UncheckedIOException(exception); }
            return result(request);
        });
        var run = run(source, "1\ny\n1\ny\n1\ny\n", client);
        assertEquals(2, run.code());
        assertEquals(3, client.requests.size());
        assertEquals(3, run.output().split("Send this request", -1).length - 1);
        assertFalse(run.output().contains("Rename this occurrence"));
        assertEquals("$n$ 3", Files.readString(source));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void providerTestRequiresConsentAndUsesOnlySyntheticEvidence() throws Exception {
        for (String answers : List.of("n\n", "y\n")) {
            var client = new Fake(request -> result(request));
            var output = new StringWriter();
            var out = new PrintWriter(output);
            new ModelReview(new EditReview(new BufferedReader(new StringReader(answers)), out), out, client)
                    .testProvider();
            assertEquals(answers.startsWith("y") ? 1 : 0, client.requests.size());
            assertTrue(output.toString().contains("may incur API charges"));
            if (!client.requests.isEmpty()) {
                assertEquals("x", client.requests.getFirst().excerpts().getFirst().text());
                assertTrue(output.toString().contains("Provider test passed"));
            }
        }
    }

    private Path source(String text) throws Exception {
        return Files.writeString(directory.resolve("main.tex"), text).toRealPath();
    }

    private Run run(Path source, String answers, ModelClient client) {
        var output = new StringWriter();
        var errors = new StringWriter();
        int code = new MathematicalRenameWorkflow(new BufferedReader(new StringReader(answers)),
                new PrintWriter(output), new PrintWriter(errors), false,
                new TexCompileGate(null, true, null, null), client)
                .run(new RenameRequest(source, "n", "length", "m", RenameRequest.Scope.FILE));
        return new Run(code, output.toString(), errors.toString());
    }

    private static ModelClient.Decision decision(ModelClient.Request request, int index, ModelClient.Action action) {
        return new ModelClient.Decision(request.candidates().get(index).id(), action, "Synthetic classification", 0.8);
    }

    private static ModelClient.Result result(ModelClient.Request request) {
        return new ModelClient.Result(request.candidates().stream().map(item -> new ModelClient.Decision(
                item.id(), ModelClient.Action.REPLACE, "Synthetic classification", 0.8)).toList());
    }

    private record Run(int code, String output, String errors) { }

    private static final class Fake implements ModelClient {
        private final Function<Request, Result> answers;
        private final List<Request> requests = new ArrayList<>();
        private int failCall;
        private int schemaFailures;

        Fake(Function<Request, Result> answers) {
            this.answers = answers;
        }

        @Override public String destination() { return "http://fixture.invalid"; }

        @Override public String model() { return "fixture-model"; }

        @Override public String preview(Request request) { return "synthetic preview"; }

        @Override public Result classify(Request request) throws Failure {
            requests.add(request);
            if (requests.size() == failCall) throw new Failure(Problem.RATE_LIMIT, "Fixture HTTP 429.");
            if (schemaFailures-- > 0) throw new Failure(Problem.INVALID_OUTPUT, "Fixture schema error.", "broken-output");
            return answers.apply(request);
        }
    }
}
