package texsuite;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Consented classification and batch/manual review; writes stay behind the shared apply gate. */
final class ModelReview {
    private final EditReview review;
    private final PrintWriter out;
    private final ModelClient client;
    private final String manualPrompt;
    private boolean repairUsed;

    ModelReview(EditReview review, PrintWriter out, ModelClient client) {
        this(review, out, client, "Rename this occurrence? [y/N]: ");
    }

    ModelReview(EditReview review, PrintWriter out, ModelClient client, String manualPrompt) {
        this.review = review;
        this.out = out;
        this.client = client;
        this.manualPrompt = manualPrompt;
    }

    Selection select(DocumentSnapshot snapshot, RenameRequest intent,
            List<RenameCandidateDiscovery.Occurrence> candidates, ContextRetriever.Retrieval context)
            throws IOException {
        repairUsed = false;
        Map<String, ModelClient.Decision> decisions = new HashMap<>();
        if (client != null && chooseAi()) {
            try {
                if (context.batches().isEmpty()) {
                    out.println("No bounded AI context is available; all occurrences are skipped.");
                    context.checkCurrent();
                    return new Selection(List.of(), client.decisionSource(), Map.of(), true);
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
                if (!review.yes("Continue with manual review? [y/N]: ")) return new Selection(List.of(), "manual", Map.of(), false);
            }
        }
        context.checkCurrent();
        if (!decisions.isEmpty()) {
            return new Selection(candidates.stream().filter(item -> decisions.containsKey(item.id())
                    && decisions.get(item.id()).action() == ModelClient.Action.REPLACE)
                    .map(RenameCandidateDiscovery.Occurrence::id).toList(),
                    client.decisionSource(), decisions, true);
        }
        return manual(snapshot, intent, candidates, context, decisions);
    }

    private Selection manual(DocumentSnapshot snapshot, RenameRequest intent,
            List<RenameCandidateDiscovery.Occurrence> candidates, ContextRetriever.Retrieval context,
            Map<String, ModelClient.Decision> decisions) throws IOException {
        context.checkCurrent();
        boolean assisted = !decisions.isEmpty();
        out.println(assisted ? "AI decisions are suggestions. Review every eligible occurrence."
                : "Manual review: accept only occurrences matching your stated meaning.");
        List<String> accepted = new ArrayList<>();
        for (var candidate : candidates) {
            var decision = decisions.get(candidate.id());
            String prompt = manualPrompt;

            if (decision != null) {
                String recommendation = switch (decision.action()) {
                    case KEEP -> "KEEP ORIGINAL";
                    case REPLACE -> "REPLACE OCCURRENCE";
                    case NEEDS_HUMAN_REVIEW -> "DECIDE MANUALLY";
                };
                out.println();
                out.printf("=== AI %s: %s ===%n", decision.action(), recommendation);
                out.println("Reason: " + DocumentInput.safeDisplay(decision.reason()));
                out.printf(Locale.ROOT, "Confidence: %.2f (model-reported)%n", decision.confidence());

                if (decision.action() == ModelClient.Action.KEEP) {
                    out.println("Replacement below applies only if you override KEEP.");
                }
                // Every yes still approves a replacement; Enter always preserves the source.
                prompt = switch (decision.action()) {
                    case KEEP -> "[AI KEEP] Override KEEP and replace this occurrence? [y/N, Enter keeps original]: ";
                    case REPLACE -> "[AI REPLACE] Accept replacement for this occurrence? [y/N]: ";
                    case NEEDS_HUMAN_REVIEW -> "[AI NEEDS_HUMAN_REVIEW] Replace this occurrence after manual review? [y/N]: ";
                };
            } else if (assisted) {
                out.println("Manual-only occurrence: bounded evidence was unavailable.");
            }

            review.printEdit(snapshot, new TextEditPlan.Edit(candidate.path(), candidate.startByte(),
                    candidate.endByte(), intent.source().getBytes(StandardCharsets.UTF_8),
                    candidate.line(), candidate.column()), intent.replacement());
            if (review.yes(prompt)) accepted.add(candidate.id());
        }
        context.checkCurrent();
        return new Selection(accepted, assisted ? client.decisionSource() : "manual", decisions, false);
    }

    Approval approve(DocumentSnapshot snapshot, RenameRequest intent,
            List<RenameCandidateDiscovery.Occurrence> candidates, ContextRetriever.Retrieval context,
            Selection selection, String operation, String region, int protectedCount,
            TexCompileGate compileGate) throws IOException {
        while (true) {
            var acceptedIds = new java.util.HashSet<>(selection.acceptedIds());
            List<TextEditPlan.Edit> edits = candidates.stream()
                    .filter(item -> acceptedIds.contains(item.id()))
                    .map(item -> new TextEditPlan.Edit(item.path(), item.startByte(), item.endByte(),
                            intent.source().getBytes(StandardCharsets.UTF_8), item.line(), item.column())).toList();
            if (edits.isEmpty() && !selection.batch()) return null;

            TexCompileGate resolved = edits.isEmpty() ? compileGate
                    : review.compilationMain(snapshot, edits, compileGate);
            compileGate = resolved;
            String provider = selection.decisionSource().equals("manual") ? null
                    : selection.decisionSource().replace("-reviewed", "");
            var history = new EditHistory.Intent(operation, intent.target().getFileName().toString(),
                    intent.scope().name(), region, intent.source(), intent.meaning(), intent.replacement(),
                    selection.decisionSource(), provider, provider == null ? null : client.model());
            TextEditPlan plan = new TextEditPlan(snapshot, edits, intent.replacement(), resolved, history);
            if (!selection.batch()) {
                review.printUnifiedDiff(snapshot, plan.preview());
                if (operation.equals("text-replacement")) {
                    for (var edit : edits) review.printEdit(snapshot, edit, intent.replacement());
                }
                out.printf("%d %s accepted. Structural checks passed.%n", edits.size(),
                        operation.equals("text-replacement") ? "replacement(s)" : "rename(s)");
                out.println("Review meaning and rendering before applying.");
                out.println(resolved.validationNotice());
                plan.validate();
                context.checkCurrent();
                if (!review.answer("Type apply to accept these changes, or Enter to cancel: ")
                        .equalsIgnoreCase("apply")) return null;

                plan.validate();
                context.checkCurrent();
                return new Approval(plan, selection);
            }
            long kept = selection.decisions().values().stream()
                    .filter(item -> item.action() == ModelClient.Action.KEEP).count();
            long uncertain = candidates.size() - edits.size() - kept;
            out.println("AI batch proposal: " + DocumentInput.safeDisplay(intent.source()) + " -> "
                    + DocumentInput.safeDisplay(intent.replacement()));
            out.printf("Scope: %s; region: %s; selected: %d; kept: %d; uncertain/skipped: %d; protected/excluded: %d.%n",
                    intent.scope(), region, edits.size(), kept, uncertain, protectedCount);
            var totals = new java.util.TreeMap<java.nio.file.Path, Integer>();
            for (var edit : edits) totals.merge(edit.path(), 1, Integer::sum);
            totals.forEach((path, count) -> out.printf("  %s: %d selected%n", DocumentInput.safeDisplay(path), count));
            out.println("AI KEEP and uncertain occurrences remain unchanged. Manual review can override eligible decisions.");
            if (!edits.isEmpty()) {
                plan.validate();
                out.println(resolved.validationNotice());
            }
            context.checkCurrent();
            switch (review.answer("Batch [diff/manual/apply/cancel (default)]: ").strip().toLowerCase(Locale.ROOT)) {
                case "diff" -> review.printUnifiedDiff(snapshot, plan.preview());
                case "manual" -> selection = manual(snapshot, intent, candidates, context, selection.decisions());
                case "apply" -> {
                    if (edits.isEmpty()) return null;

                    plan.validate();
                    context.checkCurrent();
                    return new Approval(plan, selection);
                }
                case "", "cancel" -> { return null; }
                default -> out.println("Choose diff, manual, apply or cancel.");
            }
        }
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

    record Approval(TextEditPlan plan, Selection selection) { }

    record Selection(List<String> acceptedIds, String decisionSource,
            Map<String, ModelClient.Decision> decisions, boolean batch) {
        Selection {
            acceptedIds = List.copyOf(acceptedIds);
            decisions = Map.copyOf(decisions);
        }
    }
}
