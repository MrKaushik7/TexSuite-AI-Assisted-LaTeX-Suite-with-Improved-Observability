package texsuite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Native Responses transport; every failure message deliberately omits remote bodies and keys. */
final class OpenAiClient implements ModelClient {
    static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    static final int MAX_RESPONSE_BYTES = ModelHttp.MAX_RESPONSE_BYTES;
    private final ModelSettings.Profile profile;
    private final URI endpoint;
    private final ModelHttp transport;

    OpenAiClient(ModelSettings.Profile profile) {
        this(profile, ENDPOINT, ModelHttp.client(profile), System::getenv);
    }

    // Package-local loopback endpoint/transport injection keeps tests offline.
    OpenAiClient(ModelSettings.Profile profile, URI endpoint, HttpClient http,
            Function<String, String> environment) {
        if (profile.provider() != ModelSettings.Provider.OPENAI) {
            throw new IllegalArgumentException("Profile provider does not match the adapter.");
        }
        this.profile = profile;
        this.endpoint = endpoint;
        this.transport = new ModelHttp(profile, endpoint, ENDPOINT, http, environment, "Authorization");
    }

    @Override public String decisionSource() { return "openai-reviewed"; }

    @Override public String destination() {
        return endpoint.toString();
    }

    @Override public String model() {
        return profile.model();
    }

    @Override public String preview(Request request) throws Failure {
        ModelProtocol.validateRequest(profile, request);
        try {
            ObjectNode body = ModelProtocol.JSON.createObjectNode();
            body.put("model", profile.model());
            body.put("store", false);
            body.put("stream", false);
            body.put("instructions", ModelProtocol.INSTRUCTIONS);
            body.put("max_output_tokens", request.maxOutputTokens());
            var message = body.putArray("input").addObject();
            message.put("role", "user");
            message.putArray("content").addObject().put("type", "input_text")
                    .put("text", ModelProtocol.data(request));
            var format = body.putObject("text").putObject("format");
            format.put("type", "json_schema");
            format.put("name", "rename_decisions_v1");
            format.put("strict", true);
            format.set("schema", ModelProtocol.JSON.readTree(ModelProtocol.SCHEMA));
            return ModelProtocol.encode(body);
        } catch (IOException exception) {
            throw new Failure(Problem.CONFIGURATION, "Could not encode the model request.");
        }
    }

    @Override public Result classify(Request request) throws Failure {
        return parse(transport.send(preview(request)), request);
    }

    private Result parse(byte[] bytes, Request request) throws Failure {
        JsonNode response = ModelProtocol.envelope(bytes);
        if (!response.path("status").asText().equals("completed")
                || !response.path("error").isMissingNode() && !response.path("error").isNull()
                || !response.path("incomplete_details").isMissingNode() && !response.path("incomplete_details").isNull()) {
            throw new Failure(Problem.INCOMPLETE, "OpenAI did not return a completed response.");
        }
        JsonNode output = response.path("output");
        if (!output.isArray()) throw new Failure(Problem.INVALID_OUTPUT, "OpenAI response has no output array.");
        List<String> texts = new ArrayList<>();
        for (JsonNode item : output) {
            if (item.path("type").asText().equals("reasoning")) continue;
            if (!item.path("type").asText().equals("message")
                    || !item.path("role").asText().equals("assistant")
                    || !item.path("status").asText().equals("completed") || !item.path("content").isArray()) {
                throw new Failure(Problem.INVALID_OUTPUT, "Unexpected OpenAI output item.");
            }
            for (JsonNode content : item.path("content")) {
                if (content.path("type").asText().equals("refusal")) {
                    throw new Failure(Problem.REFUSAL, "OpenAI declined the classification request.");
                }
                if (!content.path("type").asText().equals("output_text") || !content.path("text").isTextual()) {
                    throw new Failure(Problem.INVALID_OUTPUT, "Unexpected OpenAI message content.");
                }
                texts.add(content.path("text").textValue());
            }
        }
        if (texts.size() != 1) throw new Failure(Problem.INVALID_OUTPUT, "Expected one complete classification output.");
        return ModelProtocol.decisions(texts.getFirst(), request);
    }

}
