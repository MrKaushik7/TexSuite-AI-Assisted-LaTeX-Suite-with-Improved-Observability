package texsuite;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import picocli.CommandLine;
import texsuite.DocumentSnapshot.SourceContext;
import texsuite.EditReview.Cancel;

final class TextReplacementWorkflow {
    private static final int MAX_PREVIEW_EDITS = 200;

    private final EditReview review;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Path selectedFile;
    private final boolean debug;
    private final TexCompileGate compileGate;
    private final ModelReview modelReview;
    private final boolean aiAvailable;

    TextReplacementWorkflow(BufferedReader input, PrintWriter out, PrintWriter err,
            Path selectedFile, boolean debug, TexCompileGate compileGate) {
        this(input, out, err, selectedFile, debug, compileGate, null);
    }

    TextReplacementWorkflow(BufferedReader input, PrintWriter out, PrintWriter err,
            Path selectedFile, boolean debug, TexCompileGate compileGate, ModelClient client) {
        this.review = new EditReview(input, out);
        this.out = out;
        this.err = err;
        this.selectedFile = selectedFile;
        this.debug = debug;
        this.compileGate = compileGate;
        this.modelReview = new ModelReview(review, out, client, "Replace this occurrence? [y/N]: ");
        this.aiAvailable = client != null;
    }

    int run() {
        return run(null);
    }

    int run(RenameRequest routed) {
        try {
            Request request = routed == null ? collectRequest()
                    : new Request(routed.source(), routed.replacement(), routed.scope(),
                            new Region(RegionKind.SOURCE, 0, 0), routed.meaning());
            if (!selectedFile.toRealPath().equals(selectedFile)) {
                throw new IOException("Selected file identity changed; select it again.");
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    return attemptReplacement(request);
                } catch (StaleSourceException exception) {
                    if (attempt == 2) throw exception;

                    out.println("Saved source changed; reloading and preparing a new preview.");
                }
            }
            throw new IOException("Saved source kept changing; no edit was applied.");
        } catch (EditReview.Back exception) {
            out.println("Back to menu; no source changes.");
            return CommandLine.ExitCode.OK;
        } catch (Cancel | EOFException exception) {
            out.println("Cancelled without source changes.");
            return CommandLine.ExitCode.OK;
        } catch (IOException | DocumentLoader.LoadException exception) {
            err.println("Could not complete text replacement: "
                    + DocumentInput.safeDisplay(exception.getMessage()));
            return CommandLine.ExitCode.USAGE;
        }
    }

    private Request collectRequest() throws IOException, Cancel {
        String source = literal("Find literal text: ", "source");
        String replacement;
        while (true) {
            replacement = literal("Replace with: ", "replacement");
            if (!replacement.equals(source)) break;

            out.println("replacement must differ from the source string");
        }
        RenameRequest.Scope scope = scope();
        Region region = region(scope);
        String meaning = "all eligible literal matches";
        if (aiAvailable) {
            while (true) {
                meaning = review.answer("Meaning/purpose for AI selection (Enter = all eligible literal matches): ");
                if (meaning.isBlank()) meaning = "all eligible literal matches";
                try {
                    RenameRequest.validateLiteral(meaning, "meaning/purpose");
                    break;
                } catch (IllegalArgumentException exception) {
                    out.println(DocumentInput.safeDisplay(exception.getMessage()));
                }
            }
        }
        return new Request(source, replacement, scope, region, meaning);
    }

    private DocumentSnapshot loadSnapshot(RenameRequest.Scope scope)
            throws IOException, DocumentLoader.LoadException {
        DocumentLoader loader = new DocumentLoader();
        DocumentSnapshot snapshot = scope == RenameRequest.Scope.FILE
                ? loader.loadFile(selectedFile) : loader.load(selectedFile);
        if (!snapshot.root().resolve(snapshot.main()).equals(selectedFile)) {
            throw new IOException("Selected file identity changed; select it again.");
        }
        return snapshot;
    }

    private int attemptReplacement(Request request)
            throws IOException, DocumentLoader.LoadException, Cancel {
        String source = request.source();
        String replacement = request.replacement();
        RenameRequest.Scope scope = request.scope();
        Region region = request.region();

        DocumentSnapshot snapshot = loadSnapshot(scope);
        if (scope == RenameRequest.Scope.PROJECT && !snapshot.complete()) {
            out.println("Static include closure is incomplete; no text edits proposed.");
            return CommandLine.ExitCode.OK;
        }
        Selection selection = select(snapshot, source, region);
        printSelection(request, selection);
        if (region.kind() == RegionKind.DOCUMENT_TEXT
                && selection.skipReasons().containsKey("outside document text")) {
            out.println("Document text excludes the preamble; LaTeX source includes eligible literal values there.");
            if (review.yes("Try these matches in the LaTeX source region? [y/N]: ")) {
                region = new Region(RegionKind.SOURCE, 0, 0);
                selection = select(snapshot, source, region);
                printSelection(new Request(source, replacement, scope, region, request.meaning()), selection);
            }
        }
        if (selection.matches() == 0) {
            out.println("No literal matches were found in the chosen file scope.");
            return CommandLine.ExitCode.OK;
        }
        if (selection.edits().isEmpty()) {
            out.println("No safe text changes are available in the selected source region. "
                    + "The reasons above require manual source review; no changes were made.");
            return CommandLine.ExitCode.OK;
        }
        if (selection.edits().size() > MAX_PREVIEW_EDITS) {
            out.println("Too many proposed edits to review at once; choose a line range.");
            return CommandLine.ExitCode.OK;
        }
        var intent = new RenameRequest(selectedFile, source, request.meaning(), replacement, scope);
        List<RenameCandidateDiscovery.Occurrence> candidates = selection.edits().stream().map(edit ->
                new RenameCandidateDiscovery.Occurrence(RenameCandidateDiscovery.id(edit.path(),
                        snapshot.files().get(edit.path()).hash(), edit.start(), edit.end(), source),
                        edit.path(), snapshot.files().get(edit.path()).hash(), edit.start(), edit.end(),
                        edit.line(), edit.column(), RenameCandidateDiscovery.Status.CANDIDATE,
                        "eligible literal text/source value")).toList();
        var context = new ContextRetriever().retrieve(snapshot, intent,
                new RenameCandidateDiscovery.Result(candidates, snapshot.files().size()));
        var choice = modelReview.select(snapshot, intent, candidates, context);
        var approval = modelReview.approve(snapshot, intent, candidates, context, choice,
                "text-replacement", region.kind().name()
                        + (region.kind() == RegionKind.LINE_RANGE ? ":" + region.firstLine() + "-" + region.lastLine() : ""),
                selection.skipped(), compileGate);
        if (approval == null) {
            out.println("No replacements applied; cancelled or no occurrences accepted.");
            return CommandLine.ExitCode.OK;
        }
        TextEditPlan plan = approval.plan();
        Path backup = plan.apply(context::checkCurrent);
        out.printf("Applied %d replacement(s). Backup: %s%n", plan.size(),
                DocumentInput.safeDisplay(backup));
        out.println("Exact edit history: " + DocumentInput.safeDisplay(backup.resolve("changes.json")));
        return CommandLine.ExitCode.OK;
    }

    private void printSelection(Request request, Selection selection) {
        if (debug) {
            for (Skipped detail : selection.details()) {
                out.printf("  SKIPPED %s:%d:%d: %s%n", DocumentInput.safeDisplay(detail.path()),
                        detail.line(), detail.column(), detail.reason());
            }
            if (selection.skipped() > 20) {
                out.printf("  %d further skipped match(es).%n", selection.skipped() - 20);
            }
        }
        out.printf("Replace text: %s -> %s; %d match(es), %d proposed, %d skipped.%n",
                DocumentInput.safeDisplay(request.source()), DocumentInput.safeDisplay(request.replacement()),
                selection.matches(), selection.edits().size(), selection.skipped());
        if (!selection.skipReasons().isEmpty()) {
            out.println("Skipped by reason: " + selection.skipReasons());
        }
    }

    private static Selection select(DocumentSnapshot snapshot, String source, Region region) {
        List<TextEditPlan.Edit> edits = new ArrayList<>();
        List<Skipped> details = new ArrayList<>();
        int matches = 0;
        int skipped = 0;
        Map<String, Integer> skipReasons = new TreeMap<>();
        var reachable = snapshot.definitelyReachable();
        for (Map.Entry<Path, DocumentSnapshot.SourceFile> entry : snapshot.files().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            byte[] bytes = entry.getValue().bytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            TexSourceScanner scanner = new TexSourceScanner(text);
            TexSourceScanner.Scan scan = scanner.scanFor(source);
            List<TexSourceScanner.RawOccurrence> found = scan.occurrences();
            matches += found.size();
            for (int index = 0; index < found.size(); index++) {
                TexSourceScanner.RawOccurrence raw = found.get(index);
                int startChar = raw.charIndex();
                int endChar = startChar + source.length();
                String reason = skipReason(scanner, scan, index, source.length(), region,
                        reachable.contains(entry.getKey())).orElse(null);
                if (reason != null) {
                    skipped++;
                    skipReasons.merge(reason, 1, Integer::sum);
                    if (details.size() < 20) {
                        details.add(new Skipped(entry.getKey(), scanner.line(startChar),
                                scanner.column(startChar), reason));
                    }
                    continue;
                }
                int start = scanner.byteOffset(startChar);
                int end = scanner.byteOffset(endChar);
                edits.add(new TextEditPlan.Edit(entry.getKey(), start, end,
                        Arrays.copyOfRange(bytes, start, end), scanner.line(startChar),
                        scanner.column(startChar)));
            }
        }
        edits.sort(Comparator.comparing((TextEditPlan.Edit edit) -> edit.path().toString())
                .thenComparingInt(TextEditPlan.Edit::start));
        return new Selection(List.copyOf(edits), matches, skipped, Map.copyOf(skipReasons),
                List.copyOf(details));
    }

    private static Optional<String> skipReason(TexSourceScanner scanner,
            TexSourceScanner.Scan scan, int index, int length, Region region, boolean reachable) {
        TexSourceScanner.RawOccurrence raw = scan.occurrences().get(index);
        int startChar = raw.charIndex();
        int endChar = startChar + length;
        String reason = null;
        if (raw.reason() != SourceContext.PROSE
                && !(region.kind() != RegionKind.DOCUMENT_TEXT
                        && (raw.reason() == SourceContext.TEXT_ARGUMENT || raw.reason() == SourceContext.MATH))) {
            reason = "protected or non-text TeX context";
        } else if (region.kind() == RegionKind.DOCUMENT_TEXT
                && !scanner.withinDocument(startChar, endChar)) {
            reason = "outside document text";
        } else if (region.kind() == RegionKind.LINE_RANGE
                && (scanner.line(startChar) < region.firstLine()
                || scanner.line(endChar - 1) > region.lastLine())) {
            reason = "outside selected line range";
        } else if (raw.conditional()) {
            reason = "inside a TeX conditional; active branch is not evaluated";
        } else if (!reachable) {
            reason = "file is only conditionally reachable from the entry document";
        } else if (scan.overlaps(index, length)) {
            reason = "overlapping literal matches";
        } else if (scanner.partialGroup(startChar, endChar)) {
            reason = "match crosses a brace-group boundary";
        } else if (!scan.problems().isEmpty()) {
            var problem = scan.problems().getFirst();
            reason = "file needs structural review: " + problem.code();
        }
        return Optional.ofNullable(reason);
    }

    private String literal(String prompt, String label) throws IOException, Cancel {
        while (true) {
            String value = review.answer(prompt);
            try {
                RenameRequest.validateLiteral(value, label);
                return value;
            } catch (IllegalArgumentException exception) {
                out.println(DocumentInput.safeDisplay(exception.getMessage()));
            }
        }
    }

    private RenameRequest.Scope scope() throws IOException, Cancel {
        while (true) {
            switch (review.answer("Scope [1=file (default), 2=project]: ")) {
                case "", "1" -> { return RenameRequest.Scope.FILE; }
                case "2" -> { return RenameRequest.Scope.PROJECT; }
                default -> out.println("Choose 1 for this file or 2 for its static include closure.");
            }
        }
    }

    private Region region(RenameRequest.Scope scope) throws IOException, Cancel {
        out.println("Document text: prose inside the document. LaTeX source: eligible literal values, including the preamble.");
        out.println("Comments, verbatim, definitions, command names and metadata remain protected in every region.");
        while (true) {
            String choice = review.answer("Region [1=document text (default), "
                    + "2=LaTeX source, 3=line range]: ");
            if (choice.isEmpty() || choice.equals("1")) {
                return new Region(RegionKind.DOCUMENT_TEXT, 0, 0);
            }
            if (choice.equals("2")) return new Region(RegionKind.SOURCE, 0, 0);
            if (choice.equals("3")) {
                if (scope != RenameRequest.Scope.FILE) {
                    out.println("A line range requires file scope.");
                    continue;
                }
                try {
                    int first = Integer.parseInt(review.answer("First line: "));
                    int last = Integer.parseInt(review.answer("Last line: "));
                    if (first > 0 && last >= first) {
                        return new Region(RegionKind.LINE_RANGE, first, last);
                    }
                } catch (NumberFormatException exception) {
                    // The next prompt explains the accepted range.
                }
                out.println("Enter positive line numbers in increasing order.");
                continue;
            }
            out.println("Choose 1, 2, or 3.");
        }
    }

    private enum RegionKind { DOCUMENT_TEXT, SOURCE, LINE_RANGE }

    private record Region(RegionKind kind, int firstLine, int lastLine) { }

    private record Request(String source, String replacement, RenameRequest.Scope scope,
            Region region, String meaning) { }

    private record Skipped(Path path, int line, int column, String reason) { }

    private record Selection(List<TextEditPlan.Edit> edits, int matches, int skipped,
            Map<String, Integer> skipReasons, List<Skipped> details) { }

}
