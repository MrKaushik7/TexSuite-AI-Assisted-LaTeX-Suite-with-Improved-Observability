package texsuite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RenameCandidateDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void unfinishedMathUsesUnknownReviewReasonForLongerTargets() throws Exception {
        Path source = write("unfinished.tex", "$nn");
        DocumentSnapshot snapshot = new DocumentLoader().loadFile(source);
        RenameRequest request = new RenameRequest(source.toRealPath(), "nn", "symbol", "xx",
                RenameRequest.Scope.FILE);
        var result = new RenameCandidateDiscovery().discover(snapshot, request);
        assertEquals(1, result.occurrences().size());
        assertEquals(RenameCandidateDiscovery.Status.REVIEW, result.occurrences().getFirst().status());
        assertEquals("unknown scanner context", result.occurrences().getFirst().reason());
    }

    @Test
    void fileInventoryDoesNotFollowBrokenDependenciesAndChecksOnlyTargetFreshness()
            throws Exception {
        Path chapter = write("chapter.tex", "$n$\\input{../missing}\\includegraphics{absent.pdf}");
        Path sibling = write("sibling.tex", "$n$");
        DocumentLoader loader = new DocumentLoader();
        DocumentSnapshot snapshot = loader.loadFile(chapter);
        byte[] original = Files.readAllBytes(chapter);
        Set<Path> originalFiles;
        try (var paths = Files.list(temporaryDirectory)) {
            originalFiles = paths.collect(Collectors.toSet());
        }

        assertEquals(DocumentSnapshot.Coverage.FILE, snapshot.coverage());
        assertFalse(snapshot.complete());
        assertEquals(Set.of(Path.of("chapter.tex")), snapshot.files().keySet());
        assertEquals(1, snapshot.includeSites().size());
        assertFalse(snapshot.includeSites().getFirst().resolved());
        assertEquals(1, snapshot.assets().size());
        assertFalse(snapshot.assets().getFirst().resolved());
        assertTrue(loader.isCurrent(snapshot));
        RenameCandidateDiscovery.Result result = discover(snapshot, chapter, 'n',
                RenameRequest.Scope.FILE);
        assertEquals(1, result.count(RenameCandidateDiscovery.Status.CANDIDATE));
        assertTrue(result.occurrences().stream().noneMatch(item -> item.path().equals(sibling)));
        assertArrayEquals(original, Files.readAllBytes(chapter));
        try (var paths = Files.list(temporaryDirectory)) {
            assertEquals(originalFiles, paths.collect(Collectors.toSet()));
        }

        Files.writeString(sibling, "$n+n$");
        assertTrue(loader.isCurrent(snapshot));
        Files.writeString(chapter, "$n+n$");
        assertFalse(loader.isCurrent(snapshot));
    }

    @Test
    void projectInventoryDeduplicatesSourcesAndReviewsConditionalOnlyPaths()
            throws Exception {
        Path main = write("main.tex", "$n$\\input{shared}\\input{shared}"
                + "\\ifnum1=1\\input{optional}\\fi");
        write("shared.tex", "$n$");
        write("optional.tex", "$n$\\input{descendant}\\input{shared}");
        write("descendant.tex", "$n$");
        DocumentSnapshot snapshot = new DocumentLoader().load(main);
        RenameCandidateDiscovery.Result result = discover(snapshot, main, 'n',
                RenameRequest.Scope.PROJECT);

        assertTrue(snapshot.complete());
        assertEquals(5, snapshot.includeSites().size());
        assertEquals(4, result.fileCount());
        assertEquals(13, result.occurrences().size());
        assertEquals(2, result.count(RenameCandidateDiscovery.Status.CANDIDATE));
        assertEquals(2, result.count(RenameCandidateDiscovery.Status.REVIEW));
        Map<Path, RenameCandidateDiscovery.Occurrence> byPath = result.occurrences().stream()
                .filter(item -> item.status() != RenameCandidateDiscovery.Status.EXCLUDED)
                .collect(Collectors.toMap(RenameCandidateDiscovery.Occurrence::path, item -> item));
        assertEquals(RenameCandidateDiscovery.Status.CANDIDATE,
                byPath.get(Path.of("shared.tex")).status());
        assertEquals("only conditionally reachable",
                byPath.get(Path.of("descendant.tex")).reason());
        assertEquals(result.occurrences().size(), result.occurrences().stream()
                .map(RenameCandidateDiscovery.Occurrence::id).distinct().count());
    }

    @Test
    void inventoryAccountsForEveryLiteralLetterWithStableUtf8Spans() throws Exception {
        String text = "π $n+n_i+nn+\\number+\\text{n}+\\label{n}$\r\n"
                + "% n\n\\verb|n| \\newcommand{\\foo}{n}\nprose n\n";
        Path main = write("main.tex", text);
        DocumentSnapshot snapshot = new DocumentLoader().loadFile(main);
        RenameCandidateDiscovery.Result first = discover(snapshot, main, 'n',
                RenameRequest.Scope.FILE);
        RenameCandidateDiscovery.Result second = discover(snapshot, main, 'n',
                RenameRequest.Scope.FILE);

        assertEquals(first, second);
        assertEquals(text.chars().filter(value -> value == 'n').count(),
                first.occurrences().size());
        assertEquals(first.occurrences().size(), first.occurrences().stream()
                .map(item -> item.path() + ":" + item.startByte()).distinct().count());
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (RenameCandidateDiscovery.Occurrence item : first.occurrences()) {
            assertEquals(1, item.endByte() - item.startByte());
            assertEquals((byte) 'n', bytes[item.startByte()]);
            assertEquals(snapshot.files().get(item.path()).hash(), item.sourceHash());
        }
        assertEquals(RenameCandidateDiscovery.Status.CANDIDATE,
                at(first, text, "$n+", 1).status());
        assertEquals(1, at(first, text, "$n+", 1).line());
        assertEquals(4, at(first, text, "$n+", 1).column());
        assertEquals(RenameCandidateDiscovery.Status.REVIEW,
                at(first, text, "nn+", 0).status());
        assertEquals("adjacent letter", at(first, text, "nn+", 0).reason());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                at(first, text, "\\number", 1).status());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                at(first, text, "\\text{n}", 6).status());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                at(first, text, "% n", 2).status());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                at(first, text, "\\verb|n|", 6).status());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                at(first, text, "prose n", 6).status());

        RenameCandidateDiscovery.Result x = discover(snapshot, main, 'x',
                RenameRequest.Scope.FILE);
        assertEquals(text.chars().filter(value -> value == 'x').count(), x.occurrences().size());
        assertFalse(x.occurrences().isEmpty());
        assertNotEquals(first.occurrences().getFirst().id(), x.occurrences().getFirst().id());
    }

    @Test
    void scannerUncertaintyReviewsMathInAffectedSourceOnly() throws Exception {
        Path main = write("main.tex", "\\input{uncertain}\\input{clean}");
        write("uncertain.tex", "$n$ $n");
        write("clean.tex", "$n$");
        RenameCandidateDiscovery.Result result = discover(new DocumentLoader().load(main), main,
                'n', RenameRequest.Scope.PROJECT);

        assertEquals(RenameCandidateDiscovery.Status.CANDIDATE,
                result.occurrences().stream().filter(item -> item.path().equals(Path.of("clean.tex")))
                        .findFirst().orElseThrow().status());
        assertTrue(result.occurrences().stream()
                .filter(item -> item.path().equals(Path.of("uncertain.tex")))
                .noneMatch(item -> item.status() == RenameCandidateDiscovery.Status.CANDIDATE));
    }

    @Test
    void candidatePositionsHandleUnicodeAndAllSupportedNewlines() throws Exception {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            String text = "😀" + newline + "π $n$";
            Path main = write("main.tex", text);
            RenameCandidateDiscovery.Result result = discover(new DocumentLoader().loadFile(main),
                    main, 'n', RenameRequest.Scope.FILE);
            RenameCandidateDiscovery.Occurrence candidate = result.occurrences().getFirst();

            assertEquals(RenameCandidateDiscovery.Status.CANDIDATE, candidate.status());
            assertEquals(2, candidate.line());
            assertEquals(4, candidate.column());
            assertEquals(text.substring(0, text.indexOf('n'))
                    .getBytes(StandardCharsets.UTF_8).length, candidate.startByte());
        }
    }

    @Test
    void incompleteProjectInventoryAndWrongCoverageAreRejected() throws Exception {
        Path main = write("main.tex", "\\ifnum1=1\\input{missing}\\fi $n$");
        DocumentLoader loader = new DocumentLoader();
        DocumentSnapshot project = loader.load(main);
        DocumentSnapshot file = loader.loadFile(main);
        RenameRequest projectRequest = request(main, 'n', RenameRequest.Scope.PROJECT);

        assertFalse(project.complete());
        assertThrows(IllegalArgumentException.class,
                () -> new RenameCandidateDiscovery().discover(project, projectRequest));
        assertThrows(IllegalArgumentException.class,
                () -> new RenameCandidateDiscovery().discover(file, projectRequest));
        assertFalse(loader.isCurrent(project));
        assertTrue(loader.isCurrent(file));
    }

    @Test
    void projectFreshnessIncludesChildrenWhileFileFreshnessDoesNot() throws Exception {
        Path main = write("main.tex", "\\input{child}$n$");
        Path child = write("child.tex", "$n$");
        DocumentLoader loader = new DocumentLoader();
        DocumentSnapshot project = loader.load(main);
        DocumentSnapshot file = loader.loadFile(main);

        assertTrue(loader.isCurrent(project));
        assertTrue(loader.isCurrent(file));
        Files.writeString(child, "$n+n$");
        assertFalse(loader.isCurrent(project));
        assertTrue(loader.isCurrent(file));
    }

    @Test
    void literalSourceMatchesWholeSpanAndRespectsTeXContext() throws Exception {
        String text = "π $hu+huu+\\hu+\\text{hu}+αβ$ prose hu % hu\n";
        Path main = write("main.tex", text);
        DocumentSnapshot snapshot = new DocumentLoader().loadFile(main);
        RenameCandidateDiscovery discovery = new RenameCandidateDiscovery();
        RenameCandidateDiscovery.Result result = discovery.discover(snapshot,
                new RenameRequest(main.toRealPath(), "hu", "name", "hello",
                        RenameRequest.Scope.FILE));

        assertEquals(6, result.occurrences().size());
        assertEquals(RenameCandidateDiscovery.Status.CANDIDATE,
                at(result, text, "$hu", 1).status());
        assertEquals(RenameCandidateDiscovery.Status.REVIEW,
                at(result, text, "+huu", 1).status());
        assertEquals("adjacent letter", at(result, text, "+huu", 1).reason());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                at(result, text, "\\hu", 1).status());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                at(result, text, "\\text{hu}", 6).status());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                at(result, text, "prose hu", 6).status());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                at(result, text, "% hu", 2).status());
        for (RenameCandidateDiscovery.Occurrence occurrence : result.occurrences()) {
            assertEquals("hu", new String(snapshot.files().get(occurrence.path()).bytes(),
                    occurrence.startByte(), occurrence.endByte() - occurrence.startByte(),
                    StandardCharsets.UTF_8));
        }
        assertEquals(text, Files.readString(main));

        RenameCandidateDiscovery.Result unicode = discovery.discover(snapshot,
                new RenameRequest(main.toRealPath(), "αβ", "name", "x",
                        RenameRequest.Scope.FILE));
        RenameCandidateDiscovery.Occurrence match = unicode.occurrences().getFirst();
        assertEquals(RenameCandidateDiscovery.Status.CANDIDATE, match.status());
        assertEquals("αβ".getBytes(StandardCharsets.UTF_8).length,
                match.endByte() - match.startByte());
    }

    @Test
    void partialTeXGroupsRequireReview() throws Exception {
        Path main = write("groups.tex", "$a{b}c{d}$");
        DocumentSnapshot snapshot = new DocumentLoader().loadFile(main);
        for (String source : List.of("a{b", "b}c", "}c{")) {
            RenameCandidateDiscovery.Result result = new RenameCandidateDiscovery().discover(snapshot,
                    new RenameRequest(main.toRealPath(), source, "fragment", "x",
                            RenameRequest.Scope.FILE));
            assertEquals(RenameCandidateDiscovery.Status.REVIEW,
                    result.occurrences().getFirst().status());
            assertEquals("partial TeX group", result.occurrences().getFirst().reason());
        }
    }

    @Test
    void supplementaryUnicodeSourceUsesWholeCodePointContext() throws Exception {
        String text = "$😀$ 😀";
        Path main = write("main.tex", text);
        DocumentSnapshot snapshot = new DocumentLoader().loadFile(main);
        RenameCandidateDiscovery.Result result = new RenameCandidateDiscovery().discover(snapshot,
                new RenameRequest(main.toRealPath(), "😀", "symbol", "x",
                        RenameRequest.Scope.FILE));

        assertEquals(2, result.occurrences().size());
        assertEquals(RenameCandidateDiscovery.Status.CANDIDATE,
                result.occurrences().get(0).status());
        assertEquals(RenameCandidateDiscovery.Status.EXCLUDED,
                result.occurrences().get(1).status());
        assertEquals(4, result.occurrences().get(0).endByte()
                - result.occurrences().get(0).startByte());
    }

    @Test
    void overlappingAndCrossBoundaryLiteralMatchesRequireReview() throws Exception {
        Path main = write("main.tex", "$aaa$ $n$z");
        DocumentSnapshot snapshot = new DocumentLoader().loadFile(main);
        RenameCandidateDiscovery discovery = new RenameCandidateDiscovery();
        RenameCandidateDiscovery.Result overlapping = discovery.discover(snapshot,
                new RenameRequest(main.toRealPath(), "aa", "name", "x",
                        RenameRequest.Scope.FILE));
        assertEquals(2, overlapping.occurrences().size());
        assertTrue(overlapping.occurrences().stream().allMatch(item ->
                item.status() == RenameCandidateDiscovery.Status.REVIEW
                        && item.reason().equals("overlapping literal matches")));
        assertEquals(1, overlapping.occurrences().getFirst().startByte());
        assertEquals(3, overlapping.occurrences().getFirst().endByte());
        assertEquals(2, overlapping.occurrences().get(1).startByte());
        assertEquals(4, overlapping.occurrences().get(1).endByte());

        RenameCandidateDiscovery.Result crossing = discovery.discover(snapshot,
                new RenameRequest(main.toRealPath(), "n$z", "name", "x",
                        RenameRequest.Scope.FILE));
        assertEquals(1, crossing.occurrences().size());
        assertEquals(RenameCandidateDiscovery.Status.REVIEW,
                crossing.occurrences().getFirst().status());
        assertEquals("unknown scanner context", crossing.occurrences().getFirst().reason());
    }

    @Test
    void requestAcceptsLiteralReplacementStringsAndRejectsUnsafeInput() throws Exception {
        Path main = write("main.tex", "$n$");
        for (String source : List.of("", " ", "n\nx", "n\u2028x", "\ud800")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RenameRequest(main, source, "meaning", "\\number",
                            RenameRequest.Scope.FILE));
        }
        for (String source : List.of("hu", "what", "π", "😀", "\\alpha", " hello ")) {
            assertEquals(source, new RenameRequest(main, source, "meaning", "replacement",
                    RenameRequest.Scope.FILE).source());
        }
        for (String replacement : List.of("hello", "long_name", "αβ", "😀", "hello world",
                " hello ", "\\alpha", "n_{total}", "\\text{hello}", "quote \"here\"", "m")) {
            assertEquals(replacement, new RenameRequest(main, "n", "meaning", replacement,
                    RenameRequest.Scope.FILE).replacement());
        }
        for (String replacement : List.of("", " ", "\u00a0", "n", "n\nx", "n\rx",
                "n\tx", "n\u0000x", "n\u001bx", "n\u200bx", "n\u2028x", "n\u2029x",
                "\ud800", "\udc00")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RenameRequest(main, "n", "meaning", replacement,
                            RenameRequest.Scope.FILE));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new RenameRequest(main, "n", " ", "x", RenameRequest.Scope.FILE));
        assertEquals("\\number", new RenameRequest(main, "n", "length", "\\number",
                RenameRequest.Scope.FILE).replacement());

        DocumentSnapshot snapshot = new DocumentLoader().loadFile(main);
        RenameCandidateDiscovery discovery = new RenameCandidateDiscovery();
        Path target = main.toRealPath();
        RenameCandidateDiscovery.Result shortResult = discovery.discover(snapshot,
                new RenameRequest(target, "n", "meaning", "m", RenameRequest.Scope.FILE));
        RenameCandidateDiscovery.Result longResult = discovery.discover(snapshot,
                new RenameRequest(target, "n", "meaning", "\\text{hello world}",
                        RenameRequest.Scope.FILE));
        assertEquals(shortResult.occurrences(), longResult.occurrences());
    }

    private RenameCandidateDiscovery.Result discover(DocumentSnapshot snapshot, Path main,
            char source, RenameRequest.Scope scope) {
        return new RenameCandidateDiscovery().discover(snapshot, request(main, source, scope));
    }

    private RenameRequest request(Path main, char source, RenameRequest.Scope scope) {
        try {
            return new RenameRequest(main.toRealPath(), String.valueOf(source), "meaning",
                    "\\number", scope);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private RenameCandidateDiscovery.Occurrence at(RenameCandidateDiscovery.Result result,
            String text, String anchor, int offset) {
        int charIndex = text.indexOf(anchor) + offset;
        int byteOffset = text.substring(0, charIndex).getBytes(StandardCharsets.UTF_8).length;
        return result.occurrences().stream().filter(item -> item.startByte() == byteOffset)
                .findFirst().orElseThrow();
    }

    private Path write(String name, String text) throws Exception {
        return Files.writeString(temporaryDirectory.resolve(name), text);
    }
}
