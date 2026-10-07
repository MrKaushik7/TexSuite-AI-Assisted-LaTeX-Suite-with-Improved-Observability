package texsuite;

import java.util.List;
import java.util.HashSet;

/** Classification only: callers retain source identity, edit eligibility and approval. */
interface ModelClient {
    static ModelClient create(ModelSettings.Profile profile) {
        return switch (profile.provider()) {
            case OPENAI -> new OpenAiClient(profile);
            case OPENROUTER -> new OpenRouterClient(profile);
            case GEMINI -> new GeminiClient(profile);
        };
    }

    default String decisionSource() { return "ai-reviewed"; }

    String destination();

    String model();

    String preview(Request request) throws Failure;

    Result classify(Request request) throws Failure;

    record Request(String source, String meaning, String replacement, List<Candidate> candidates,
            List<Excerpt> excerpts, int maxOutputTokens, String repairOutput) {
        public Request {
            candidates = List.copyOf(candidates);
            excerpts = List.copyOf(excerpts);
        }

        Request repair(String output) {
            return new Request(source, meaning, replacement, candidates, List.of(), maxOutputTokens, output);
        }
    }

    record Candidate(String id, String excerptId, int startByte, int endByte) { }

    record Excerpt(String id, List<String> roles, String text) {
        public Excerpt {
            roles = List.copyOf(roles);
        }
    }

    enum Action { REPLACE, KEEP, NEEDS_HUMAN_REVIEW }

    record Decision(String id, Action action, String reason, double confidence) { }

    record Result(List<Decision> decisions) {
        public Result {
            decisions = List.copyOf(decisions);
        }

        void validate(Request request) throws Failure {
            var expected = new HashSet<>(request.candidates().stream().map(Candidate::id).toList());
            var actual = new HashSet<String>();
            for (Decision decision : decisions) {
                if (!expected.contains(decision.id()) || !actual.add(decision.id())
                        || decision.action() == null || !Double.isFinite(decision.confidence())
                        || decision.confidence() < 0 || decision.confidence() > 1
                        || decision.reason() == null || decision.reason().isBlank()
                        || decision.reason().codePointCount(0, decision.reason().length()) > 240
                        || !EditorApplication.safeText(decision.reason())) {
                    throw new Failure(Problem.INVALID_OUTPUT, "Invalid or foreign model decision.");
                }
            }
            if (!actual.equals(expected)) {
                throw new Failure(Problem.INVALID_OUTPUT, "Model did not classify every supplied occurrence.");
            }
        }
    }

    enum Problem { CONFIGURATION, MISSING_KEY, AUTHENTICATION, NOT_FOUND, RATE_LIMIT,
        HTTP, TIMEOUT, CONNECTION, OVERSIZED, REFUSAL, INCOMPLETE, INVALID_OUTPUT }

    final class Failure extends Exception {
        private final Problem problem;
        private final String repairOutput;

        Failure(Problem problem, String message) {
            this(problem, message, null);
        }

        Failure(Problem problem, String message, String repairOutput) {
            super(message);
            this.problem = problem;
            this.repairOutput = repairOutput;
        }

        Problem problem() {
            return problem;
        }

        String repairOutput() {
            return repairOutput;
        }
    }
}
