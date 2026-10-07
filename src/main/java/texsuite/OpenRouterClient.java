package texsuite;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.function.Function;

/** OpenRouter Chat Completions with explicit schema or prompt-JSON mode and no paid model fallback. */
final class OpenRouterClient implements ModelClient {
    static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private final ModelSettings.Profile profile;
    private final URI endpoint;
    private final ModelHttp transport;

    OpenRouterClient(ModelSettings.Profile profile) {
        this(profile, ENDPOINT, ModelHttp.client(profile), System::getenv);
    }

    OpenRouterClient(ModelSettings.Profile profile, URI endpoint, HttpClient http,
            Function<String, String> environment) {
        if (profile.provider() != ModelSettings.Provider.OPENROUTER) {
            throw new IllegalArgumentException("Profile provider does not match the adapter.");
        }
        this.profile = profile;
        this.endpoint = endpoint;
        this.transport = new ModelHttp(profile, endpoint, ENDPOINT, http, environment, "Authorization");
    }

    @Override public String destination() { return endpoint.toString(); }
    @Override public String model() { return profile.model(); }
    @Override public String decisionSource() { return "openrouter-reviewed"; }

    @Override public String preview(Request request) throws Failure {
        ModelProtocol.validateRequest(profile, request);
        try {
            var body = ModelProtocol.JSON.createObjectNode();
            body.put("model", model());
            body.put("stream", false);
            body.put("max_tokens", request.maxOutputTokens());
            var messages = body.putArray("messages");
            boolean schemaOutput = profile.outputMode() == ModelSettings.OutputMode.JSON_SCHEMA;
            messages.addObject().put("role", "system").put("content", ModelProtocol.INSTRUCTIONS
                    + (schemaOutput ? "" : "\nReturn JSON matching this schema:\n" + ModelProtocol.SCHEMA));
            messages.addObject().put("role", "user").put("content", ModelProtocol.data(request));
            body.putObject("provider").put("require_parameters", true);
            if (schemaOutput) {
                var format = body.putObject("response_format");
                format.put("type", "json_schema");
                var schema = format.putObject("json_schema");
                schema.put("name", "rename_decisions_v1").put("strict", true);
                schema.set("schema", ModelProtocol.JSON.readTree(ModelProtocol.SCHEMA));
            }
            return ModelProtocol.encode(body);
        } catch (IOException exception) {
            throw new Failure(Problem.CONFIGURATION, "Could not encode the model request.");
        }
    }

    @Override public Result classify(Request request) throws Failure {
        JsonNode envelope = ModelProtocol.envelope(transport.send(preview(request)));
        if (envelope.hasNonNull("error")) throw new Failure(Problem.HTTP, "OpenRouter returned an error envelope.");
        JsonNode choices = envelope.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            throw new Failure(Problem.INVALID_OUTPUT, "Expected one OpenRouter classification choice.");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (message.hasNonNull("refusal") || choice.path("finish_reason").asText().equals("content_filter")) {
            throw new Failure(Problem.REFUSAL, "OpenRouter declined the classification request.");
        }
        if (choice.hasNonNull("error") || !choice.path("finish_reason").asText().equals("stop")) {
            throw new Failure(Problem.INCOMPLETE, "OpenRouter did not return a completed response.");
        }
        if (!message.path("role").asText().equals("assistant") || !message.path("content").isTextual()
                || message.hasNonNull("tool_calls")) {
            throw new Failure(Problem.INVALID_OUTPUT, "Unexpected OpenRouter message content.");
        }
        return ModelProtocol.decisions(message.path("content").textValue(), request);
    }
}
