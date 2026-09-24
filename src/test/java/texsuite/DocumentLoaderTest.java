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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import texsuite.DocumentSnapshot.SourceContext;

final class DocumentLoaderTest {
    @TempDir
    Path temporaryDirectory;
    @TempDir
    Path outsideDirectory;

    @Test
    void repeatedScannerRequestsDoNotLeakOccurrencesOrStructuralState() {
        for (String text : List.of("$n+x$ \\input{chapter} % n",
                "\\ifdraft {$n \\includegraphics{figure.pdf}",
                "\\begin{verbatim}n", "} n")) {
            TexSourceScanner scanner = new TexSourceScanner(text);
            TexSourceScanner.Scan requested = scanner.scanFor('n');
            TexSourceScanner.Scan neutral = scanner.scan();

            assertTrue(neutral.occurrences().isEmpty());
            assertEquals(new TexSourceScanner(text).scan(), neutral);
            assertEquals(requested, scanner.scanFor('n'));
            assertEquals(new TexSourceScanner(text).scanFor('x'), scanner.scanFor('x'));
            assertEquals(neutral, scanner.scan());
        }
    }

    @Test
    void explicitScannerAcceptsOnlyAsciiLetters() {
        TexSourceScanner scanner = new TexSourceScanner("$n+x+X$ % π");
        for (char target : new char[] {'\0', '$', '%', '\\', 'π', '\ud83d'}) {
            assertThrows(IllegalArgumentException.class, () -> scanner.scanFor(target));
        }
        assertEquals(List.of(new TexSourceScanner.RawOccurrence(3, SourceContext.MATH, false)),
                scanner.scanFor('x').occurrences());
        assertEquals(List.of(new TexSourceScanner.RawOccurrence(5, SourceContext.MATH, false)),
                scanner.scanFor('X').occurrences());
        assertTrue(scanner.scan().occurrences().isEmpty());
    }

    @Test
    void scansLiteralMathLettersOnlyOnExplicitRequest() throws Exception {
        String text = "π prose n\n% $n$ \\input{ghost}\n"
                + "$n_i + 2n + \\mathit{n} + sin + nn + \\text{n} + \\label{n}"
                + " + \\number + \\n$\n"
                + "\\verb|$n$|\n\\begin{verbatim}$n$\\end{verbatim}\n"
                + "\\newcommand{\\foo}{n}\n";
        Path main = Files.writeString(temporaryDirectory.resolve("main.tex"), text);

        DocumentSnapshot snapshot = new DocumentLoader().load(main);
        TexSourceScanner.Scan scan = new TexSourceScanner(text).scanFor('n');

        assertTrue(snapshot.complete());
        assertTrue(new TexSourceScanner(text).scan().occurrences().isEmpty());
        assertEquals(6, scan.occurrences().stream()
                .filter(item -> item.reason() == SourceContext.MATH).count());
        assertEquals(1, snapshot.files().size());
        assertEquals(0, snapshot.includeSites().size());
        assertEquals(text.chars().filter(character -> character == 'n').count(),
                scan.occurrences().size());
        assertEquals(scan.occurrences().size(), scan.occurrences().stream()
                .map(TexSourceScanner.RawOccurrence::charIndex).distinct().count());
        assertEquals(SourceContext.MATH, occurrenceAt(scan, text, "$n_i", 1).reason());
        assertEquals(SourceContext.MATH, occurrenceAt(scan, text, "sin", 2).reason());
        assertEquals(SourceContext.TEXT_ARGUMENT,
                occurrenceAt(scan, text, "\\text{n}", 6).reason());
        assertEquals(SourceContext.METADATA,
                occurrenceAt(scan, text, "\\label{n}", 7).reason());
        assertEquals(SourceContext.CONTROL_SEQUENCE,
                occurrenceAt(scan, text, "\\number", 1).reason());
        assertEquals(SourceContext.COMMENT,
                occurrenceAt(scan, text, "% $n$", 3).reason());
        assertEquals(SourceContext.VERBATIM,
                occurrenceAt(scan, text, "\\verb|$n$|", 7).reason());
        assertEquals(SourceContext.DEFINITION,
                occurrenceAt(scan, text, "\\newcommand{\\foo}{n}", 18).reason());
        int commentStart = text.indexOf('%');
        int commentEnd = text.indexOf('\n', commentStart) + 1;
        assertTrue(snapshot.protectedRegions().stream().anyMatch(region ->
                region.reason() == SourceContext.COMMENT
                        && region.startByte() == byteOffset(text, commentStart)
                        && region.endByte() == byteOffset(text, commentEnd)));
        int definitionStart = text.indexOf("\\newcommand");
        assertTrue(snapshot.protectedRegions().stream().anyMatch(region ->
                region.reason() == SourceContext.DEFINITION
                        && region.startByte() == byteOffset(text, definitionStart)
                        && region.endByte() == byteOffset(text,
                                definitionStart + "\\newcommand{\\foo}{n}".length())));
        assertTrue(snapshot.protectedRegions().stream().anyMatch(region ->
                region.reason() == SourceContext.VERBATIM));
        assertArrayEquals(Files.readAllBytes(main), snapshot.files().get(Path.of("main.tex")).bytes());
    }

    @Test
    void loadsSharedSourcesOnceAndRecordsConditionalIncludeSites() throws Exception {
        Path main = write("main.tex", "\\input{chapter}\n\\ifnum1=1\n\\input{optional}\n\\fi\n");
        write("chapter.tex", "$n$\n\\input{shared}\n");
        write("optional.tex", "$n$\n\\input{shared}\n");
        write("shared.tex", "$n$\n");

        DocumentSnapshot snapshot = new DocumentLoader().load(main);

        assertEquals(4, snapshot.files().size());
        assertEquals(4, snapshot.includeSites().size());
        assertEquals(1, snapshot.includeSites().stream().filter(item -> item.conditional()).count());
        assertTrue(snapshot.includeSites().stream().anyMatch(item -> item.conditional()
                && item.target().equals(Path.of("optional.tex"))));
        assertTrue(snapshot.complete());
    }

    @Test
    void rejectsCyclesEscapesMissingRequiredSourcesAndInvalidIncludedUtf8() throws Exception {
        Path main = write("main.tex", "\\input{chapter}\n");
        write("chapter.tex", "\\input{main}\n");
        assertTrue(assertThrows(DocumentLoader.LoadException.class,
                () -> new DocumentLoader().load(main)).getMessage().contains("cycle"));

        Files.writeString(main, "\\input{../outside.tex}\n");
        assertTrue(assertThrows(DocumentLoader.LoadException.class,
                () -> new DocumentLoader().load(main)).getMessage().contains("escapes"));

        Files.writeString(main, "\\input{/}\n");
        assertTrue(assertThrows(DocumentLoader.LoadException.class,
                () -> new DocumentLoader().load(main)).getMessage().contains("escapes"));

        Files.writeString(main, "\\input{missing}\n");
        assertTrue(assertThrows(DocumentLoader.LoadException.class,
                () -> new DocumentLoader().load(main)).getMessage().contains("missing"));

        Files.writeString(main, "\\input{chapter}\n");
        Files.write(temporaryDirectory.resolve("chapter.tex"),
                new byte[] {(byte) 0xc3, (byte) 0x28});
        assertTrue(assertThrows(DocumentLoader.LoadException.class,
                () -> new DocumentLoader().load(main)).getMessage().contains("UTF-8"));
    }

    @Test
    void unresolvedConditionalIncludeIsVisibleWithoutClaimingCompleteCoverage() throws Exception {
        Path main = write("main.tex", "\\ifnum1=1\n\\input{missing}\n\\fi\n$n$\n");

        DocumentSnapshot snapshot = new DocumentLoader().load(main);

        assertFalse(snapshot.complete());
        assertEquals(1, snapshot.includeSites().size());
        assertFalse(snapshot.includeSites().getFirst().resolved());
        assertFalse(new DocumentLoader().isCurrent(snapshot));
        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(item -> item.code().equals("MISSING_CONDITIONAL_INCLUDE")));
    }

    @Test
    void recordsProtectedRegionsAssetsAndExactByteOffsets() throws Exception {
        String text = "π $n$ \\includegraphics{figure.pdf}\n\\ensuremath{n}\n";
        Path main = write("main.tex", text);
        Files.write(temporaryDirectory.resolve("figure.pdf"), new byte[] {1, 2, 3});
        DocumentLoader loader = new DocumentLoader();

        DocumentSnapshot snapshot = loader.load(main);
        TexSourceScanner scanner = new TexSourceScanner(text);
        TexSourceScanner.Scan requested = scanner.scanFor('n');

        assertEquals(SourceContext.MATH, occurrenceAt(requested, text, "$n$", 1).reason());
        assertEquals(SourceContext.UNKNOWN,
                occurrenceAt(requested, text, "\\ensuremath{n}", 12).reason());
        assertEquals(1, snapshot.assets().size());
        assertEquals("figure.pdf", snapshot.assets().getFirst().literal());
        assertTrue(snapshot.assets().getFirst().resolved());
        assertEquals(Path.of("figure.pdf"), snapshot.assets().getFirst().target());
        assertEquals(4, scanner.byteOffset(occurrenceAt(requested, text, "$n$", 1).charIndex()));
        assertTrue(snapshot.protectedRegions().stream().anyMatch(region ->
                region.reason() == SourceContext.UNKNOWN));
        assertTrue(loader.isCurrent(snapshot));
        byte[] exposed = snapshot.files().get(Path.of("main.tex")).bytes();
        exposed[0] = 0;
        assertNotEquals(0, snapshot.files().get(Path.of("main.tex")).bytes()[0]);
        Files.write(temporaryDirectory.resolve("figure.pdf"), new byte[] {4, 5, 6});
        assertFalse(loader.isCurrent(snapshot));
        DocumentSnapshot refreshed = loader.load(main);
        assertNotEquals(snapshot.fingerprint(), refreshed.fingerprint());
        assertTrue(loader.isCurrent(refreshed));
        Files.writeString(main, "changed");
        assertFalse(loader.isCurrent(refreshed));
    }

    @Test
    void rejectsSymbolicLinkEscapeForIncludesAndAssets() throws Exception {
        Path outsideSource = Files.writeString(outsideDirectory.resolve("other.tex"), "$n$");
        Path outsideAsset = Files.write(outsideDirectory.resolve("figure.pdf"), new byte[] {1});
        Files.createSymbolicLink(temporaryDirectory.resolve("linked.tex"), outsideSource);
        Files.createSymbolicLink(temporaryDirectory.resolve("linked.pdf"), outsideAsset);
        Path main = write("main.tex", "\\input{linked}\n");

        try {
            assertTrue(assertThrows(DocumentLoader.LoadException.class,
                    () -> new DocumentLoader().load(main)).getMessage().contains("escapes"));

            Files.writeString(main, "\\includegraphics{linked.pdf}\n");
            assertTrue(assertThrows(DocumentLoader.LoadException.class,
                    () -> new DocumentLoader().load(main)).getMessage().contains("escapes"));
        } finally {
            Files.deleteIfExists(temporaryDirectory.resolve("linked.tex"));
            Files.deleteIfExists(temporaryDirectory.resolve("linked.pdf"));
        }
    }

    @Test
    void ambiguousAssetExtensionNeedsReview() throws Exception {
        Path main = write("main.tex", "\\includegraphics{figure}\n");
        Files.write(temporaryDirectory.resolve("figure.pdf"), new byte[] {1});
        Files.write(temporaryDirectory.resolve("figure.png"), new byte[] {2});

        DocumentSnapshot snapshot = new DocumentLoader().load(main);

        assertEquals(1, snapshot.assets().size());
        assertFalse(snapshot.assets().getFirst().resolved());
        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(item -> item.code().equals("AMBIGUOUS_ASSET")));
        assertFalse(new DocumentLoader().isCurrent(snapshot));
    }

    @Test
    void malformedAssetReferenceCannotPassCurrentInputCheck() throws Exception {
        Path main = write("main.tex", "\\includegraphics\\figure\n");

        DocumentSnapshot snapshot = new DocumentLoader().load(main);

        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(item -> item.code().equals("UNRESOLVED_ASSET")));
        assertFalse(new DocumentLoader().isCurrent(snapshot));
    }

    @Test
    void unclosedMathReportsUncertainContext() throws Exception {
        Path main = write("main.tex", "$n");

        DocumentSnapshot snapshot = new DocumentLoader().load(main);
        TexSourceScanner.Scan requested = new TexSourceScanner("$n").scanFor('n');

        assertEquals(SourceContext.UNKNOWN, requested.occurrences().getFirst().reason());
        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(item -> item.code().equals("UNCLOSED_STRUCTURE")));
    }

    @Test
    void mathIffCommandDoesNotStartAConditional() throws Exception {
        Path main = write("main.tex", "$a \\iff n$\n");

        DocumentSnapshot snapshot = new DocumentLoader().load(main);

        assertEquals(SourceContext.MATH,
                new TexSourceScanner("$a \\iff n$\n").scanFor('n').occurrences().getFirst().reason());
        assertTrue(snapshot.diagnostics().isEmpty());
    }

    @Test
    void unresolvedIncludeKeepsItsUncertainRegionAndDiagnostic() throws Exception {
        String text = "\\input% n\n{\\name}\n\\include{unknown name}\n$n$";
        DocumentSnapshot snapshot = new DocumentLoader().load(write("main.tex", text));
        TexSourceScanner.Scan requested = new TexSourceScanner(text).scanFor('n');

        assertFalse(snapshot.complete());
        assertEquals(text.chars().filter(character -> character == 'n').count(),
                requested.occurrences().size());
        assertEquals(requested.occurrences().size(), requested.occurrences().stream()
                .map(TexSourceScanner.RawOccurrence::charIndex).distinct().count());
        assertEquals(SourceContext.UNKNOWN,
                occurrenceAt(requested, text, "\\name", 1).reason());
        assertEquals(SourceContext.MATH,
                occurrenceAt(requested, text, "$n$", 1).reason());
    }

    @Test
    void protectsCommentSeparatedStarredOptionalAndSingleTokenArguments() throws Exception {
        String text = "$\\text% n\n{n} + \\operatorname*{n} + \\cite[n][n]{n}"
                + " + \\text n + \\cite[{n]}]{key} + n$";
        DocumentSnapshot snapshot = new DocumentLoader().load(write("main.tex", text));
        TexSourceScanner.Scan requested = new TexSourceScanner(text).scanFor('n');

        assertTrue(snapshot.diagnostics().isEmpty());
        assertEquals(text.chars().filter(character -> character == 'n').count(),
                requested.occurrences().size());
        assertEquals(SourceContext.TEXT_ARGUMENT,
                occurrenceAt(requested, text, "\\text n", 6).reason());
        assertEquals(SourceContext.MATH,
                occurrenceAt(requested, text, " + n$", 3).reason());
        int optionalN = byteOffset(text, text.indexOf("\\cite[n]") + 6);
        assertTrue(snapshot.protectedRegions().stream().anyMatch(region ->
                region.startByte() <= optionalN && optionalN < region.endByte()));
    }

    @Test
    void doubleBackslashDoesNotEscapeDefinitionClosingBrace() throws Exception {
        String text = "\\newcommand{\\foo}{n\\\\}\n"
                + "\\newcommand% n\n{\\bar}% n\n{n}\n$n$";
        DocumentSnapshot snapshot = new DocumentLoader().load(write("main.tex", text));
        TexSourceScanner.Scan requested = new TexSourceScanner(text).scanFor('n');

        assertTrue(snapshot.diagnostics().isEmpty());
        assertEquals(text.chars().filter(character -> character == 'n').count(),
                requested.occurrences().size());
        assertEquals(SourceContext.MATH, occurrenceAt(requested, text, "$n$", 1).reason());
    }

    @Test
    void recognizesCarriageReturnAndCrLfWithoutChangingByteSpans() throws Exception {
        for (String ending : List.of("\r", "\r\n", "\n")) {
            String text = "% n" + ending + "π $n$";
            DocumentSnapshot snapshot = new DocumentLoader().load(write("main.tex", text));
            TexSourceScanner scanner = new TexSourceScanner(text);
            TexSourceScanner.Scan requested = scanner.scanFor('n');

            TexSourceScanner.RawOccurrence occurrence = occurrenceAt(requested, text, "$n$", 1);
            assertEquals(SourceContext.MATH, occurrence.reason());
            assertEquals(2, scanner.line(occurrence.charIndex()));
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),
                    snapshot.files().get(Path.of("main.tex")).bytes());
        }
    }

    @Test
    void conditionalBranchCannotLeakMathStateIntoLaterContext() throws Exception {
        String text = "\\iffalse $\\fi n$";
        DocumentSnapshot snapshot = new DocumentLoader().load(write("main.tex", text));

        assertEquals(SourceContext.UNKNOWN,
                occurrenceAt(new TexSourceScanner(text).scanFor('n'), text, " n$", 1).reason());
        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(item -> item.code().equals("CONDITIONAL_STRUCTURE")));
    }

    @Test
    void newifDeclarationDoesNotOpenBranchAndBalancedBranchesKeepLaterMathContext()
            throws Exception {
        String text = "\\newif\\ifdraft\n\\ifdraft $n$\\else $n$\\fi\n$n$";
        DocumentSnapshot snapshot = new DocumentLoader().load(write("main.tex", text));
        TexSourceScanner.Scan requested = new TexSourceScanner(text).scanFor('n');

        assertEquals(3, requested.occurrences().stream()
                .filter(item -> item.reason() == SourceContext.MATH).count());
        assertEquals(2, requested.occurrences().stream()
                .filter(TexSourceScanner.RawOccurrence::conditional).count());
        assertTrue(snapshot.diagnostics().isEmpty());
    }

    @Test
    void malformedAssetOptionsCannotPassFreshnessCheck() throws Exception {
        DocumentSnapshot snapshot = new DocumentLoader().load(
                write("main.tex", "$\\includegraphics[n]\\figure$"));

        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(item -> item.code().equals("UNRESOLVED_ASSET")));
        assertFalse(new DocumentLoader().isCurrent(snapshot));
    }

    @Test
    void nestedDependenciesResolveFromSelectedProjectRoot() throws Exception {
        Files.createDirectory(temporaryDirectory.resolve("chapters"));
        Path main = write("main.tex", "\\input{chapters/one}");
        write("chapters/one.tex", "\\input% n\n{shared}\\includegraphics*% n\n{figure}");
        write("shared.tex", "$n$");
        write("chapters/shared.tex", "$n+n$");
        Files.write(temporaryDirectory.resolve("figure.pdf"), new byte[] {1});
        Files.write(temporaryDirectory.resolve("chapters/figure.pdf"), new byte[] {2});

        DocumentSnapshot snapshot = new DocumentLoader().load(main);

        assertTrue(snapshot.complete());
        assertTrue(snapshot.diagnostics().isEmpty());
        assertTrue(snapshot.files().containsKey(Path.of("shared.tex")));
        assertFalse(snapshot.files().containsKey(Path.of("chapters/shared.tex")));
        assertEquals(Path.of("figure.pdf"), snapshot.assets().getFirst().target());
    }

    @Test
    void currentCheckRejectsRetargetedIncludeEvenWhenOldTargetIsUnchanged() throws Exception {
        Path main = write("main.tex", "\\input{linked}");
        Path first = write("first.tex", "$n$");
        Path second = write("second.tex", "$n$");
        Path link = Files.createSymbolicLink(temporaryDirectory.resolve("linked.tex"), first);
        DocumentLoader loader = new DocumentLoader();
        DocumentSnapshot snapshot = loader.load(main);

        Files.delete(link);
        Files.createSymbolicLink(link, second);
        assertFalse(loader.isCurrent(snapshot));
        DocumentSnapshot refreshed = loader.load(main);
        assertTrue(loader.isCurrent(refreshed));
        assertNotEquals(snapshot.fingerprint(), refreshed.fingerprint());
        Files.delete(link);
        assertFalse(loader.isCurrent(refreshed));
    }

    @Test
    void currentCheckRejectsRetargetedAssetAndNewExtensionAmbiguity() throws Exception {
        Path main = write("main.tex", "\\includegraphics{figure}");
        Path first = Files.write(temporaryDirectory.resolve("first.pdf"), new byte[] {1});
        Path second = Files.write(temporaryDirectory.resolve("second.pdf"), new byte[] {1});
        Path link = Files.createSymbolicLink(temporaryDirectory.resolve("figure.pdf"), first);
        DocumentLoader loader = new DocumentLoader();
        DocumentSnapshot snapshot = loader.load(main);

        Files.delete(link);
        Files.createSymbolicLink(link, second);
        assertFalse(loader.isCurrent(snapshot));
        DocumentSnapshot refreshed = loader.load(main);
        assertTrue(loader.isCurrent(refreshed));
        Files.write(temporaryDirectory.resolve("figure.png"), new byte[] {2});
        assertFalse(loader.isCurrent(refreshed));
    }

    @Test
    void emptyBibliographyEntriesCannotPassCurrentCheck() throws Exception {
        DocumentSnapshot snapshot = new DocumentLoader().load(
                write("main.tex", "\\bibliography{,}"));

        assertFalse(snapshot.assets().isEmpty());
        assertTrue(snapshot.assets().stream().noneMatch(item -> item.resolved()));
        assertFalse(new DocumentLoader().isCurrent(snapshot));
    }

    private Path write(String name, String text) throws Exception {
        return Files.writeString(temporaryDirectory.resolve(name), text);
    }

    private TexSourceScanner.RawOccurrence occurrenceAt(TexSourceScanner.Scan scan, String text,
            String anchor, int offset) {
        int characterIndex = text.indexOf(anchor) + offset;
        List<TexSourceScanner.RawOccurrence> matching = scan.occurrences().stream()
                .filter(item -> item.charIndex() == characterIndex).toList();
        assertEquals(1, matching.size(), "Expected one requested occurrence in " + anchor);
        return matching.getFirst();
    }

    private int byteOffset(String text, int characterIndex) {
        return text.substring(0, characterIndex).getBytes(StandardCharsets.UTF_8).length;
    }
}
