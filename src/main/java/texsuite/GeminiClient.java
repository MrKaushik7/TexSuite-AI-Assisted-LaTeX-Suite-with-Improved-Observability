package texsuite;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.function.Function;

/** Native Gemini generateContent; the key is a header, never a URL parameter. */
final class GeminiClient implements ModelClient {
    private final ModelSettings.Profile profile;
    private final URI endpoint;
    private final ModelHttp transport;

    GeminiClient(ModelSettings.Profile profile) {
        this(profile, endpoint(profile), ModelHttp.client(profile), System::getenv);
    }

    GeminiClient(ModelSettings.Profile profile, URI endpoint, HttpClient http,
            Function<String, String> environment) {
        this.profile = profile;
        this.endpoint = endpoint;
        this.transport = new ModelHttp(profile, endpoint, endpoint(profile), http, environment, "x-goog-api-key");
    }

    private static URI endpoint(ModelSettings.Profile profile) {
        if (profile.provider() != ModelSettings.Provider.GEMINI || profile.model().isEmpty() || !profile.model().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Gemini requires an explicit bare model ID.");
        }
        return URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                + profile.model() + ":generateContent");
    }

    @Override public String destination() { return endpoint.toString(); }
    @Override public String model() { return profile.model(); }
    @Override public String decisionSource() { return "gemini-reviewed"; }

    @Override public String preview(Request request) throws Failure {
        ModelProtocol.validateRequest(profile, request);
        try {
            var body = ModelProtocol.JSON.createObjectNode();
            body.putObject("systemInstruction").putArray("parts").addObject()
                    .put("text", ModelProtocol.INSTRUCTIONS);
            var message = body.putArray("contents").addObject();
            message.put("role", "user");
            message.putArray("parts").addObject().put("text", ModelProtocol.data(request));
            var config = body.putObject("generationConfig");
            config.put("maxOutputTokens", request.maxOutputTokens());
            config.put("candidateCount", 1);
            // Use the generateContent fields exercised by Google's Gemini 2.5 SDK example.
            config.put("responseMimeType", "application/json");
            config.set("responseJsonSchema", ModelProtocol.JSON.readTree(ModelProtocol.SCHEMA));
            return ModelProtocol.encode(body);
        } catch (IOException exception) {
            throw new Failure(Problem.CONFIGURATION, "Could not encode the model request.");
        }
    }

    @Override public Result classify(Request request) throws Failure {
        JsonNode envelope = ModelProtocol.envelope(transport.send(preview(request)));
        if (envelope.hasNonNull("error")) throw new Failure(Problem.HTTP, "Gemini returned an error envelope.");
        JsonNode block = envelope.path("promptFeedback").path("blockReason");
        if (!block.isMissingNode() && !block.isNull()) {
            throw new Failure(Problem.REFUSAL, "Gemini blocked the classification request.");
        }
        JsonNode candidates = envelope.path("candidates");
        if (!candidates.isArray() || candidates.size() != 1) {
            throw new Failure(Problem.INVALID_OUTPUT, "Expected one Gemini classification candidate.");
        }
        JsonNode candidate = candidates.get(0);
        String finish = candidate.path("finishReason").asText();
        if (!finish.equals("STOP")) {
            throw new Failure(finish.equals("SAFETY") || finish.equals("RECITATION") ? Problem.REFUSAL : Problem.INCOMPLETE,
                    "Gemini did not return an unrestricted completed response.");
        }
        for (JsonNode rating : candidate.path("safetyRatings")) {
            if (rating.path("blocked").asBoolean()) throw new Failure(Problem.REFUSAL, "Gemini blocked the classification request.");
        }
        JsonNode content = candidate.path("content");
        JsonNode parts = content.path("parts");
        if (!content.path("role").asText().equals("model") || !parts.isArray() || parts.size() != 1
                || !parts.get(0).path("text").isTextual() || parts.get(0).path("thought").asBoolean()
                || !onlyTextFields(parts.get(0))) {
            throw new Failure(Problem.INVALID_OUTPUT, "Unexpected Gemini message content.");
        }
        return ModelProtocol.decisions(parts.get(0).path("text").textValue(), request);
    }
    private static boolean onlyTextFields(JsonNode part) {
        var names = part.fieldNames();
        while (names.hasNext()) {
            if (!java.util.Set.of("text", "thought", "thoughtSignature").contains(names.next())) return false;
        }
        return true;
    }
}
