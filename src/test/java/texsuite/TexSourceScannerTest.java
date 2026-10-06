package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import texsuite.DocumentSnapshot.SourceContext;

final class TexSourceScannerTest {
    @Test
    void documentStartIgnoresCommentsDefinitionsVerbatimAndConditionals() {
        for (String source : new String[] {"% \\begin{document}\nchapter",
                "\\newcommand{\\foo}{\\begin{document}}chapter",
                "\\begin{verbatim}\\begin{document}\\end{verbatim}",
                "\\iffalse\\begin{document}\\fi"}) {
            TexSourceScanner scanner = new TexSourceScanner(source);
            scanner.scan();
            assertFalse(scanner.hasDocumentStart(), source);
        }
        TexSourceScanner scanner = new TexSourceScanner("\\begin{document}hello\\end{document}");
        scanner.scan();
        assertTrue(scanner.hasDocumentStart());
    }

    @Test
    void characterAdapterAndStringsShareExplicitContextExpectations() {
        record Example(String source, SourceContext context) { }
        for (Example example : List.of(
                new Example("nn", SourceContext.PROSE),
                new Example("$nn$", SourceContext.MATH),
                new Example("% nn", SourceContext.COMMENT),
                new Example("\\verb|nn|", SourceContext.VERBATIM),
                new Example("\\newcommand{\\foo}{nn}", SourceContext.DEFINITION),
                new Example("\\text{nn}", SourceContext.TEXT_ARGUMENT),
                new Example("\\label{nn}", SourceContext.METADATA),
                new Example("\\nn", SourceContext.CONTROL_SEQUENCE),
                new Example("$nn", SourceContext.UNKNOWN),
                new Example("{$nn$", SourceContext.UNKNOWN),
                new Example("\\begin{equation}nn", SourceContext.UNKNOWN))) {
            TexSourceScanner scanner = new TexSourceScanner(example.source());
            var chars = scanner.scanFor('n');
            assertEquals(chars, scanner.scanFor("n"), example.source());
            assertTrue(chars.occurrences().stream()
                    .filter(o -> o.charIndex() >= example.source().indexOf("nn")
                            && o.charIndex() < example.source().indexOf("nn") + 2)
                    .allMatch(o -> o.reason() == example.context()),
                    example.source());
            var strings = scanner.scanFor("nn");
            assertEquals(1, strings.occurrences().size(), example.source());
            assertEquals(example.context(), strings.occurrences().getFirst().reason(), example.source());
            assertEquals(chars.problems(), strings.problems());
        }
    }

    @Test
    void unfinishedLaterMathDoesNotDowngradeCompletedEarlierMath() {
        TexSourceScanner scanner = new TexSourceScanner("$nn$ prose $nn");
        assertEquals(List.of(SourceContext.MATH, SourceContext.UNKNOWN),
                scanner.scanFor("nn").occurrences().stream().map(TexSourceScanner.RawOccurrence::reason).toList());
        assertEquals(List.of(SourceContext.MATH, SourceContext.MATH,
                        SourceContext.UNKNOWN, SourceContext.UNKNOWN),
                scanner.scanFor('n').occurrences().stream().map(TexSourceScanner.RawOccurrence::reason).toList());
    }

    @Test
    void overlapAndConditionalFlagsSurviveUnifiedMatching() {
        var scan = new TexSourceScanner("\\ifdraft $nnn$\\fi $nn$").scanFor("nn");
        assertEquals(3, scan.occurrences().size());
        assertTrue(scan.overlaps(0, 2));
        assertTrue(scan.overlaps(1, 2));
        assertFalse(scan.overlaps(2, 2));
        assertEquals(List.of(true, true, false),
                scan.occurrences().stream().map(TexSourceScanner.RawOccurrence::conditional).toList());
        assertTrue(scan.occurrences().stream().allMatch(o -> o.reason() == SourceContext.MATH));
    }

    @Test
    void unicodePositionsAndNewlinesAreIndependentOfTargetLength() {
        for (String newline : List.of("\n", "\r", "\r\n")) {
            String text = "π😀" + newline + "$α😀$";
            TexSourceScanner scanner = new TexSourceScanner(text);
            var match = scanner.scanFor("α😀").occurrences().getFirst();
            assertEquals(SourceContext.MATH, match.reason());
            assertEquals(2, scanner.line(match.charIndex()));
            assertEquals(2, scanner.column(match.charIndex()));
            assertEquals(("π😀" + newline + "$").getBytes(StandardCharsets.UTF_8).length,
                    scanner.byteOffset(match.charIndex()));
        }
    }

    @Test
    void protectedContextsAndBraceBoundariesRetainTheirPrecedence() {
        TexSourceScanner scanner = new TexSourceScanner("$nn \\text{nn}$");
        assertEquals(SourceContext.CONTROL_SEQUENCE,
                scanner.scanFor("nn \\text{nn}").occurrences().getFirst().reason());
        scanner = new TexSourceScanner("$n{n}n$");
        scanner.scanFor("n{n");
        assertTrue(scanner.partialGroup(1, 4));
        assertFalse(scanner.partialGroup(1, 6));
        scanner.scan();
        assertFalse(scanner.partialGroup(1, 4));
        scanner.scanFor('n');
        assertTrue(scanner.partialGroup(1, 4));
    }
}
