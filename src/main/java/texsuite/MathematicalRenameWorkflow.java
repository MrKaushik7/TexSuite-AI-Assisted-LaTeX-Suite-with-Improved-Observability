package texsuite;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import picocli.CommandLine;
import texsuite.EditReview.Cancel;

/** Manual meaning decisions stay separate from the shared compile-and-apply safeguards. */
final class MathematicalRenameWorkflow {
    private static final int MAX_REVIEW_OCCURRENCES = 200;

    private final EditReview review;
    private final PrintWriter out;
    private final PrintWriter err;
    private final boolean debug;
    private final TexCompileGate compileGate;
    private boolean sessionEnded;

    MathematicalRenameWorkflow(BufferedReader input, PrintWriter out, PrintWriter err,
            boolean debug, TexCompileGate compileGate) {
        this.review = new EditReview(input, out);
        this.out = out;
        this.err = err;
        this.debug = debug;
        this.compileGate = compileGate;
    }

    boolean sessionEnded() {
        return sessionEnded;
    }

    int run(RenameRequest request) {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    return attemptRename(request);
                } catch (StaleSourceException exception) {
                    if (attempt == 2) throw exception;

                    out.println("Saved source changed; reloading and preparing a new preview.");
                }
            }
            throw new IOException("Saved source kept changing; no edit was applied.");
        } catch (Cancel | EOFException exception) {
            sessionEnded = true;
            out.println("Cancelled without source changes.");
            return CommandLine.ExitCode.OK;
        } catch (IOException | DocumentLoader.LoadException exception) {
            err.println("Could not complete mathematical rename: "
                    + DocumentInput.safeDisplay(exception.getMessage()));
            return CommandLine.ExitCode.USAGE;
        }
    }

    private int attemptRename(RenameRequest request) throws IOException, DocumentLoader.LoadException {
        DocumentSnapshot snapshot = loadRenameSnapshot(request);
        printRenameRequest(request, snapshot);
        if (request.scope() == RenameRequest.Scope.PROJECT) {
            out.println("Project scope covers only the loaded static include closure.");
            if (!snapshot.complete()) {
                out.println("Static include closure is incomplete; inventory stopped.");
                printDiagnostics(snapshot);
                return CommandLine.ExitCode.OK;
            }
        }
        if (!snapshot.diagnostics().isEmpty()) printDiagnostics(snapshot);

        var inventory = new RenameCandidateDiscovery().discover(snapshot, request);
        printRenameInventory(request, snapshot, inventory);
        List<RenameCandidateDiscovery.Occurrence> candidates = inventory.occurrences().stream()
                .filter(item -> item.status() == RenameCandidateDiscovery.Status.CANDIDATE).toList();
        if (candidates.isEmpty()) {
            out.println("No safe mathematical occurrences are available for selection; no source changes.");
            return CommandLine.ExitCode.OK;
        }
        if (candidates.size() > MAX_REVIEW_OCCURRENCES) {
            out.println("Too many occurrences to review at once; choose a smaller file scope.");
            return CommandLine.ExitCode.OK;
        }
        ContextRetriever.Retrieval context = new ContextRetriever().retrieve(snapshot, request, inventory);
        context.checkCurrent();
        printContext(context);
        out.println("Manual review: accept only occurrences matching your stated meaning.");
        List<String> accepted = new ArrayList<>();
        for (var candidate : candidates) {
            review.printEdit(snapshot, new TextEditPlan.Edit(candidate.path(), candidate.startByte(),
                    candidate.endByte(), request.source().getBytes(StandardCharsets.UTF_8),
                    candidate.line(), candidate.column()), request.replacement());
            if (review.yes("Rename this occurrence? [y/N]: ")) accepted.add(candidate.id());
        }
        if (accepted.isEmpty()) {
            out.println("No renames accepted; no source changes.");
            return CommandLine.ExitCode.OK;
        }
        RenamePlan record = RenamePlan.create(snapshot, request, accepted);
        List<TextEditPlan.Edit> edits = record.editsFor(snapshot);
        TexCompileGate resolved = review.compilationMain(snapshot, edits, compileGate);
        TextEditPlan plan = new TextEditPlan(snapshot, edits, request.replacement(), resolved);
        review.printUnifiedDiff(snapshot, plan.preview());
        out.printf("%d rename(s) accepted. Structural checks passed.%n", edits.size());
        out.println("Review meaning and rendering before applying.");
        out.println(resolved.validationNotice());
        plan.validate();
        context.checkCurrent();
        if (!review.answer("Type apply to accept these changes, or Enter to cancel: ")
                .equalsIgnoreCase("apply")) {
            out.println("Cancelled without source changes.");
            return CommandLine.ExitCode.OK;
        }
        plan.validate();
        context.checkCurrent();
        Path savedPlan = record.save(snapshot);
        out.println("Approved intent saved: " + DocumentInput.safeDisplay(savedPlan));
        Path backup = plan.apply(context::checkCurrent);
        out.printf("Applied %d rename(s). Backup: %s%n", plan.size(), DocumentInput.safeDisplay(backup));
        return CommandLine.ExitCode.OK;
    }

    private void printContext(ContextRetriever.Retrieval context) {
        out.printf("Context retrieval: %d batch(es), %d source character(s), %d manual-only occurrence(s).%n",
                context.batches().size(), context.sourceCharacters(), context.reviews().size());
        out.println("Context reads follow static includes; they do not expand edit scope.");

        var displayed = new HashSet<String>();
        for (var batch : context.batches()) {
            for (var slice : batch.slices()) {
                if (!displayed.add(slice.id())) continue;

                out.printf("  Context %s:%d:%d [%s]%n", DocumentInput.safeDisplay(slice.path()),
                        slice.line(), slice.column(), String.join(", ", slice.roles()));
                slice.text().lines().forEach(line -> out.println("    " + DocumentInput.safeDisplay(line)));
            }
        }
        for (var item : context.reviews()) {
            out.printf("  Manual context review %s: %s%n", item.candidateId(),
                    DocumentInput.safeDisplay(item.reason()));
        }
    }

    private DocumentSnapshot loadRenameSnapshot(RenameRequest request)
            throws IOException, DocumentLoader.LoadException {
        if (!request.target().toRealPath().equals(request.target())) {
            throw new DocumentLoader.LoadException(
                    "Selected file identity changed; select the file again.");
        }
        DocumentLoader loader = new DocumentLoader();
        DocumentSnapshot snapshot = request.scope() == RenameRequest.Scope.FILE
                ? loader.loadFile(request.target()) : loader.load(request.target());
        if (!snapshot.root().resolve(snapshot.main()).equals(request.target())) {
            throw new DocumentLoader.LoadException(
                    "Selected file identity changed; select the file again.");
        }
        return snapshot;
    }

    private void printRenameRequest(RenameRequest request, DocumentSnapshot snapshot) {
        out.printf("Rename request: %s -> %s; meaning: %s%n",
                DocumentInput.safeDisplay(request.source()),
                DocumentInput.safeDisplay(request.replacement()),
                DocumentInput.safeDisplay(request.meaning()));

        out.printf("Scope: %s; entry: %s; root: %s%n", request.scope(),
                DocumentInput.safeDisplay(request.target()),
                DocumentInput.safeDisplay(snapshot.root()));
    }

    private void printRenameInventory(RenameRequest request, DocumentSnapshot snapshot,
            RenameCandidateDiscovery.Result result) {
        out.printf("Occurrence inventory: %d file(s), %d candidate(s), %d review, %d excluded.%n",
                result.fileCount(), result.count(RenameCandidateDiscovery.Status.CANDIDATE),
                result.count(RenameCandidateDiscovery.Status.REVIEW),
                result.count(RenameCandidateDiscovery.Status.EXCLUDED));

        out.println("Candidates are lexical findings, not approved edits.");
        if (result.count(RenameCandidateDiscovery.Status.CANDIDATE) == 0
                && result.count(RenameCandidateDiscovery.Status.REVIEW) == 0) {
            out.println(result.occurrences().isEmpty()
                    ? "No literal matches were found in the chosen scope."
                    : "Matches are outside mathematical rename. Use Replace text "
                    + "for a general literal change.");
        }
        out.println("Uncertain and protected occurrences cannot be selected.");
        if (debug) {
            out.printf("Replacement: \"%s\" (proposed literal LaTeX source)%n",
                    DocumentInput.safeDisplay(request.replacement()).replace("\"", "\\\""));

            out.println("Matched source is marked with ⟦ ⟧; excerpts show the original text.");
            int excludedShown = 0;
            Path displayedFile = null;
            byte[] displayedBytes = null;

            for (RenameCandidateDiscovery.Occurrence occurrence : result.occurrences()) {
                if (occurrence.status() == RenameCandidateDiscovery.Status.EXCLUDED) {
                    if (excludedShown >= 20) {
                        continue;
                    }
                    excludedShown++;
                }
                if (!occurrence.path().equals(displayedFile)) {
                    displayedFile = occurrence.path();
                    displayedBytes = snapshot.files().get(displayedFile).bytes();
                }

                out.printf("  %s: %s (%s:%d:%d)%n",
                        occurrence.status(), SourceExcerpt.marked(displayedBytes, occurrence.startByte(), occurrence.endByte()),
                        DocumentInput.safeDisplay(occurrence.path()), occurrence.line(),
                        occurrence.column());

                out.printf("    REASON: %s%n", occurrence.reason());

                out.printf("    id=%s bytes[%d,%d)%n", occurrence.id(),
                        occurrence.startByte(), occurrence.endByte());
            }

            if (result.count(RenameCandidateDiscovery.Status.EXCLUDED) > excludedShown) {
                out.printf("  %d excluded occurrence(s) omitted from debug detail.%n",
                        result.count(RenameCandidateDiscovery.Status.EXCLUDED) - excludedShown);
            }
        }

    }

    private void printDiagnostics(DocumentSnapshot snapshot) {
        out.printf("%d source diagnostic(s) need review.%n", snapshot.diagnostics().size());
        snapshot.diagnostics().stream().limit(5).forEach(diagnostic ->
                out.printf("  %s at %s%n", diagnostic.code(),
                        TexSuiteCli.diagnosticLocation(snapshot, diagnostic)));
        if (snapshot.diagnostics().size() > 5) {
            out.printf("  ... %d more diagnostic(s)%n", snapshot.diagnostics().size() - 5);
        }
    }

}
