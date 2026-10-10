package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TextReplacementWorkflowTest {
    @TempDir Path directory;

    @Test
    void eachStaleApprovalNeedsNewPreviewAndStopsAfterThreeAttempts() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello").toRealPath();
        int[] approvals = {0};
        BufferedReader input = new BufferedReader(new StringReader("hello\nworld\n1\n2\ny\napply\ny\napply\ny\napply\n")) {
            @Override public String readLine() throws IOException {
                String answer = super.readLine();
                if ("apply".equals(answer)) {
                    Files.writeString(source, "hello " + ++approvals[0]);
                }
                return answer;
            }
        };
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        int code = new TextReplacementWorkflow(input, new PrintWriter(output), new PrintWriter(errors),
                source, true, new TexCompileGate(null, true, null, null)).run();

        assertEquals(2, code);
        assertEquals(3, approvals[0]);
        assertEquals(3, count(output, "Proposed changes to saved source:"));
        assertEquals(3, count(output, "Type apply"));
        assertEquals(2, count(output, "Saved source changed; reloading"));
        assertEquals("hello 3", Files.readString(source));
        assertTrue(errors.toString().contains("Saved source changed; reload and review"));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void stalePreviewRetriesWithoutAskingApprovalAndKeepsExhaustionMessage() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello").toRealPath();
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        int[] previews = {0};
        PrintWriter out = new PrintWriter(output) {
            @Override public void println(String value) {
                super.println(value);
                if (value.equals("Review meaning and rendering before applying.")) {
                    try {
                        Files.writeString(source, "hello " + ++previews[0]);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                }
            }
        };
        int code = new TextReplacementWorkflow(new BufferedReader(new StringReader("hello\nworld\n1\n2\ny\ny\ny\n")),
                out, new PrintWriter(errors), source, false, new TexCompileGate(null, true, null, null)).run();

        assertEquals(2, code);
        assertEquals(3, previews[0]);
        assertEquals(0, count(output, "Type apply"));
        assertTrue(errors.toString().contains("Saved source changed; reload and review"));
        assertEquals("hello 3", Files.readString(source));
    }

    @Test
    void compilationStalenessRequiresRenewedApprovalButCompilerFailureDoesNotRetry() throws Exception {
        for (boolean stale : new boolean[] {true, false}) {
            Path source = Files.writeString(directory.resolve("main.tex"), "hello").toRealPath();
            int[] compilations = {0};
            TexCompileGate gate = new TexCompileGate(Path.of("/fake/pdflatex"), false, source,
                    (command, cwd, timeout) -> {
                        compilations[0]++;
                        if (!stale) return 1;
                        Files.writeString(source, "hello external");
                        Files.writeString(cwd.resolve(".texsuite-validation/texsuite-validation.pdf"), "pdf");
                        return 0;
                    });
            StringWriter output = new StringWriter();
            StringWriter errors = new StringWriter();
            int code = new TextReplacementWorkflow(new BufferedReader(new StringReader(
                    "hello\nworld\n1\n2\ny\napply\ny\n\n")), new PrintWriter(output), new PrintWriter(errors),
                    source, false, gate).run();

            assertEquals(stale ? 0 : 2, code);
            assertEquals(1, compilations[0]);
            assertEquals(stale ? 2 : 1, count(output, "Type apply"));
            assertEquals(stale ? 1 : 0, count(output, "Saved source changed; reloading"));
            assertEquals(stale ? "hello external" : "hello", Files.readString(source));
        }
    }

    @Test
    void skippedDetailsStayBoundedAndPrecedeSummary() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "% " + "hello ".repeat(23)).toRealPath();
        for (boolean debug : new boolean[] {true, false}) {
            StringWriter output = new StringWriter();
            StringWriter errors = new StringWriter();
            int code = new TextReplacementWorkflow(new BufferedReader(new StringReader("hello\nworld\n1\n2\n")),
                    new PrintWriter(output), new PrintWriter(errors), source, debug,
                    new TexCompileGate(null, true, null, null)).run();
            assertEquals(0, code);
            assertEquals(debug ? 20 : 0, count(output, "  SKIPPED "));
            assertTrue(output.toString().contains("23 match(es), 0 proposed, 23 skipped."));
            if (debug) {
                assertTrue(output.toString().indexOf("3 further skipped match(es).")
                        < output.toString().indexOf("Replace text:"));
            }
            assertEquals("", errors.toString());
        }
    }

    @Test
    void reviewsEachOccurrenceAndPreviewsOnlyAcceptedEdits() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello hello").toRealPath();
        Result result = run(source, "hello\nworld\n1\n2\nmaybe\ny\nn\napply\n",
                new TexCompileGate(null, true, null, null));

        assertEquals(0, result.code(), result.errors());
        assertEquals("world hello", Files.readString(source));
        assertTrue(result.output().contains("Choose y or n"));
        assertTrue(result.output().contains("1 replacement(s) accepted"));
        String preview = result.output().substring(result.output().indexOf("Proposed changes"));
        assertTrue(preview.contains("main.tex:1:1"));
        assertFalse(preview.contains("main.tex:1:7"));
    }

    @Test
    void emptySelectionAndCancellationNeverCompileOrWrite() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello hello").toRealPath();
        for (String answers : new String[] {"\nn\n", "y\nquit\n", "y\n", "y\ny\n\n"}) {
            Result result = run(source, "hello\nworld\n1\n2\n" + answers,
                    new TexCompileGate(Path.of("/fake/pdflatex"), false, source,
                            (command, cwd, timeout) -> { fail("Must not compile"); return 1; }));

            assertEquals(0, result.code(), result.errors());
            assertEquals("hello hello", Files.readString(source));
            assertFalse(Files.exists(directory.resolve(".tex-suite")));
        }
    }

    @Test
    void staleReloadRepeatsIndividualDecisions() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "hello hello").toRealPath();
        BufferedReader input = new BufferedReader(new StringReader(
                "hello\nworld\n1\n2\ny\nn\napply\nn\ny\napply\n")) {
            private boolean changed;
            @Override public String readLine() throws IOException {
                String answer = super.readLine();
                if ("apply".equals(answer) && !changed) {
                    Files.writeString(source, "hello hello!");
                    changed = true;
                }
                return answer;
            }
        };
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        int code = new TextReplacementWorkflow(input, new PrintWriter(output), new PrintWriter(errors),
                source, false, new TexCompileGate(null, true, null, null)).run();

        assertEquals(0, code, errors.toString());
        assertEquals("hello world!", Files.readString(source));
        assertEquals(4, count(output, "Replace this occurrence?"));
    }

    @Test
    void chaptersConfirmHintOrRequestMainBeforeFinalApproval() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"),
                "\\documentclass{article}\\begin{document}\\input{chapter}\\end{document}");
        for (String selection : new String[] {"y\n", "n\nmain.tex\n", "n\nmissing.tex\nmain.tex\n"}) {
            Path chapter = Files.writeString(directory.resolve("chapter.tex"),
                    "% !TEX root = main.tex\nhello hello").toRealPath();
            int[] calls = {0};
            TexCompileGate gate = new TexCompileGate(Path.of("/fake/pdflatex"), false, null,
                    (command, cwd, timeout) -> {
                        calls[0]++;
                        assertEquals("./main.tex", command.getLast());
                        assertEquals("% !TEX root = main.tex\nworld hello", Files.readString(cwd.resolve("chapter.tex")));
                        assertEquals(Files.readString(main), Files.readString(cwd.resolve("main.tex")));
                        Files.writeString(cwd.resolve(".texsuite-validation/texsuite-validation.pdf"), "pdf");
                        return 0;
                    });
            Result result = run(chapter, "hello\nworld\n1\n1\ny\nn\n" + selection + "apply\n", gate);

            assertEquals(0, result.code(), result.errors());
            assertEquals(1, calls[0]);
            assertTrue(result.output().contains("Use compilation main main.tex?"));
            assertTrue(result.output().indexOf("Compilation main:") < result.output().indexOf("Type apply"));
        }
    }

    @Test
    void mainMustContainEditedChapterAndUnresolvedIncludesAreRejected() throws Exception {
        Files.writeString(directory.resolve("wrong.tex"), "\\begin{document}other\\end{document}");
        Files.writeString(directory.resolve("broken.tex"), "\\input{chapter}\\input{missing}");
        Files.writeString(directory.resolve("conditional.tex"), "\\iftrue\\input{chapter}\\fi");
        Path chapter = Files.writeString(directory.resolve("chapter.tex"), "hello").toRealPath();
        Result result = run(chapter, "hello\nworld\n1\n1\ny\nwrong.tex\nbroken.tex\nconditional.tex\n\n",
                new TexCompileGate(Path.of("/fake/pdflatex"), false, null,
                        (command, cwd, timeout) -> { fail("Invalid mains must not compile"); return 1; }));

        assertEquals(0, result.code(), result.errors());
        assertEquals("hello", Files.readString(chapter));
        assertTrue(result.output().contains("unconditional include closure"));
        assertTrue(result.output().contains("included source is missing: missing.tex"), result.output());
        assertFalse(result.output().contains("Type apply"));
    }

    @Test
    void explicitMainOverridesHintAndStandaloneMainNeedsNoPrompt() throws Exception {
        Path main = Files.writeString(directory.resolve("main.tex"),
                "\\begin{document}hello\\input{chapter}\\end{document}").toRealPath();
        Path chapter = Files.writeString(directory.resolve("chapter.tex"),
                "% !TEX root = missing.tex\nhello").toRealPath();
        for (Path selected : new Path[] {chapter, main}) {
            TexCompileGate gate = new TexCompileGate(Path.of("/fake/pdflatex"), false,
                    selected.equals(chapter) ? main : null, (command, cwd, timeout) -> {
                        assertEquals("./main.tex", command.getLast());
                        Files.writeString(cwd.resolve(".texsuite-validation/texsuite-validation.pdf"), "pdf");
                        return 0;
                    });
            Result result = run(selected, "hello\nworld\n1\n1\ny\napply\n", gate);

            assertEquals(0, result.code(), result.errors());
            assertFalse(result.output().contains("Use compilation main"));
            assertFalse(result.output().contains("Selected file is a chapter"));
        }
    }

    @Test
    void outsideRootAndInvalidHintAreRejectedWithoutApproval() throws Exception {
        Path chapter = Files.writeString(directory.resolve("chapter.tex"),
                "% !TEX root = missing.tex\nhello").toRealPath();
        Path outside = Files.createTempFile("texsuite-outside-", ".tex");
        try {
            Result result = run(chapter, "hello\nworld\n1\n1\ny\ny\n" + outside + "\n\n",
                    new TexCompileGate(Path.of("/fake/pdflatex"), false, null,
                            (command, cwd, timeout) -> { fail("Must not compile"); return 1; }));

            assertEquals(0, result.code(), result.errors());
            assertTrue(result.output().contains("inside the displayed project root"));
            assertFalse(result.output().contains("Type apply"));
            assertEquals("% !TEX root = missing.tex\nhello", Files.readString(chapter));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private Result run(Path source, String answers, TexCompileGate gate) {
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        int code = new TextReplacementWorkflow(new BufferedReader(new StringReader(answers)),
                new PrintWriter(output), new PrintWriter(errors), source, false, gate).run();
        return new Result(code, output.toString(), errors.toString());
    }

    private record Result(int code, String output, String errors) { }

    private static int count(StringWriter output, String text) {
        return output.toString().split(java.util.regex.Pattern.quote(text), -1).length - 1;
    }
}
