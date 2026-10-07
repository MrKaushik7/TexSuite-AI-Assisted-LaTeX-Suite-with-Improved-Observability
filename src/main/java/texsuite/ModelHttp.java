package texsuite;

import static texsuite.ModelClient.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** One bounded POST, with no redirects, retries or secret-bearing errors. */
final class ModelHttp {
    static final int MAX_RESPONSE_BYTES = 131_072;
    private final ModelSettings.Profile profile;
    private final URI endpoint;
    private final HttpClient http;
    private final Function<String, String> environment;
    private final String keyHeader;

    ModelHttp(ModelSettings.Profile profile, URI endpoint, URI nativeEndpoint, HttpClient http,
            Function<String, String> environment, String keyHeader) {
        if (!nativeEndpoint.equals(endpoint) && !("http".equals(endpoint.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())
                && endpoint.getUserInfo() == null && endpoint.getQuery() == null && endpoint.getFragment() == null)) {
            throw new IllegalArgumentException("Only the native endpoint or an offline loopback fixture is supported.");
        }
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Provider redirects must be disabled.");
        }
        this.profile = profile;
        this.endpoint = endpoint;
        this.http = http;
        this.environment = environment;
        this.keyHeader = keyHeader;
    }

    static HttpClient client(ModelSettings.Profile profile) {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(profile.timeoutSeconds())).build();
    }

    byte[] send(String body) throws Failure {
        String key = environment.apply(profile.keyEnvironment());
        if (key == null || key.isBlank() || !key.matches("[!-~]+")) {
            throw new Failure(Problem.MISSING_KEY, "The configured key environment variable is missing or invalid.");
        }
        HttpRequest outgoing = HttpRequest.newBuilder(endpoint)
                .header(keyHeader, keyHeader.equals("Authorization") ? "Bearer " + key : key).header("Content-Type", "application/json")
                .header("Accept", "application/json").timeout(Duration.ofSeconds(profile.timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicBoolean oversized = new AtomicBoolean();
        HttpResponse.BodyHandler<byte[]> handler = info -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(value -> {
                    if (bytes.size() + value.length > MAX_RESPONSE_BYTES) {
                        oversized.set(true);
                        throw new IllegalStateException("Response size limit.");
                    }
                    bytes.writeBytes(value);
                })), ignored -> bytes.toByteArray());
        var pending = http.sendAsync(outgoing, handler);
        HttpResponse<byte[]> response;
        try {
            // The deadline covers headers AND the complete bounded body, including stalled streams.
            response = pending.get(profile.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new Failure(Problem.TIMEOUT, "Model request timed out.");
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure(Problem.CONNECTION, "Model request was interrupted.");
        } catch (ExecutionException exception) {
            pending.cancel(true);
            if (oversized.get()) throw new Failure(Problem.OVERSIZED, "Model response exceeded the size limit.");
            if (exception.getCause() instanceof java.net.http.HttpTimeoutException) {
                throw new Failure(Problem.TIMEOUT, "Model request timed out.");
            }
            throw new Failure(Problem.CONNECTION, "Could not complete the Model connection.");
        }
        int status = response.statusCode();
        if (status != 200) {
            Problem problem = switch (status) {
                case 401, 403 -> Problem.AUTHENTICATION;
                case 404 -> Problem.NOT_FOUND;
                case 429 -> Problem.RATE_LIMIT;
                default -> Problem.HTTP;
            };
            throw new Failure(problem, "Model returned HTTP " + status + ".");
        }
        if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT)
                .split(";", 2)[0].strip().equals("application/json")) {
            throw new Failure(Problem.INVALID_OUTPUT, "Model response was not JSON.");
        }
        return response.body();
    }
}
