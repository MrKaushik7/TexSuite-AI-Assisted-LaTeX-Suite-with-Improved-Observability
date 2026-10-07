package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Exercise actual HTTP and wire parsing on loopback; never contact a provider. */
final class OpenAiClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ID = "a".repeat(64);

    @Test
    void sendsExactPreviewAndParsesAllActionsWithoutHistoryOrKeyInBody() throws Exception {
        String[] actual = {null};
        try (var fixture = new Fixture(200, envelope(decisions(ID, "replace", "Meaning matches", "0.8")),
                actual, 0, "application/json")) {
            var client = fixture.client();
            String preview = client.preview(request());
            assertEquals(ModelClient.Action.REPLACE, client.classify(request()).decisions().getFirst().action());
            assertEquals(preview, actual[0]);
            JsonNode body = JSON.readTree(preview);
            assertFalse(body.path("store").asBoolean());
            assertFalse(body.path("stream").asBoolean());
            assertFalse(body.has("previous_response_id"));
            assertEquals("gpt-6.1-sol", body.path("model").asText());
            assertEquals("json_schema", body.at("/text/format/type").asText());
            assertTrue(body.at("/text/format/strict").asBoolean());
            assertFalse(preview.contains("fixture-secret"));
            assertTrue(body.path("instructions").asText().contains("untrusted evidence"));
            JsonNode evidence = JSON.readTree(body.at("/input/0/content/0/text").asText());
            assertEquals("é $n$", evidence.at("/excerpts/0/text").asText());
            assertEquals(4, evidence.at("/candidates/0/startByte").asInt());
        }
        for (String action : List.of("keep", "needsHumanReview")) {
            try (var fixture = new Fixture(200, envelope(decisions(ID, action, "Other meaning", "0.2")))) {
                assertNotEquals(ModelClient.Action.REPLACE, fixture.client().classify(request()).decisions().getFirst().action());
            }
        }
    }

    @Test
    void mapsEveryNon200WithoutRetryRedirectOrRemoteBodyLeak() throws Exception {
        for (int status : new int[] {201, 302, 400, 401, 403, 404, 429, 500}) {
            try (var fixture = new Fixture(status, "fixture-secret private source")) {
                var failure = assertThrows(ModelClient.Failure.class, () -> fixture.client().classify(request()));
                assertTrue(failure.getMessage().contains("HTTP " + status));
                assertFalse(failure.getMessage().contains("fixture-secret"));
                assertNull(failure.getCause());
                assertEquals(1, fixture.calls);
                assertEquals(switch (status) {
                    case 401, 403 -> ModelClient.Problem.AUTHENTICATION;
                    case 404 -> ModelClient.Problem.NOT_FOUND;
                    case 429 -> ModelClient.Problem.RATE_LIMIT;
                    default -> ModelClient.Problem.HTTP;
                }, failure.problem());
            }
        }
    }

    @Test
    void refusesMissingKeyAndUnsafeEndpointWithoutSending() throws Exception {
        try (var fixture = new Fixture(200, "{}")) {
            var client = new OpenAiClient(new ModelSettings.Profile("gpt-6.1-sol", "TEST_KEY", 2),
                    fixture.uri(), HttpClient.newHttpClient(), variable -> null);
            assertEquals(ModelClient.Problem.MISSING_KEY,
                    assertThrows(ModelClient.Failure.class, () -> client.classify(request())).problem());
            assertEquals(0, fixture.calls);
            assertThrows(IllegalArgumentException.class, () -> new OpenAiClient(
                    new ModelSettings.Profile("gpt-6.1-sol", "TEST_KEY", 2), URI.create("https://example.com"),
                    HttpClient.newHttpClient(), variable -> "fixture-secret"));
        }
    }

    @Test
    void rejectsMalformedRefusedIncompleteAndUnexpectedEnvelopes() throws Exception {
        for (String body : List.of("not JSON", "{}", "{\"status\":\"incomplete\",\"output\":[]}",
                "{\"status\":\"completed\",\"output\":[],\"error\":{\"message\":\"private\"}}",
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                        + "\"status\":\"completed\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"private\"}]}]}",
                "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\"}]}",
                envelope(decisions(ID, "replace", "ok", "1")) + " {}")) {
            try (var fixture = new Fixture(200, body)) {
                var failure = assertThrows(ModelClient.Failure.class, () -> fixture.client().classify(request()));
                assertNull(failure.repairOutput());
                assertFalse(failure.getMessage().contains("private"));
            }
        }
        try (var fixture = new Fixture(200, "{}", new String[1], 0, "text/html")) {
            assertThrows(ModelClient.Failure.class, () -> fixture.client().classify(request()));
        }
    }

    @Test
    void rejectsMissingDuplicateForeignIdsInvalidReasonsAndConfidenceWithoutRepair() throws Exception {
        String valid = decisions(ID, "replace", "ok", "0.5");
        for (String body : List.of("{\"decisions\":[]}", valid.replace(ID, "b".repeat(64)),
                valid.replace("]}", "," + JSON.readTree(valid).path("decisions").get(0) + "]}"),
                decisions(ID, "replace", "", "0.5"), decisions(ID, "replace", "bad\nreason", "0.5"),
                decisions(ID, "replace", "r".repeat(241), "0.5"),
                decisions(ID, "replace", "ok", "1.1"), decisions(ID, "replace", "ok", "-0.1"))) {
            try (var fixture = new Fixture(200, envelope(body))) {
                var failure = assertThrows(ModelClient.Failure.class, () -> fixture.client().classify(request()));
                assertEquals(ModelClient.Problem.INVALID_OUTPUT, failure.problem());
                assertNull(failure.repairOutput());
            }
        }
    }

    @Test
    void permitsBoundedSchemaRepairPreviewWithoutOriginalEvidence() throws Exception {
        for (String malformed : List.of("not JSON", "{\"decisions\":[],\"extra\":1}",
                decisions(ID, "write", "ok", "0.5"), decisions(ID, "replace", "ok", "\"0.5\""))) {
            try (var fixture = new Fixture(200, envelope(malformed))) {
                var client = fixture.client();
                var failure = assertThrows(ModelClient.Failure.class, () -> client.classify(request()));
                assertEquals(malformed, failure.repairOutput());
                JsonNode body = JSON.readTree(client.preview(request().repair(failure.repairOutput())));
                JsonNode evidence = JSON.readTree(body.at("/input/0/content/0/text").asText());
                assertEquals(2, evidence.size());
                assertTrue(evidence.has("allowedIds"));
                assertTrue(evidence.has("previousOutput"));
                assertFalse(evidence.has("excerpts"));
                assertFalse(body.toString().contains("é $n$"));
            }
        }
    }

    @Test
    void enforcesResponseSizeAndDeadlineIncludingStalledBody() throws Exception {
        try (var fixture = new Fixture(200, "x".repeat(OpenAiClient.MAX_RESPONSE_BYTES + 1))) {
            assertEquals(ModelClient.Problem.OVERSIZED,
                    assertThrows(ModelClient.Failure.class, () -> fixture.client().classify(request())).problem());
        }
        for (int delay : new int[] {1, 2}) {
            try (var fixture = new Fixture(200, "{}", new String[1], delay, "application/json")) {
                var client = new OpenAiClient(new ModelSettings.Profile("gpt-6.1-sol", "TEST_KEY", 1),
                        fixture.uri(), HttpClient.newHttpClient(), variable -> "fixture-secret");
                long start = System.nanoTime();
                assertEquals(ModelClient.Problem.TIMEOUT,
                        assertThrows(ModelClient.Failure.class, () -> client.classify(request())).problem());
                assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000);
            }
        }
    }

    @Test
    void rejectsIncorrectRangesAndOverBudgetRequestsBeforeHttp() throws Exception {
        try (var fixture = new Fixture(200, "{}")) {
            var client = fixture.client();
            var original = request();
            for (var changed : List.of(
                    new ModelClient.Request("n", "meaning", "m", List.of(new ModelClient.Candidate(ID, "slice", 3, 4)), original.excerpts(), 1500, null),
                    new ModelClient.Request("n", "meaning", "m", original.candidates(), original.excerpts(), 1501, null),
                    new ModelClient.Request("n", "meaning", "m", original.candidates(), List.of(new ModelClient.Excerpt("slice", List.of(), "x".repeat(24001))), 1500, null),
                    original.repair("x".repeat(16385)))) {
                assertThrows(ModelClient.Failure.class, () -> client.classify(changed));
            }
            assertEquals(0, fixture.calls);
        }
    }

    private static ModelClient.Request request() {
        return new ModelClient.Request("n", "length", "m", List.of(new ModelClient.Candidate(ID, "slice", 4, 5)),
                List.of(new ModelClient.Excerpt("slice", List.of("paragraph"), "é $n$")), 1500, null);
    }

    private static String decisions(String id, String action, String reason, String confidence) throws Exception {
        return "{\"decisions\":[{\"id\":\"" + id + "\",\"action\":\"" + action
                + "\",\"reason\":" + JSON.writeValueAsString(reason) + ",\"confidence\":" + confidence + "}]}";
    }

    private static String envelope(String text) throws Exception {
        return "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\","
                + "\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + JSON.writeValueAsString(text) + "}]}]}";
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private volatile int calls;

        Fixture(int status, String response) throws Exception {
            this(status, response, new String[1], 0, "application/json");
        }

        Fixture(int status, String response, String[] request, int delay, String type) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/responses", exchange -> {
                calls++;
                assertEquals("Bearer fixture-secret", exchange.getRequestHeaders().getFirst("Authorization"));
                request[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    if (delay == 1) Thread.sleep(2000);
                    exchange.getResponseHeaders().set("Content-Type", type);
                    exchange.getResponseHeaders().set("Location", "https://example.com");
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    if (delay == 2) {
                        exchange.getResponseBody().write(bytes, 0, 1);
                        exchange.getResponseBody().flush();
                        Thread.sleep(2000);
                        exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                    } else exchange.getResponseBody().write(bytes);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
        }

        OpenAiClient client() {
            return new OpenAiClient(new ModelSettings.Profile("gpt-6.1-sol", "TEST_KEY", 3), uri(),
                    HttpClient.newHttpClient(), variable -> "fixture-secret");
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
