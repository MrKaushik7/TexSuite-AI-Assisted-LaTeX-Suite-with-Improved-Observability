package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SanitationPlannerTest {
    @TempDir Path directory;

    @Test
    void goldenCleanupPreservesProtectedBytesParagraphsAndEof() throws Exception {
        String source = "\\documentclass{article}\n\\newcommand{\\value}{two  }\n\\begin{document}\r\n"
                + "Café é 😀.  \t\r\n\n  \n% comment  \n\\verb|literal  |  \n"
                + "\\begin{verbatim}\nprotected  \n\\end{verbatim}\n"
                + "\\iftrue\nconditional  \n\\fi\n\\textbf{command}  \n"
                + "\\value  \ncontrol\\  \nLast prose. \t\n\\end{document}";
        var snapshot = snapshot(source);
        var report = new SanitationPlanner().inspect(snapshot);
        assertEquals(2, report.trailing().size());
        var updated = new TextEditPlan(snapshot, report.trailing(), gate()).preview().get(Path.of("main.tex"));
        assertArrayEquals(bytes(source.replace("Café é 😀.  \t", "Café é 😀.").replace("Last prose. \t", "Last prose.")), updated);
        assertNotNull(report.crlfBlock());
        assertNotNull(report.lfBlock()); // Document boundary's CRLF is intentionally protected.
        assertTrue(report.findings().stream().anyMatch(f -> f.rule().equals("PROTECTED_TRAILING")));
    }

    @Test
    void mixedEolConversionIsAtomicAndIndependentOfTrailingRemoval() throws Exception {
        String source = "\\documentclass{article}\n\\begin{document}\nOne.  \r\nTwo.\rThree. \n\\end{document}\n";
        var snapshot = snapshot(source);
        var report = new SanitationPlanner().inspect(snapshot);
        assertEquals(4, report.lfCount());
        assertEquals(1, report.crlfCount());
        assertEquals(1, report.crCount());
        assertNull(report.lfBlock());
        assertNotNull(report.crlfBlock());
        var edits = new java.util.ArrayList<>(report.trailing());
        edits.addAll(report.lf());
        String expected = "\\documentclass{article}\n\\begin{document}\nOne.\nTwo.\nThree.\n\\end{document}\n";
        assertArrayEquals(bytes(expected), new TextEditPlan(snapshot, edits, gate()).preview().get(Path.of("main.tex")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"% comment  \r\n", "\\begin{verbatim}\nkeep  \r\n\\end{verbatim}\n",
            "\\newcommand{\\x}{a  \r\nb}\n", "\\iftrue\nmaybe  \r\n\\fi\n", "control\\  \r\n",
            "\\textbf{word}  \r\n", "  \r\n", "$x$  \r\n", "\\unknown{\nargument  \r\n}\n", "{\nargument\n}  \r\n"})
    void protectedTerminatorBlocksEntireNormalization(String protectedText) throws Exception {
        var report = new SanitationPlanner().inspect(snapshot("\\begin{document}\n" + protectedText + "Ordinary.  \r\n\\end{document}\n"));
        assertNotNull(report.lfBlock());
        assertTrue(report.lf().isEmpty());
        assertEquals(1, report.trailing().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\catcode`\\ =12\n", "\\obeyspaces\n", "\\endlinechar=-1\n",
            "\\ExplSyntaxOn\n", "\\let\\x\\obeylines\n", "\\csname obeyspaces\\endcsname\n",
            "^^5cobeyspaces\n", "%^^0a\\obeyspaces\n", "\\verb|^^0a|\n", "\\newcommand{\\x}{\\obeyspaces}\n",
            "\\newcommand{\\x}{\\unknown}\n", "\\newcommand{\\α}{\\unknown}\n",
            "\\newcommand{\\x}{\\%}\n", "\\begin{mystery}\n\\end{mystery}\n"})
    void unsupportedInterpretationWithholdsAllFixes(String prefix) throws Exception {
        var report = new SanitationPlanner().inspect(snapshot(prefix + "\\begin{document}\nPlain.  \n\\end{document}\n"));
        assertTrue(report.trailing().isEmpty());
        assertTrue(report.lf().isEmpty());
        assertTrue(report.crlf().isEmpty());
        assertTrue(report.findings().stream().anyMatch(f -> f.rule().equals("UNSUPPORTED_WHITESPACE")));
    }

    @Test
    void hazardousTextInCommentsAndVerbatimDoesNotDisableOrdinaryProse() throws Exception {
        var report = new SanitationPlanner().inspect(snapshot("% \\obeyspaces\n\\begin{document}\n"
                + "\\begin{verbatim}\n\\catcode`x=3\n\\end{verbatim}\nPlain.  \n\\end{document}\n"));
        assertEquals(1, report.trailing().size());
    }

    @Test
    void dependencyHazardsBlockSelectedFileAndIncludeDiagnosticsStayReadOnly() throws Exception {
        Files.writeString(directory.resolve("chapter.tex"), "\\obeyspaces\n");
        var report = new SanitationPlanner().inspect(snapshot("\\begin{document}\nPlain.  \n\\input{chapter}\n\\end{document}\n"));
        assertTrue(report.trailing().isEmpty());
        assertTrue(report.findings().stream().anyMatch(f -> f.path().equals(Path.of("chapter.tex"))));
        assertEquals("\\obeyspaces\n", Files.readString(directory.resolve("chapter.tex")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\input{\\dynamic}", "{", "\\begin{itemize}"})
    void incompleteOrBrokenSyntaxOnlyReports(String broken) throws Exception {
        var report = new SanitationPlanner().inspect(snapshot("\\begin{document}\nPlain.  \n" + broken + "\n\\end{document}"));
        assertTrue(report.trailing().isEmpty());
        assertFalse(report.findings().isEmpty());
    }

    @Test
    void packageAndUnicodeFindingsNeverBecomeEdits() throws Exception {
        String source = "\\documentclass{article}\n\\usepackage[a]{foo,bar}\n\\usepackage[a]{foo}\n"
                + "\\usepackage[b]{bar}\n% \\usepackage{ignored}\n\\begin{document}\n"
                + "Français 日本語 α 😀 é\nNBSP\u00a0thin\u2009invisible\u200b minus−quote“\n\\end{document}\n";
        var snapshot = snapshot(source);
        var report = new SanitationPlanner().inspect(snapshot);
        assertEquals(1, count(report, "DUPLICATE_PACKAGE"));
        assertEquals(1, count(report, "PACKAGE_OPTIONS"));
        assertTrue(report.findings().stream().anyMatch(f -> f.rule().equals("DUPLICATE_PACKAGE") && f.message().startsWith("foo imported again")));
        assertTrue(report.findings().stream().anyMatch(f -> f.rule().equals("PACKAGE_OPTIONS") && f.message().startsWith("bar imported with differing")));
        assertEquals(5, count(report, "UNICODE_REVIEW"));
        assertTrue(report.trailing().isEmpty());
        assertArrayEquals(bytes(source), snapshot.files().get(Path.of("main.tex")).bytes());
    }

    @Test
    void conditionalAndBodyPackageCommandsAreNotPreambleDuplicates() throws Exception {
        var report = new SanitationPlanner().inspect(snapshot("\\usepackage{foo}\n\\iftrue\n\\usepackage{foo}\n\\fi\n"
                + "\\begin{document}\n\\usepackage{foo}\n\\end{document}\n"));
        assertEquals(0, count(report, "DUPLICATE_PACKAGE"));
    }

    @Test
    void limitsAreExplicitAndNeverReturnTruncatedFixGroup() throws Exception {
        var report = new SanitationPlanner().inspect(snapshot("\\begin{document}\n" + "Plain.  \n".repeat(201) + "\\end{document}\n"));
        assertTrue(report.trailing().isEmpty());
        assertEquals(1, count(report, "FIX_LIMIT"));
        var unicode = new SanitationPlanner().inspect(snapshot("\u00a0".repeat(205)));
        assertEquals(200, unicode.findings().size());
        assertEquals(5, unicode.omittedFindings());
    }

    @Test
    void pureProseCanConvertToCrlfWithoutAddingFinalNewline() throws Exception {
        var snapshot = snapshot("First.\nLast.");
        var report = new SanitationPlanner().inspect(snapshot);
        assertNull(report.crlfBlock());
        assertArrayEquals(bytes("First.\r\nLast."), new TextEditPlan(snapshot, report.crlf(), gate()).preview().get(Path.of("main.tex")));
    }

    @Test
    void cyclicIncludeRetainsTheUsefulLoaderErrorAndSource() throws Exception {
        Files.writeString(directory.resolve("chapter.tex"), "\\input{main}\n");
        var failure = assertThrows(DocumentLoader.LoadException.class, () -> snapshot("Plain.  \n\\input{chapter}\n"));
        assertTrue(failure.getMessage().contains("include cycle"));
        assertEquals("Plain.  \n\\input{chapter}\n", Files.readString(directory.resolve("main.tex")));
    }

    @Test
    void eolLimitBlocksTheWholeConversion() throws Exception {
        var report = new SanitationPlanner().inspect(snapshot("Plain.\r".repeat(201)));
        assertTrue(report.lf().isEmpty());
        assertTrue(report.crlf().isEmpty());
        assertTrue(report.lfBlock().contains("200-edit"));
        assertTrue(report.crlfBlock().contains("200-edit"));
    }

    @Test
    void structuralAdvisoryRetainsItsExactLocationAndWithholdingExplanation() throws Exception {
        var report = new SanitationPlanner().inspect(snapshot("\\begin{document}\nPlain.  \n{\n\\end{document}"));
        assertTrue(report.findings().stream().anyMatch(f -> f.rule().equals("UNCLOSED_STRUCTURE")
                && f.line() == 4 && f.column() == 15 && f.message().contains("unclosed")));
        assertEquals(1, count(report, "FIXES_WITHHELD"));
    }

    @Test
    void multipleLiteralDefinitionsDoNotDisablePlainProse() throws Exception {
        var report = new SanitationPlanner().inspect(snapshot("\\newcommand{\\a}{one}\n\\newcommand{\\b}{two}\nPlain.  \n"));
        assertEquals(1, report.trailing().size());
        assertEquals(0, count(report, "UNSUPPORTED_WHITESPACE"));
    }

    @Test
    void ruleProvenanceRejectsNonWhitespaceAndNoOpShapes() {
        assertFalse(SanitationPlanner.validFix(SanitationPlanner.TRAILING, "x", ""));
        assertFalse(SanitationPlanner.validFix(SanitationPlanner.TRAILING, "\u00a0", ""));
        assertFalse(SanitationPlanner.validFix(SanitationPlanner.TRAILING, "", ""));
        assertFalse(SanitationPlanner.validFix(SanitationPlanner.TRAILING, " ", " "));
        assertFalse(SanitationPlanner.validFix(SanitationPlanner.EOL, "\n", "\n"));
        assertFalse(SanitationPlanner.validFix(SanitationPlanner.EOL, "\r", ""));
        assertFalse(SanitationPlanner.validFix(null, " ", ""));
        assertTrue(SanitationPlanner.validFix(SanitationPlanner.TRAILING, " \t", ""));
        assertTrue(SanitationPlanner.validFix(SanitationPlanner.EOL, "\r", "\n"));
    }

    private long count(SanitationPlanner.Report report, String rule) {
        return report.findings().stream().filter(f -> f.rule().equals(rule)).count();
    }

    private DocumentSnapshot snapshot(String source) throws Exception {
        return new DocumentLoader().load(Files.writeString(directory.resolve("main.tex"), source).toRealPath());
    }

    static TexCompileGate gate() { return new TexCompileGate(null, true, null, null); }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
