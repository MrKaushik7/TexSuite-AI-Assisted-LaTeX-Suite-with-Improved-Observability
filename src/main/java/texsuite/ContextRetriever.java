package texsuite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Retrieves source evidence; candidate discovery remains the authority for edit eligibility. */
final class ContextRetriever {
    static final int MAX_CANDIDATES = 8;
    static final int MAX_SOURCE_CHARACTERS = 24_000;
    static final int MAX_OUTPUT_TOKENS = 1_500;

    private static final Pattern BLANK_LINE = Pattern.compile(
            "(?:\\r\\n|\\r(?!\\n)|(?<!\\r)\\n)[\\t ]*(?:\\r\\n|\\r(?!\\n)|(?<!\\r)\\n)");
    private static final Pattern COMMAND = Pattern.compile("\\\\[A-Za-z@]+");
    private static final Set<String> THEOREMS = Set.of("theorem", "lemma", "proposition",
            "corollary", "claim", "remark", "example");

    Retrieval retrieve(DocumentSnapshot edits, RenameRequest request,
            RenameCandidateDiscovery.Result inventory) throws IOException {
        List<RenameCandidateDiscovery.Occurrence> candidates = inventory.occurrences().stream()
                .filter(item -> item.status() == RenameCandidateDiscovery.Status.CANDIDATE)
                .sorted(Comparator.comparing((RenameCandidateDiscovery.Occurrence item) -> item.path().toString())
                        .thenComparingInt(RenameCandidateDiscovery.Occurrence::startByte)).toList();
        if (!new DocumentLoader().isCurrent(edits)) {
            throw new StaleSourceException("Saved source changed before context retrieval; review again.");
        }
        if (candidates.isEmpty()) return new Retrieval(edits, null, List.of(), List.of());

        DocumentSnapshot context;
        try {
            context = new DocumentLoader().load(edits.root().resolve(edits.main()));
        } catch (DocumentLoader.LoadException exception) {
            return manualOnly(edits, null, candidates, "Static context unavailable: " + exception.getMessage());
        }
        if (!context.complete()) {
            return manualOnly(edits, context, candidates, "Static source context is incomplete.");
        }

        Map<Path, IndexedSource> sources = new TreeMap<>();
        for (var entry : context.files().entrySet()) {
            sources.put(entry.getKey(), index(entry.getKey(), entry.getValue()));
        }
        if (sources.values().stream().anyMatch(item -> !item.scan().problems().isEmpty())) {
            return manualOnly(edits, context, candidates, "Source context needs structural review.");
        }

        List<Batch> batches = new ArrayList<>();
        List<Review> reviews = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<SelectionSpan> pending = new ArrayList<>();
        for (var candidate : candidates) {
            IndexedSource source = sources.get(candidate.path());
            if (source == null || !source.hash().equals(candidate.sourceHash())) {
                throw new StaleSourceException("Saved source changed during context retrieval; review again.");
            }
            if (!source.text().substring(source.charIndex(candidate.startByte()),
                    source.charIndex(candidate.endByte())).equals(request.source())) {
                throw new IOException("Candidate context does not match the requested source.");
            }
            List<SelectionSpan> selected = candidateContext(source, candidate, sources,
                    context.definitelyReachable());
            List<Slice> slices = slices(selected, sources);
            if (slices.isEmpty() || characters(slices) > MAX_SOURCE_CHARACTERS) {
                reviews.add(new Review(candidate.id(), "Complete context exceeds the source budget or is unavailable."));
                continue;
            }

            List<SelectionSpan> combined = new ArrayList<>(pending);
            combined.addAll(selected);
            if (!ids.isEmpty() && (ids.size() == MAX_CANDIDATES
                    || characters(slices(combined, sources)) > MAX_SOURCE_CHARACTERS)) {
                batches.add(new Batch(ids, slices(pending, sources), MAX_OUTPUT_TOKENS));
                ids = new ArrayList<>();
                pending = new ArrayList<>();
            }
            ids.add(candidate.id());
            pending.addAll(selected);
        }
        if (!ids.isEmpty()) batches.add(new Batch(ids, slices(pending, sources), MAX_OUTPUT_TOKENS));

        Retrieval result = new Retrieval(edits, context, batches, reviews);
        result.checkCurrent();
        return result;
    }

    private static Retrieval manualOnly(DocumentSnapshot edits, DocumentSnapshot context,
            List<RenameCandidateDiscovery.Occurrence> candidates, String reason) {
        return new Retrieval(edits, context, List.of(), candidates.stream()
                .map(item -> new Review(item.id(), reason)).toList());
    }

    private static IndexedSource index(Path path, DocumentSnapshot.SourceFile file) {
        String text = new String(file.bytes(), StandardCharsets.UTF_8);
        TexSourceScanner scanner = new TexSourceScanner(text);
        TexSourceScanner.Scan scan = scanner.scan();
        List<SelectionSpan> paragraphs = new ArrayList<>();
        var blanks = BLANK_LINE.matcher(text);
        int start = 0;
        while (blanks.find()) {
            paragraphs.add(new SelectionSpan(path, start, blanks.start(), "paragraph"));
            start = blanks.end();
        }
        paragraphs.add(new SelectionSpan(path, start, text.length(), "paragraph"));
        return new IndexedSource(path, file.hash(), text, scanner, scan, paragraphs);
    }

    private static List<SelectionSpan> candidateContext(IndexedSource source,
            RenameCandidateDiscovery.Occurrence candidate, Map<Path, IndexedSource> sources,
            Set<Path> reachable) {
        int start = source.charIndex(candidate.startByte());
        int end = source.charIndex(candidate.endByte());
        List<SelectionSpan> selected = new ArrayList<>();
        source.paragraphs().stream().filter(item -> item.startChar() <= start && item.endChar() >= end)
                .findFirst().ifPresent(selected::add);
        source.scanner().contextSpans().stream()
                .filter(item -> !item.conditional() && item.kind().equals("equation")
                        && item.startChar() <= start && item.endChar() >= end)
                .min(Comparator.comparingInt(item -> item.endChar() - item.startChar()))
                .ifPresent(item -> selected.add(span(source.path(), item)));
        addNearest(source, start, Set.of("section"), selected);
        addNearest(source, start, Set.of("definition"), selected);
        addNearest(source, start, THEOREMS, selected);
        addNearest(source, start, Set.of("proof"), selected);

        // Follow only referenced, unconditional local definitions. Multiple definitions are
        // shown together rather than guessing which TeX execution would make effective.
        Set<String> visited = new HashSet<>();
        for (int index = 0; index < selected.size(); index++) {
            if (characters(slices(selected, sources)) > MAX_SOURCE_CHARACTERS) break;

            SelectionSpan slice = selected.get(index);
            IndexedSource owner = sources.get(slice.path());
            var commands = COMMAND.matcher(owner.text()).region(slice.startChar(), slice.endChar());
            while (commands.find()) {
                String name = commands.group();
                if (owner.scan().protectedRegions().stream().anyMatch(region ->
                        (region.reason() == DocumentSnapshot.SourceContext.COMMENT
                                || region.reason() == DocumentSnapshot.SourceContext.VERBATIM)
                                && region.startChar() <= commands.start() && region.endChar() >= commands.end())
                        || !visited.add(name)) {
                    continue;
                }
                for (IndexedSource dependency : sources.values()) {
                    if (!reachable.contains(dependency.path())) continue;

                    dependency.scanner().contextSpans().stream()
                            .filter(item -> item.kind().equals("macro") && !item.conditional()
                                    && item.name().equals(name))
                            .forEach(item -> selected.add(span(dependency.path(), item)));
                }
            }
        }
        return selected;
    }

    private static void addNearest(IndexedSource source, int position, Set<String> kinds,
            List<SelectionSpan> selected) {
        source.scanner().contextSpans().stream()
                .filter(item -> !item.conditional() && kinds.contains(item.kind())
                        && item.startChar() <= position)
                .max(Comparator.comparingInt(TexSourceScanner.ContextSpan::startChar))
                .ifPresent(item -> selected.add(span(source.path(), item)));
    }

    private static SelectionSpan span(Path path, TexSourceScanner.ContextSpan item) {
        return new SelectionSpan(path, item.startChar(), item.endChar(), item.kind());
    }

    private static List<Slice> slices(List<SelectionSpan> selected, Map<Path, IndexedSource> sources) {
        List<SelectionSpan> ordered = selected.stream()
                .sorted(Comparator.comparing((SelectionSpan item) -> item.path().toString())
                        .thenComparingInt(SelectionSpan::startChar).thenComparingInt(SelectionSpan::endChar)).toList();
        List<Slice> result = new ArrayList<>();
        int index = 0;
        while (index < ordered.size()) {
            SelectionSpan first = ordered.get(index++);
            int end = first.endChar();
            Set<String> roles = new TreeSet<>();
            roles.add(first.role());
            while (index < ordered.size() && ordered.get(index).path().equals(first.path())
                    && ordered.get(index).startChar() <= end) {
                SelectionSpan next = ordered.get(index++);
                end = Math.max(end, next.endChar());
                roles.add(next.role());
            }
            IndexedSource source = sources.get(first.path());
            int startByte = source.scanner().byteOffset(first.startChar());
            int endByte = source.scanner().byteOffset(end);
            String key = first.path() + "\0" + source.hash() + "\0" + startByte + "\0" + endByte;
            result.add(new Slice(TextEditPlan.sha256(key.getBytes(StandardCharsets.UTF_8)),
                    first.path(), source.hash(), startByte, endByte,
                    source.scanner().line(first.startChar()), source.scanner().column(first.startChar()),
                    List.copyOf(roles), source.text().substring(first.startChar(), end)));
        }
        return List.copyOf(result);
    }

    private static int characters(List<Slice> slices) {
        return slices.stream().mapToInt(item -> item.text().codePointCount(0, item.text().length())).sum();
    }

    private static Map<Path, String> sourceHashes(DocumentSnapshot snapshot) {
        Map<Path, String> hashes = new TreeMap<>();
        snapshot.files().forEach((path, file) -> hashes.put(path, file.hash()));
        return hashes;
    }

    record Slice(String id, Path path, String sourceHash, int startByte, int endByte,
            int line, int column, List<String> roles, String text) {
        Slice {
            roles = List.copyOf(roles);
        }
    }

    record Batch(List<String> candidateIds, List<Slice> slices, int maxOutputTokens) {
        Batch {
            candidateIds = List.copyOf(candidateIds);
            slices = List.copyOf(slices);
        }

        int sourceCharacters() {
            return characters(slices);
        }
    }

    record Review(String candidateId, String reason) { }

    record Retrieval(DocumentSnapshot edits, DocumentSnapshot context,
            List<Batch> batches, List<Review> reviews) {
        Retrieval {
            batches = List.copyOf(batches);
            reviews = List.copyOf(reviews);
        }

        int sourceCharacters() {
            return batches.stream().mapToInt(Batch::sourceCharacters).sum();
        }

        void checkCurrent() throws IOException {
            if (!new DocumentLoader().isCurrent(edits)) {
                throw new StaleSourceException("Saved source changed; reload and review context again.");
            }
            if (context == null) return;

            try {
                DocumentSnapshot current = new DocumentLoader().load(context.root().resolve(context.main()));
                if (!current.root().equals(context.root()) || !current.main().equals(context.main())
                        || current.complete() != context.complete()
                        || !sourceHashes(current).equals(sourceHashes(context))
                        || !current.includeSites().equals(context.includeSites())) {
                    throw new StaleSourceException("Saved context changed; reload and review again.");
                }
            } catch (DocumentLoader.LoadException exception) {
                throw new StaleSourceException("Saved context is unavailable; reload and review again.");
            }
        }
    }

    private record SelectionSpan(Path path, int startChar, int endChar, String role) { }

    private record IndexedSource(Path path, String hash, String text, TexSourceScanner scanner,
            TexSourceScanner.Scan scan, List<SelectionSpan> paragraphs) {
        int charIndex(int byteOffset) {
            int low = 0;
            int high = text.length();
            while (low < high) {
                int middle = (low + high) >>> 1; //unsigned left shift
                if (scanner.byteOffset(middle) < byteOffset) low = middle + 1;
                else high = middle;
            }
            return low;
        }
    }
}
