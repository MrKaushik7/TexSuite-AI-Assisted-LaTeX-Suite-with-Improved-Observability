package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline classifiers exercise batch approval without external credentials or provider calls. */
final class BatchReplacementTest {
    @TempDir Path directory;

    @Test
    void hundredOccurrencesUseOneApplyApprovalAndSkipKeepAndUncertainty() throws Exception {
        String original = "$" + String.join("+", java.util.Collections.nCopies(100, "n")) + "$\n% n";
        Path source = Files.writeString(directory.resolve("main.tex"), original).toRealPath();
        var client = new Fixture();
        var output = new StringWriter();
        var errors = new StringWriter();
        String consent = "y\n".repeat(13);
        int code = new MathematicalRenameWorkflow(reader("1\n" + consent + "diff\napply\n"),
                new PrintWriter(output), new PrintWriter(errors), false,
                new TexCompileGate(null, true, null, null), client)
                .run(new RenameRequest(source, "n", "database length", "m", RenameRequest.Scope.FILE));

        assertEquals(0, code, errors.toString());
        assertEquals(13, client.requests.size());
        assertEquals(100, client.requests.stream().mapToInt(item -> item.candidates().size()).sum());
        assertEquals("$n+n+" + String.join("+", java.util.Collections.nCopies(98, "m")) + "$\n% n", Files.readString(source));
        assertTrue(output.toString().contains("selected: 98; kept: 1; uncertain/skipped: 1; protected/excluded: 1"));
        assertFalse(output.toString().contains("this occurrence?"));
        assertFalse(output.toString().contains("Type apply"));
        assertEquals(2, output.toString().split("Batch \\[", -1).length - 1); // optional diff, then one apply
        var history = EditHistory.list(directory.toRealPath()).getFirst();
        assertEquals("complete", history.status());
        assertEquals(98, history.record().edits().size());
        assertEquals("fixture-model", history.record().intent().model());
    }

    @Test
    void cancellationAndAllUncertainNeverCompileWriteOrCreateHistory() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "$n+n$").toRealPath();
        for (boolean uncertain : List.of(false, true)) {
            var client = new Fixture();
            client.allUncertain = uncertain;
            var errors = new StringWriter();
            int code = new MathematicalRenameWorkflow(reader("1\ny\n\n"), new PrintWriter(new StringWriter()),
                    new PrintWriter(errors), false, new TexCompileGate(Path.of("/fake/pdflatex"), false, source,
                            (command, cwd, timeout) -> { fail("Cancelled/empty batch must not compile"); return 1; }), client)
                    .run(new RenameRequest(source, "n", "length", "m", RenameRequest.Scope.FILE));
            assertEquals(0, code, errors.toString());
            assertEquals("$n+n$", Files.readString(source));
            assertFalse(Files.exists(directory.resolve(".tex-suite")));
        }
    }

    @Test
    void oversizedContextIsSkippedWithoutAnApiCallOrWrite() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "a ".repeat(12_001) + "$n$").toRealPath();
        var client = new Fixture();
        var output = new StringWriter();
        int code = new MathematicalRenameWorkflow(reader("1\napply\n"), new PrintWriter(output),
                new PrintWriter(new StringWriter()), false, new TexCompileGate(null, true, null, null), client)
                .run(new RenameRequest(source, "n", "length", "m", RenameRequest.Scope.FILE));
        assertEquals(0, code);
        assertTrue(client.requests.isEmpty());
        assertTrue(output.toString().contains("selected: 0; kept: 0; uncertain/skipped: 1"));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void sourceReroutingRequiresExplicitChoiceAndTextAiUsesTheStatedPurpose() throws Exception {
        String original = "\\usepackage{pifont}\n\\tikzset{>={Latex[width=0.5mm,length=1mm]}}\n"
                + "\\begin{document}font prose\\end{document}";
        Path source = Files.writeString(directory.resolve("main.tex"), original).toRealPath();
        var client = new Fixture();
        client.allReplace = true;
        var output = new StringWriter();
        var errors = new StringWriter();
        int code = new TextReplacementWorkflow(reader("pifont\nfont\n\n\nchange package name only\ny\n1\ny\napply\n"),
                new PrintWriter(output), new PrintWriter(errors), source, false,
                new TexCompileGate(null, true, null, null), client).run();
        assertEquals(0, code, errors.toString());
        assertEquals(original.replace("pifont", "font"), Files.readString(source));
        assertTrue(output.toString().contains("Try these matches in the LaTeX source region?"));
        assertEquals("change package name only", client.requests.getFirst().meaning());
        var history = EditHistory.list(directory.toRealPath()).getFirst().record();
        assertEquals("SOURCE", history.intent().region());
        assertEquals("text-replacement", history.intent().operation());
        assertEquals("pifont", history.edits().getFirst().oldText());

        output = new StringWriter();
        errors = new StringWriter();
        code = new MathematicalRenameWorkflow(reader("y\ny\napply\n"), new PrintWriter(output),
                new PrintWriter(errors), false, new TexCompileGate(null, true, null, null))
                .run(new RenameRequest(source, "0.5", "width", "0.75", RenameRequest.Scope.FILE));
        assertEquals(0, code, errors.toString());
        assertEquals(original.replace("pifont", "font").replace("0.5", "0.75"), Files.readString(source));
        assertTrue(output.toString().contains("Continue this literal request in LaTeX source replacement?"));
        assertFalse(output.toString().contains("REASON: prose"));
    }

    @Test
    void declinedRegionReroutingPreservesThePreamble() throws Exception {
        String original = "\\usepackage{pifont}\n\\begin{document}hello\\end{document}";
        Path source = Files.writeString(directory.resolve("main.tex"), original).toRealPath();
        int code = new TextReplacementWorkflow(reader("pifont\nfont\n\n\nn\n"),
                new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()), source,
                false, new TexCompileGate(null, true, null, null)).run();
        assertEquals(0, code);
        assertEquals(original, Files.readString(source));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void invalidTextPurposeIsRetriedBeforeClassification() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"),
                "\\begin{document}hello\\end{document}").toRealPath();
        var client = new Fixture();
        client.allReplace = true;
        var errors = new StringWriter();
        int code = new TextReplacementWorkflow(reader("hello\nworld\n\n\nbad\u0001purpose\nvalid purpose\n1\ny\napply\n"),
                new PrintWriter(new StringWriter()), new PrintWriter(errors), source, false,
                new TexCompileGate(null, true, null, null), client).run();
        assertEquals(0, code, errors.toString());
        assertEquals("valid purpose", client.requests.getFirst().meaning());
        assertEquals("\\begin{document}world\\end{document}", Files.readString(source));
    }

    @Test
    void wholeGreekTokensPreserveScriptsAndProtectDefinitionsCommandsAndComments() throws Exception {
        String original = "$a\\alpha+\\alpha_1+\\alpha^2+\\alphabet+\\text{\\alpha}$\n"
                + "% " + "\\alpha ".repeat(9) + "\n"
                + "\\newcommand{\\named}{\\alpha}\n\\verb|\\alpha|\n"
                + "$" + String.join("+", java.util.Collections.nCopies(7, "\\alpha")) + "$";
        Path source = Files.writeString(directory.resolve("main.tex"), original).toRealPath();
        var snapshot = new DocumentLoader().loadFile(source);
        var intent = new RenameRequest(source, "\\alpha", "alpha symbol", "\\beta", RenameRequest.Scope.FILE);
        var inventory = new RenameCandidateDiscovery().discover(snapshot, intent);
        assertEquals(10, inventory.count(RenameCandidateDiscovery.Status.CANDIDATE));
        assertEquals(13, inventory.count(RenameCandidateDiscovery.Status.EXCLUDED));
        var scanner = new TexSourceScanner("$\\alpha$");
        assertEquals(DocumentSnapshot.SourceContext.CONTROL_SEQUENCE, scanner.scanFor("a").occurrences().getFirst().reason());
        var client = new Fixture();
        client.allReplace = true;
        var errors = new StringWriter();
        assertEquals(0, new MathematicalRenameWorkflow(reader("1\ny\ny\napply\n"), new PrintWriter(new StringWriter()),
                new PrintWriter(errors), false, new TexCompileGate(null, true, null, null), client).run(intent), errors.toString());
        String expected = original.replace("$a\\alpha+\\alpha_1+\\alpha^2", "$a\\beta+\\beta_1+\\beta^2")
                .replace("$" + String.join("+", java.util.Collections.nCopies(7, "\\alpha")) + "$",
                        "$" + String.join("+", java.util.Collections.nCopies(7, "\\beta")) + "$");
        assertEquals(expected, Files.readString(source));
    }

    private static BufferedReader reader(String answers) {
        return new BufferedReader(new StringReader(answers));
    }

    private static final class Fixture implements ModelClient {
        private final List<Request> requests = new ArrayList<>();
        private int candidateCount;
        private boolean allUncertain;
        private boolean allReplace;

        @Override public String destination() { return "http://fixture.invalid"; }

        @Override public String model() { return "fixture-model"; }

        @Override public String preview(Request request) { return "synthetic preview"; }

        @Override public Result classify(Request request) {
            requests.add(request);
            return new Result(request.candidates().stream().map(item -> {
                int index = candidateCount++;
                Action action = allReplace ? Action.REPLACE : allUncertain || index == 1
                        ? Action.NEEDS_HUMAN_REVIEW : index == 0 ? Action.KEEP : Action.REPLACE;
                return new Decision(item.id(), action, "offline fixture decision", 0.9);
            }).toList());
        }
    }
}
