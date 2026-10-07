package texsuite;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Exercise both new wire contracts over real loopback HTTP, without provider credentials. */
final class ProviderClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ID = "a".repeat(64);
    private static final List<ModelSettings.Provider> PROVIDERS = List.of(
            ModelSettings.Provider.OPENROUTER, ModelSettings.Provider.GEMINI);

    @Test
    void explicitPromptJsonSupportsNemotronWithoutSilentSchemaDowngrade() throws Exception {
        var provider = ModelSettings.Provider.OPENROUTER;
        try (var fixture = new Fixture(200, envelope(provider, decision(ID, "replace")), 0)) {
            var profile = new ModelSettings.Profile(provider, "nvidia/nemotron-3-ultra-550b-a55b:free",
                    "TEST_KEY", 1, ModelSettings.OutputMode.PROMPT_JSON);
            var client = new OpenRouterClient(profile, URI.create("http://127.0.0.1:"
                    + fixture.server.getAddress().getPort() + "/test"), HttpClient.newHttpClient(), name -> "fixture-secret");
            var body = JSON.readTree(client.preview(request()));
            assertFalse(body.has("response_format"));
            assertTrue(body.at("/provider/require_parameters").asBoolean());
            assertTrue(body.at("/messages/0/content").asText().contains(ModelProtocol.SCHEMA));
            assertEquals(ModelClient.Action.REPLACE, client.classify(request()).decisions().getFirst().action());
            assertFalse(JSON.readTree(fixture.body).has("response_format"));
        }
        assertThrows(IllegalArgumentException.class, () -> new ModelSettings.Profile(
                ModelSettings.Provider.GEMINI, "gemini-2.5-flash", "GEMINI_API_KEY", 60, ModelSettings.OutputMode.PROMPT_JSON));
    }

    @Test
    void exactWirePayloadHeaderAuthenticationAndAllActions() throws Exception {
        for (var provider : PROVIDERS) {
            for (String action : List.of("replace", "keep", "needsHumanReview")) {
                try (var fixture = new Fixture(200, envelope(provider, decision(ID, action)), 0)) {
                    var client = fixture.client(provider, "fixture-secret");
                    String preview = client.preview(request());
                    assertEquals(action.equals("replace") ? ModelClient.Action.REPLACE
                            : action.equals("keep") ? ModelClient.Action.KEEP : ModelClient.Action.NEEDS_HUMAN_REVIEW,
                            client.classify(request()).decisions().getFirst().action());
                    assertEquals(preview, fixture.body);
                    assertEquals(1, fixture.calls);
                    assertEquals(provider.id() + "-reviewed", client.decisionSource());
                    assertFalse(preview.contains("fixture-secret"));
                    assertNull(fixture.query);
                    var body = JSON.readTree(preview);
                    String data;
                    if (provider == ModelSettings.Provider.OPENROUTER) {
                        assertEquals("Bearer fixture-secret", fixture.authorization);
                        assertNull(fixture.googleKey);
                        assertEquals("nvidia/nemotron-3-ultra-550b-a55b:free", body.path("model").asText());
                        assertTrue(body.at("/provider/require_parameters").asBoolean());
                        assertFalse(body.has("models"));
                        assertEquals(1500, body.path("max_tokens").asInt());
                        assertFalse(body.path("stream").asBoolean());
                        assertTrue(body.at("/response_format/json_schema/strict").asBoolean());
                        data = body.at("/messages/1/content").asText();
                    } else {
                        assertNull(fixture.authorization);
                        assertEquals("fixture-secret", fixture.googleKey);
                        assertEquals(1500, body.at("/generationConfig/maxOutputTokens").asInt());
                        assertEquals("application/json", body.at("/generationConfig/responseMimeType").asText());
                        assertEquals(JSON.readTree(ModelProtocol.SCHEMA), body.at("/generationConfig/responseJsonSchema"));
                        assertFalse(body.path("generationConfig").has("responseFormat"));
                        assertFalse(body.has("tools"));
                        data = body.at("/contents/0/parts/0/text").asText();
                    }
                    assertEquals("é $n$", JSON.readTree(data).at("/excerpts/0/text").asText());
                }
            }
        }
    }

    @Test
    void geminiCapturedFencedDecisionOutputRequiresRepairWithExplicitContract() throws Exception {
        String first = "9db2c5da9d25ba24c6fd50154d1dfc50d6598c5a73609aee9268a2e10a7ec02c";
        String second = "4db1d53cdad97c9536cd3ce957ef82f37a75dbecef96eb856a531625fe6a952f";
        var request = new ModelClient.Request("n", "database length only, not noise", "\\numElements",
                List.of(new ModelClient.Candidate(first, "database", 90, 91),
                        new ModelClient.Candidate(second, "noise", 22, 23)),
                List.of(new ModelClient.Excerpt("database", List.of("paragraph", "macro"),
                        "\\documentclass{article}\n\\newcommand{\\numElements}{n}\n\\begin{document}\nDatabase length is $n$."),
                        new ModelClient.Excerpt("noise", List.of("paragraph"), "Independent noise is $n$.\n\\end{document}\n")),
                1500, null);
        String observed = """
                ```json
                {"decisions": [{"id": "%s", "decision": "replace", "reason": "The variable 'n' is explicitly defined as 'Database length'. This matches the user's intended meaning.", "confidence": 1.0}, {"id": "%s", "decision": "keep", "reason": "The variable 'n' is explicitly defined as 'Independent noise'. This contradicts the user's intended meaning ('not noise').", "confidence": 1.0}]}
                ```""".formatted(first, second);
        var provider = ModelSettings.Provider.GEMINI;

        try (var fixture = new Fixture(200, envelope(provider, observed), 0)) {
            var client = fixture.client(provider, "fixture-secret");
            var failure = assertThrows(ModelClient.Failure.class, () -> client.classify(request));
            assertEquals(ModelClient.Problem.INVALID_OUTPUT, failure.problem());
            assertEquals(observed, failure.repairOutput());
            assertEquals(1, fixture.calls);

            for (var input : List.of(request, request.repair(observed))) {
                var payload = JSON.readTree(client.preview(input));
                assertEquals("application/json", payload.at("/generationConfig/responseMimeType").asText());
                assertEquals(JSON.readTree(ModelProtocol.SCHEMA), payload.at("/generationConfig/responseJsonSchema"));
                assertFalse(payload.path("generationConfig").has("responseFormat"));
                String instructions = payload.at("/systemInstruction/parts/0/text").asText();
                assertTrue(instructions.contains("id, action, reason, confidence"));
                assertTrue(instructions.contains("without Markdown fences"));
            }
            var repair = JSON.readTree(client.preview(request.repair(observed)));
            var evidence = JSON.readTree(repair.at("/contents/0/parts/0/text").asText());
            assertEquals(2, evidence.size());
            assertEquals(List.of(first, second), JSON.convertValue(evidence.path("allowedIds"), List.class));
            assertEquals(observed, evidence.path("previousOutput").asText());
            assertFalse(evidence.has("excerpts"));
            assertFalse(evidence.has("meaning"));
        }

        // Fixing formatting never means accepting either fences or alias keys locally.
        String valid = observed.substring("```json\n".length(), observed.length() - "\n```".length())
                .replace("\"decision\":", "\"action\":");
        for (String invalid : List.of(observed, "```json\n" + valid + "\n```", valid.replace("\"action\":", "\"decision\":"))) {
            try (var fixture = new Fixture(200, envelope(provider, invalid), 0)) {
                assertThrows(ModelClient.Failure.class, () -> fixture.client(provider, "fixture-secret").classify(request));
            }
        }
        try (var fixture = new Fixture(200, envelope(provider, valid), 0)) {
            var result = fixture.client(provider, "fixture-secret").classify(request);
            assertEquals(ModelClient.Action.REPLACE, result.decisions().getFirst().action());
            assertEquals(ModelClient.Action.KEEP, result.decisions().getLast().action());
        }
    }

    @Test
    void non200FailuresNeverRetryRedirectOrExposeRemoteText() throws Exception {
        for (var provider : PROVIDERS) {
            for (int status : List.of(201, 302, 400, 401, 403, 404, 429, 500)) {
                try (var fixture = new Fixture(status, "fixture-secret private source", 0)) {
                    var failure = assertThrows(ModelClient.Failure.class,
                            () -> fixture.client(provider, "fixture-secret").classify(request()));
                    assertEquals(switch (status) {
                        case 401, 403 -> ModelClient.Problem.AUTHENTICATION;
                        case 404 -> ModelClient.Problem.NOT_FOUND;
                        case 429 -> ModelClient.Problem.RATE_LIMIT;
                        default -> ModelClient.Problem.HTTP;
                    }, failure.problem());
                    assertFalse(failure.getMessage().contains("fixture-secret"));
                    assertFalse(failure.getMessage().contains("private source"));
                    assertNull(failure.getCause());
                    assertEquals(1, fixture.calls);
                }
            }
        }
    }

    @Test
    void incompleteRefusedUnexpectedAndAmbiguousEnvelopesAreNotRepairable() throws Exception {
        for (var provider : PROVIDERS) {
            String good = envelope(provider, decision(ID, "replace"));
            List<String> invalid = provider == ModelSettings.Provider.OPENROUTER
                    ? List.of(good.replace("\"stop\"", "\"length\""), good.replace("\"stop\"", "\"content_filter\""),
                            good.replace("\"assistant\"", "\"user\""), "{\"choices\":[]}",
                            "{\"error\":{\"message\":\"private source\"}}",
                            good.replace("\"role\":", "\"tool_calls\":[],\"role\":"))
                    : List.of(good.replace("\"STOP\"", "\"MAX_TOKENS\""), good.replace("\"STOP\"", "\"SAFETY\""),
                            good.replace("\"model\"", "\"user\""), "{\"candidates\":[]}",
                            "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
                            good.replace("\"text\":", "\"thought\":true,\"text\":"));
            for (String body : invalid) {
                try (var fixture = new Fixture(200, body, 0)) {
                    var failure = assertThrows(ModelClient.Failure.class,
                            () -> fixture.client(provider, "fixture-secret").classify(request()));
                    assertNull(failure.repairOutput());
                }
            }
        }
    }

    @Test
    void exactIdsAndOneBoundedSourceFreeSchemaRepair() throws Exception {
        for (var provider : PROVIDERS) {
            for (String invalid : List.of(decision("b".repeat(64), "replace"), "{\"decisions\":[]}")) {
                try (var fixture = new Fixture(200, envelope(provider, invalid), 0)) {
                    var failure = assertThrows(ModelClient.Failure.class,
                            () -> fixture.client(provider, "fixture-secret").classify(request()));
                    assertNull(failure.repairOutput());
                }
            }
            try (var fixture = new Fixture(200, envelope(provider, "broken JSON"), 0)) {
                var client = fixture.client(provider, "fixture-secret");
                var failure = assertThrows(ModelClient.Failure.class, () -> client.classify(request()));
                assertEquals("broken JSON", failure.repairOutput());
                String repair = client.preview(request().repair(failure.repairOutput()));
                var body = JSON.readTree(repair);
                String data = provider == ModelSettings.Provider.OPENROUTER
                        ? body.at("/messages/1/content").asText() : body.at("/contents/0/parts/0/text").asText();
                var evidence = JSON.readTree(data);
                assertEquals(2, evidence.size());
                assertTrue(evidence.has("allowedIds"));
                assertEquals("broken JSON", evidence.path("previousOutput").asText());
                assertFalse(repair.contains("é $n$"));
            }
        }
    }

    @Test
    void missingKeysUnsafeDestinationsAndInvalidSourceRangesSendNothing() throws Exception {
        for (var provider : PROVIDERS) {
            try (var fixture = new Fixture(200, "{}", 0)) {
                assertEquals(ModelClient.Problem.MISSING_KEY, assertThrows(ModelClient.Failure.class,
                        () -> fixture.client(provider, null).classify(request())).problem());
                var invalid = new ModelClient.Request("n", "length", "m",
                        List.of(new ModelClient.Candidate(ID, "slice", 0, 1)), request().excerpts(), 1500, null);
                assertThrows(ModelClient.Failure.class, () -> fixture.client(provider, "fixture-secret").classify(invalid));
                assertEquals(0, fixture.calls);
                assertThrows(IllegalArgumentException.class, () -> client(provider, URI.create("https://example.com"), "secret", 1));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new ModelSettings.Profile(
                ModelSettings.Provider.GEMINI, "../model?key=secret", "GEMINI_API_KEY", 60));
        assertInstanceOf(OpenRouterClient.class, ModelClient.create(profile(ModelSettings.Provider.OPENROUTER, 60)));
        assertInstanceOf(GeminiClient.class, ModelClient.create(profile(ModelSettings.Provider.GEMINI, 60)));
    }

    @Test
    void oversizedBodiesAndStalledBodiesRespectSharedLimits() throws Exception {
        for (var provider : PROVIDERS) {
            try (var fixture = new Fixture(200, "x".repeat(ModelHttp.MAX_RESPONSE_BYTES + 1), 0)) {
                assertEquals(ModelClient.Problem.OVERSIZED, assertThrows(ModelClient.Failure.class,
                        () -> fixture.client(provider, "fixture-secret").classify(request())).problem());
            }
            try (var fixture = new Fixture(200, "{}", 1)) {
                assertEquals(ModelClient.Problem.TIMEOUT, assertThrows(ModelClient.Failure.class,
                        () -> fixture.client(provider, "fixture-secret").classify(request())).problem());
            }
        }
    }

    private static ModelClient.Request request() {
        return new ModelClient.Request("n", "length", "m", List.of(new ModelClient.Candidate(ID, "slice", 4, 5)),
                List.of(new ModelClient.Excerpt("slice", List.of("paragraph"), "é $n$")), 1500, null);
    }

    private static String decision(String id, String action) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("decisions", List.of(java.util.Map.of(
                "id", id, "action", action, "reason", "Meaning decision", "confidence", 0.9))));
    }

    private static String envelope(ModelSettings.Provider provider, String text) throws Exception {
        var body = JSON.createObjectNode();
        if (provider == ModelSettings.Provider.OPENROUTER) {
            var choice = body.putArray("choices").addObject();
            choice.put("finish_reason", "stop");
            choice.putObject("message").put("role", "assistant").put("content", text);
        } else {
            var candidate = body.putArray("candidates").addObject();
            candidate.put("finishReason", "STOP");
            candidate.putObject("content").put("role", "model").putArray("parts").addObject().put("text", text);
        }
        return JSON.writeValueAsString(body);
    }

    private static ModelSettings.Profile profile(ModelSettings.Provider provider, int timeout) {
        return new ModelSettings.Profile(provider, provider == ModelSettings.Provider.OPENROUTER
                ? "nvidia/nemotron-3-ultra-550b-a55b:free" : "gemini-2.5-flash", "TEST_KEY", timeout);
    }

    private static ModelClient client(ModelSettings.Provider provider, URI uri, String key, int timeout) {
        return provider == ModelSettings.Provider.OPENROUTER
                ? new OpenRouterClient(profile(provider, timeout), uri, HttpClient.newHttpClient(), name -> key)
                : new GeminiClient(profile(provider, timeout), uri, HttpClient.newHttpClient(), name -> key);
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private volatile String body;
        private volatile String authorization;
        private volatile String googleKey;
        private volatile String query;
        private volatile int calls;

        Fixture(int status, String response, int delay) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/test", exchange -> {
                calls++;
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                googleKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
                query = exchange.getRequestURI().getQuery();
                body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Location", "https://example.com");
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes, 0, delay == 0 ? bytes.length : 1);
                    if (delay != 0) {
                        exchange.getResponseBody().flush();
                        Thread.sleep(2000);
                        exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        ModelClient client(ModelSettings.Provider provider, String key) {
            return ProviderClientTest.client(provider,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/test"), key, 1);
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
