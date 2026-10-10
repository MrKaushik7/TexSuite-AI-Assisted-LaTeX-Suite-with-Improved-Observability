package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ModelProtocolTest {
    private static final String ID = "a".repeat(64);

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0})
    void decisions_confidenceAtEitherBoundary_isAccepted(double confidence) throws Exception {
        String body = decision(confidence).toString();

        var result = ModelProtocol.decisions(body, request());

        assertEquals(confidence, result.decisions().getFirst().confidence());
        assertEquals(ModelClient.Action.KEEP, result.decisions().getFirst().action());
    }

    @ParameterizedTest
    @ValueSource(strings = {"negative", "aboveOne", "stringConfidence", "missingField",
            "extraField", "unknownId", "duplicateId", "missingId", "unknownAction", "notObject",
            "wrongReason", "wrongId", "wrongAction"})
    void decisions_invalidContract_isRejected(String variant) throws Exception {
        ObjectNode body = decision(0.5);
        ObjectNode item = (ObjectNode) body.path("decisions").get(0);
        switch (variant) {
            case "negative" -> item.put("confidence", -0.000001);
            case "aboveOne" -> item.put("confidence", 1.000001);
            case "stringConfidence" -> item.put("confidence", "0.5");
            case "missingField" -> item.remove("confidence");
            case "extraField" -> item.put("extra", true);
            case "unknownId" -> item.put("id", "b".repeat(64));
            case "duplicateId" -> body.withArray("decisions").add(item.deepCopy());
            case "missingId" -> body.withArray("decisions").removeAll();
            case "unknownAction" -> item.put("action", "write");
            case "notObject" -> body.withArray("decisions").set(0, ModelProtocol.JSON.nullNode());
            case "wrongReason" -> item.put("reason", 2);
            case "wrongId" -> item.put("id", 3);
            case "wrongAction" -> item.put("action", false);
            default -> throw new AssertionError(variant);
        }

        var failure = assertThrows(ModelClient.Failure.class,
                () -> ModelProtocol.decisions(body.toString(), request()));

        assertEquals(ModelClient.Problem.INVALID_OUTPUT, failure.problem());
    }

    @Test
    void encode_exactByteBudgetAccepted_oneExtraByteRejected() throws Exception {
        ObjectNode body = ModelProtocol.JSON.createObjectNode().put("text", "");
        int overhead = ModelProtocol.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(body).length;
        body.put("text", "x".repeat(ModelProtocol.MAX_REQUEST_BYTES - overhead));

        assertEquals(ModelProtocol.MAX_REQUEST_BYTES,
                ModelProtocol.encode(body).getBytes(StandardCharsets.UTF_8).length);
        body.put("text", body.path("text").asText() + "x");
        var failure = assertThrows(ModelClient.Failure.class, () -> ModelProtocol.encode(body));

        assertEquals(ModelClient.Problem.OVERSIZED, failure.problem());
    }

    @Test
    void validateRequest_exactCandidateTokenAndUnicodeContextBudgetsAccepted() throws Exception {
        var profile = new ModelSettings.Profile("fixture-model", "FIXTURE_KEY", 10);
        var candidates = new java.util.ArrayList<ModelClient.Candidate>();
        for (int i = 0; i < 8; i++) {
            candidates.add(new ModelClient.Candidate(String.format("%064x", i), "excerpt", 0, 1));
        }
        String text = "n" + "😀".repeat(23_999);
        var excerpts = List.of(new ModelClient.Excerpt("excerpt", List.of(), text));
        var bounded = new ModelClient.Request("n", "count", "m", candidates, excerpts, 1500, null);

        assertDoesNotThrow(() -> ModelProtocol.validateRequest(profile, bounded));
        assertDoesNotThrow(() -> ModelProtocol.validateRequest(profile, request()));
        assertDoesNotThrow(() -> ModelProtocol.validateRequest(profile,
                new ModelClient.Request("n", "count", "m", request().candidates(),
                        request().excerpts(), 1, null)));
        var overContext = new ModelClient.Request("n", "count", "m", candidates,
                List.of(new ModelClient.Excerpt("excerpt", List.of(), text + "x")), 1500, null);
        assertEquals(ModelClient.Problem.OVERSIZED, assertThrows(ModelClient.Failure.class,
                () -> ModelProtocol.validateRequest(profile, overContext)).problem());
    }

    @Test
    void validateRequest_missingExcerptRejectsBeforeProviderCall() {
        var invalid = new ModelClient.Request("n", "count", "m", request().candidates(),
                List.of(), 100, null);

        var failure = assertThrows(ModelClient.Failure.class, () -> ModelProtocol.validateRequest(
                new ModelSettings.Profile("fixture-model", "FIXTURE_KEY", 10), invalid));

        assertEquals(ModelClient.Problem.CONFIGURATION, failure.problem());
    }

    @Test
    void repairOutput_exactLimitAcceptedAndPreserved_oneOverRejectedOrOmitted() throws Exception {
        var profile = new ModelSettings.Profile("fixture-model", "FIXTURE_KEY", 10);
        String bounded = "x".repeat(16_384);

        assertDoesNotThrow(() -> ModelProtocol.validateRequest(profile, request().repair(bounded)));
        assertThrows(ModelClient.Failure.class,
                () -> ModelProtocol.validateRequest(profile, request().repair(bounded + "x")));
        var exactFailure = assertThrows(ModelClient.Failure.class,
                () -> ModelProtocol.decisions(bounded, request()));
        var overFailure = assertThrows(ModelClient.Failure.class,
                () -> ModelProtocol.decisions(bounded + "x", request()));

        assertEquals(bounded, exactFailure.repairOutput());
        assertNull(overFailure.repairOutput());
    }

    private static ObjectNode decision(double confidence) {
        ObjectNode body = ModelProtocol.JSON.createObjectNode();
        body.putArray("decisions").addObject().put("id", ID).put("action", "keep")
                .put("reason", "different meaning").put("confidence", confidence);
        return body;
    }

    private static ModelClient.Request request() {
        return new ModelClient.Request("n", "count", "m",
                List.of(new ModelClient.Candidate(ID, "excerpt", 0, 1)),
                List.of(new ModelClient.Excerpt("excerpt", List.of("candidate"), "n")), 100, null);
    }
}
