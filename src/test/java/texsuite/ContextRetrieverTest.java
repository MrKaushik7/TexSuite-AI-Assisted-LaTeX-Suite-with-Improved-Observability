package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ContextRetrieverTest {
    @TempDir Path directory;

    @Test
    void retrievesStructuralMeaningAndReferencedMacrosWithExactDeduplicatedRanges() throws Exception {
        Path source = source("\\newcommand{\\length}{\\card}\n\\newcommand{\\card}{n}\n"
                + "\\newcommand{\\unused}{UNRELATED}\n\n\\section{Database}\n\n"
                + "\\begin{definition}\nThe database has $n$ elements.\n\\end{definition}\n\n"
                + "\\begin{theorem}\nIt holds that $n>0$.\n\\end{theorem}\n\n"
                + "\\begin{proof}\nTherefore \\[n=\\length\\]\n\\end{proof}");
        var snapshot = new DocumentLoader().loadFile(source);
        var request = request(source);
        var inventory = new RenameCandidateDiscovery().discover(snapshot, request);
        var result = new ContextRetriever().retrieve(snapshot, request, inventory);

        assertTrue(result.reviews().isEmpty());
        assertEquals(1, result.batches().size());
        var batch = result.batches().getFirst();
        assertEquals(3, batch.candidateIds().size());
        assertEquals(1_500, batch.maxOutputTokens());
        String context = text(result);
        assertTrue(context.contains("\\section{Database}"));
        assertTrue(context.contains("\\begin{definition}"));
        assertTrue(context.contains("\\begin{theorem}"));
        assertTrue(context.contains("\\begin{proof}"));
        assertTrue(context.contains("\\newcommand{\\length}{\\card}"));
        assertTrue(context.contains("\\newcommand{\\card}{n}"));
        assertFalse(context.contains("UNRELATED"));
        byte[] bytes = Files.readAllBytes(source);
        int previousEnd = 0;
        for (var slice : batch.slices()) {
            assertTrue(slice.startByte() >= previousEnd);
            assertEquals(new String(Arrays.copyOfRange(bytes, slice.startByte(), slice.endByte()),
                    StandardCharsets.UTF_8), slice.text());
            assertEquals(TextEditPlan.sha256(bytes), slice.sourceHash());
            previousEnd = slice.endByte();
        }
        assertEquals(batch.sourceCharacters(), batch.slices().stream()
                .mapToInt(slice -> slice.text().codePointCount(0, slice.text().length())).sum());
        assertEquals(result.batches(), new ContextRetriever().retrieve(snapshot, request, inventory).batches());
        result.checkCurrent();
    }

    @Test
    void fileScopeReadsIncludedMacrosWithoutDiscoveringEditsInTheDependency() throws Exception {
        Path source = source("$n=\\meaning$\n\\input{defs}");
        Files.writeString(directory.resolve("defs.tex"), "\\newcommand{\\meaning}{n}");
        var result = retrieve(source);

        assertEquals(1, result.edits().files().size());
        assertEquals(2, result.context().files().size());
        assertEquals(1, result.batches().getFirst().candidateIds().size());
        assertTrue(result.batches().getFirst().slices().stream()
                .anyMatch(slice -> slice.path().equals(Path.of("defs.tex"))));
        assertTrue(text(result).contains("\\newcommand{\\meaning}{n}"));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void conditionalDependenciesAndConditionalDefinitionsDoNotSupplyCertainContext() throws Exception {
        Path source = source("\\iffalse\\newcommand{\\meaning}{CONDITIONAL}\\fi\n\n"
                + "$n=\\meaning$\\iffalse\\input{defs}\\fi");
        Files.writeString(directory.resolve("defs.tex"), "\\newcommand{\\meaning}{DEPENDENCY}");
        var result = retrieve(source);

        assertTrue(result.reviews().isEmpty());
        assertFalse(text(result).contains("CONDITIONAL"));
        assertFalse(text(result).contains("DEPENDENCY"));
    }

    @Test
    void splitsAtEightCandidatesAndCountsSharedContextAgainInEachBatch() throws Exception {
        Path source = source("\\newcommand{\\meaning}{shared}\n\n" + "$n=\\meaning$\n\n".repeat(9));
        var result = retrieve(source);

        assertEquals(List.of(8, 1), result.batches().stream().map(batch -> batch.candidateIds().size()).toList());
        assertEquals(9, result.batches().stream().flatMap(batch -> batch.candidateIds().stream()).distinct().count());
        int uniqueCharacters = result.batches().stream().flatMap(batch -> batch.slices().stream()).distinct()
                .mapToInt(slice -> slice.text().codePointCount(0, slice.text().length())).sum();
        assertTrue(result.sourceCharacters() > uniqueCharacters);
        assertEquals(result.sourceCharacters(), result.batches().stream()
                .mapToInt(ContextRetriever.Batch::sourceCharacters).sum());
    }

    @Test
    void splitsForSourceBudgetBeforeTheCandidateLimit() throws Exception {
        Path source = source("a".repeat(13_000) + "$n$\n\n" + "b".repeat(13_000) + "$n$");
        var result = retrieve(source);

        assertEquals(2, result.batches().size());
        assertTrue(result.batches().stream().allMatch(batch -> batch.candidateIds().size() == 1
                && batch.sourceCharacters() <= 24_000));
        assertTrue(result.reviews().isEmpty());
    }

    @Test
    void oversizedCompleteContextIsAccountedForAsManualInsteadOfTruncated() throws Exception {
        Path source = source("a".repeat(24_000) + "$n$");
        var result = retrieve(source);

        assertTrue(result.batches().isEmpty());
        assertEquals(1, result.reviews().size());
        assertTrue(result.reviews().getFirst().reason().contains("budget"));
        assertEquals(0, result.sourceCharacters());
        assertEquals("a".repeat(24_000) + "$n$", Files.readString(source));
    }

    @Test
    void supplementaryCharactersCountOnceAndCrLfDoesNotCreateFalseParagraphs() throws Exception {
        Path source = source("é😀 first $n$\r\nstill first\r\n \t\r\nsecond $n$");
        var result = retrieve(source);

        var slices = result.batches().getFirst().slices();
        assertEquals(2, slices.size());
        assertTrue(slices.getFirst().text().contains("\r\nstill first"));
        assertEquals(4, slices.getLast().line());
        assertEquals(1, slices.getLast().column());
        assertEquals("second $n$", slices.getLast().text());
        assertEquals(new String(Arrays.copyOfRange(Files.readAllBytes(source),
                slices.getLast().startByte(), slices.getLast().endByte()), StandardCharsets.UTF_8),
                slices.getLast().text());

        source("😀".repeat(13_000) + " $n$");
        assertTrue(retrieve(source).reviews().isEmpty());
        assertEquals(13_004, retrieve(source).sourceCharacters());
    }

    @Test
    void missingOrStructurallyUnknownDependenciesLeaveEveryCandidateForManualReview() throws Exception {
        Path source = source("$n+n$\\input{defs}");
        var missing = retrieve(source);
        assertTrue(missing.batches().isEmpty());
        assertEquals(2, missing.reviews().size());
        assertTrue(missing.reviews().getFirst().reason().contains("unavailable"));

        Files.writeString(directory.resolve("defs.tex"), "\\newcommand{\\bad}{");
        var malformed = retrieve(source);
        assertTrue(malformed.batches().isEmpty());
        assertEquals(2, malformed.reviews().size());
        assertTrue(malformed.reviews().getFirst().reason().contains("structural"));
    }

    @Test
    void dependencyEditsAndRetargetedAliasesInvalidateContext() throws Exception {
        Path source = source("$n=\\meaning$\\input{defs}");
        Path first = Files.writeString(directory.resolve("first.tex"), "\\newcommand{\\meaning}{one}");
        Path second = Files.writeString(directory.resolve("second.tex"), "\\newcommand{\\meaning}{one}");
        Path alias = Files.createSymbolicLink(directory.resolve("defs.tex"), first.getFileName());
        var result = retrieve(source);

        Files.writeString(first, "\\newcommand{\\meaning}{two}");
        assertThrows(StaleSourceException.class, result::checkCurrent);
        Files.writeString(first, "\\newcommand{\\meaning}{one}");
        result.checkCurrent();
        Files.delete(alias);
        Files.createSymbolicLink(alias, second.getFileName());
        assertThrows(StaleSourceException.class, result::checkCurrent);
    }

    @Test
    void contextGuardImmediatelyBeforeCommitPreventsAWriteAndLeavesRecoverableEvidence() throws Exception {
        Path source = source("$n=\\meaning$\\input{defs}");
        Path definitions = Files.writeString(directory.resolve("defs.tex"), "\\newcommand{\\meaning}{one}");
        var snapshot = new DocumentLoader().loadFile(source);
        var inventory = new RenameCandidateDiscovery().discover(snapshot, request(source));
        var result = new ContextRetriever().retrieve(snapshot, request(source), inventory);
        var ids = inventory.occurrences().stream().filter(item -> item.status() == RenameCandidateDiscovery.Status.CANDIDATE)
                .map(RenameCandidateDiscovery.Occurrence::id).toList();
        var edits = RenamePlan.create(snapshot, request(source), ids).editsFor(snapshot);
        var plan = new TextEditPlan(snapshot, edits, "m", new TexCompileGate(null, true, null, null));
        int[] checks = {0};

        assertThrows(StaleSourceException.class, () -> plan.apply(() -> {
            if (++checks[0] == 3) Files.writeString(definitions, "\\newcommand{\\meaning}{two}");
            result.checkCurrent();
        }));
        assertEquals(3, checks[0]);
        assertEquals("$n=\\meaning$\\input{defs}", Files.readString(source));
        assertTrue(TextRecovery.pending(source.getParent()).isEmpty());
        try (var journals = Files.walk(directory.resolve(".tex-suite/backups"))) {
            Path journal = journals.filter(path -> path.getFileName().toString().equals("recovery.properties"))
                    .findFirst().orElseThrow();
            assertTrue(Files.readString(journal).contains("status=restored"));
        }
    }

    @Test
    void structuralObservationsResetWithoutAuthorizingMathCommandsOrProtectedBytes() throws Exception {
        String source = "\\section{Title}\n\\begin{definition}$n+\\alpha$\\end{definition}\n"
                + "\\begin{verbatim}\\section{Fake}$n$\\end{verbatim}";
        var scanner = new TexSourceScanner(source);
        scanner.scan();
        var spans = new ArrayList<>(scanner.contextSpans());
        assertTrue(spans.stream().anyMatch(span -> span.kind().equals("equation")));
        assertEquals(1, spans.stream().filter(span -> span.kind().equals("section")).count());
        scanner.scanFor("n");
        assertEquals(spans, scanner.contextSpans());
        scanner.scan();
        assertEquals(spans, scanner.contextSpans());

        Path file = source(source);
        var snapshot = new DocumentLoader().loadFile(file);
        var inventory = new RenameCandidateDiscovery().discover(snapshot,
                new RenameRequest(file, "\\alpha", "alpha", "\\beta", RenameRequest.Scope.FILE));
        assertEquals(1, inventory.count(RenameCandidateDiscovery.Status.EXCLUDED));
        assertEquals(0, inventory.count(RenameCandidateDiscovery.Status.CANDIDATE));
    }

    private Path source(String text) throws Exception {
        return Files.writeString(directory.resolve("main.tex"), text).toRealPath();
    }

    private RenameRequest request(Path source) {
        return new RenameRequest(source, "n", "database length", "m", RenameRequest.Scope.FILE);
    }

    private ContextRetriever.Retrieval retrieve(Path source) throws Exception {
        var snapshot = new DocumentLoader().loadFile(source);
        var request = request(source);
        return new ContextRetriever().retrieve(snapshot, request,
                new RenameCandidateDiscovery().discover(snapshot, request));
    }

    private String text(ContextRetriever.Retrieval result) {
        return String.join("\n", result.batches().stream().flatMap(batch -> batch.slices().stream())
                .map(ContextRetriever.Slice::text).toList());
    }
}
