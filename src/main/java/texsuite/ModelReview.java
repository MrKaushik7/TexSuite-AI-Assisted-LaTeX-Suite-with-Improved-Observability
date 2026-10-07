package texsuite;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Consented classification followed by explicit human decisions; never authorizes writes. */
final class ModelReview {
    private final EditReview review;
    private final PrintWriter out;
    private final ModelClient client;
    private boolean repairUsed;

    ModelReview(EditReview review, PrintWriter out, ModelClient client) {
        this.review = review;
        this.out = out;
        this.client = client;
    }

    Selection select(DocumentSnapshot snapshot, RenameRequest intent,
            List<RenameCandidateDiscovery.Occurrence> candidates, ContextRetriever.Retrieval context)
            throws IOException {
        Map<String, ModelClient.Decision> decisions = new HashMap<>();
        if (client != null && chooseAi()) {
            try {
                if (context.batches().isEmpty()) {
                    throw new ModelClient.Failure(ModelClient.Problem.CONFIGURATION,
                            "No bounded context is available for AI classification.");
                }
                for (var batch : context.batches()) {
                    ModelClient.Request request = request(intent, candidates, batch);
                    ModelClient.Result result = classify(request, context);
                    result.validate(request);
                    for (var decision : result.decisions()) decisions.put(decision.id(), decision);
                }
            } catch (ModelClient.Failure exception) {
                decisions.clear();
                out.println("AI classification stopped: " + exception.problem() + ". "
                        + DocumentInput.safeDisplay(exception.getMessage()));
                out.println("All AI decisions for this operation were discarded.");
                if (!review.yes("Continue with manual review? [y/N]: ")) return new Selection(List.of(), "manual");
            }
        }
        context.checkCurrent();
        boolean assisted = !decisions.isEmpty();
        out.println(assisted ? "AI decisions are suggestions. Review every eligible occurrence."
                : "Manual review: accept only occurrences matching your stated meaning.");
        List<String> accepted = new ArrayList<>();
        for (var candidate : candidates) {
            var decision = decisions.get(candidate.id());
            if (decision != null) {
                out.printf(Locale.ROOT, "AI %s; confidence %.2f (model-reported): %s%n",
                        decision.action(), decision.confidence(), DocumentInput.safeDisplay(decision.reason()));
            } else if (assisted) {
                out.println("Manual-only occurrence: bounded evidence was unavailable.");
            }
            review.printEdit(snapshot, new TextEditPlan.Edit(candidate.path(), candidate.startByte(),
                    candidate.endByte(), intent.source().getBytes(StandardCharsets.UTF_8),
                    candidate.line(), candidate.column()), intent.replacement());
            if (review.yes("Rename this occurrence? [y/N]: ")) accepted.add(candidate.id());
        }
        context.checkCurrent();
        return new Selection(accepted, assisted ? client.decisionSource() : "manual");
    }

    private boolean chooseAi() throws IOException {
        while (true) {
            switch (review.answer("Selection [1=AI, 2=manual (default)]: ").strip()) {
                case "1" -> { return true; }
                case "2", "" -> { return false; }
                default -> out.println("Choose 1 or 2.");
            }
        }
    }

    private ModelClient.Result classify(ModelClient.Request request, ContextRetriever.Retrieval context)
            throws IOException, ModelClient.Failure {
        try {
            return send(request, context);
        } catch (ModelClient.Failure exception) {
            if (exception.repairOutput() == null || repairUsed) throw exception;

            out.println("The completed output failed its schema. One repair can send only allowed IDs"
                    + " and the previous output; that output may repeat source text.");
            if (!review.yes("Prepare one schema repair? [y/N]: ")) throw exception;

            repairUsed = true;
            return send(request.repair(exception.repairOutput()), context);
        }
    }

    private ModelClient.Result send(ModelClient.Request request, ContextRetriever.Retrieval context)
            throws IOException, ModelClient.Failure {
        context.checkCurrent();
        if (!consent(request)) {
            throw new ModelClient.Failure(ModelClient.Problem.CONFIGURATION, "Remote request was not approved.");
        }
        context.checkCurrent();
        ModelClient.Result result = client.classify(request);
        context.checkCurrent();
        result.validate(request);
        return result;
    }

    private boolean consent(ModelClient.Request request) throws IOException, ModelClient.Failure {
        String payload = client.preview(request);
        out.println("Destination: " + DocumentInput.safeDisplay(client.destination()));
        out.println("Model: " + DocumentInput.safeDisplay(client.model()));
        out.println("This request may incur API charges. Exact outgoing JSON (key excluded):");
        payload.lines().forEach(line -> out.println(DocumentInput.safeDisplay(line)));
        return review.yes("Send this request? [y/N]: ");
    }

    void testProvider() throws IOException {
        // Fixed synthetic evidence: no file paths or bytes from the selected document.
        String id = "0".repeat(64);
        ModelClient.Request request = new ModelClient.Request("x", "synthetic test symbol", "y",
                List.of(new ModelClient.Candidate(id, "test", 0, 1)),
                List.of(new ModelClient.Excerpt("test", List.of("synthetic provider test"), "x")), 1_500, null);
        out.println("Provider test uses fixed synthetic text; no document source is sent.");
        try {
            if (!consent(request)) {
                out.println("Provider test cancelled.");
                return;
            }
            ModelClient.Result result = client.classify(request);
            result.validate(request);
            out.println("Provider test passed: completed schema and occurrence ID validated.");
        } catch (ModelClient.Failure exception) {
            out.println("Provider test failed: " + exception.problem() + ". "
                    + DocumentInput.safeDisplay(exception.getMessage()));
        }
    }

    private static ModelClient.Request request(RenameRequest intent,
            List<RenameCandidateDiscovery.Occurrence> inventory, ContextRetriever.Batch batch) throws IOException {
        List<ModelClient.Candidate> candidates = new ArrayList<>();
        for (String id : batch.candidateIds()) {
            var occurrence = inventory.stream().filter(item -> item.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IOException("Unknown context occurrence."));
            var slice = batch.slices().stream().filter(item -> item.path().equals(occurrence.path())
                    && item.startByte() <= occurrence.startByte() && item.endByte() >= occurrence.endByte())
                    .findFirst().orElseThrow(() -> new IOException("Exact occurrence context is unavailable."));
            candidates.add(new ModelClient.Candidate(id, slice.id(), occurrence.startByte() - slice.startByte(),
                    occurrence.endByte() - slice.startByte()));
        }
        return new ModelClient.Request(intent.source(), intent.meaning(), intent.replacement(), candidates,
                batch.slices().stream().map(item -> new ModelClient.Excerpt(item.id(), item.roles(), item.text()))
                        .toList(), batch.maxOutputTokens(), null);
    }

    record Selection(List<String> acceptedIds, String decisionSource) {
        Selection {
            acceptedIds = List.copyOf(acceptedIds);
        }
    }
}
