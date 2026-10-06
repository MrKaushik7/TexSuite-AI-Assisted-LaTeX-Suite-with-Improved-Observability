package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verify selected-only writes and the no-write paths through the real transaction implementation. */
final class MathematicalRenameWorkflowTest {
    @TempDir Path directory;

    @Test
    void selectiveRenameCompilesAcceptedDiffAndBacksUpExactOriginal() throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/rename/manual.tex")) {
            original = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Path source = Files.writeString(directory.resolve("main.tex"), original).toRealPath();
        String expected = original.replace("$n$, with indices $i<n$", "$\\numElements$, with indices $i<\\numElements$");
        int[] calls = {0};
        TexCompileGate gate = new TexCompileGate(Path.of("/fake/pdflatex"), false, source,
                (command, cwd, timeout) -> {
                    calls[0]++;
                    assertEquals(original, Files.readString(source));
                    assertEquals(expected, Files.readString(cwd.resolve("main.tex")));
                    Files.writeString(cwd.resolve(".texsuite-validation/texsuite-validation.pdf"), "pdf");
                    return 0;
                });
        Result result = run(source, "y\ny\nn\napply\n", gate);
        assertEquals(0, result.code(), result.errors());
        assertEquals(1, calls[0]);
        assertEquals(expected, Files.readString(source));
        assertTrue(result.output().contains("--- a/main.tex"));
        assertTrue(result.output().contains("+++ b/main.tex"));
        assertTrue(result.output().contains("-Database length is $n$"));
        assertTrue(result.output().contains("+Database length is $\\numElements$"));
        assertTrue(result.output().contains("Applied 2 rename(s)"));
        try (var files = Files.list(directory.resolve(".tex-suite/plans"))) {
            var plan = new ObjectMapper().readTree(files.findFirst().orElseThrow().toFile());
            assertEquals(1, plan.get("version").asInt());
            assertEquals("manual", plan.get("decisionSource").asText());
            assertEquals(2, plan.get("edits").size());
            assertEquals(TextEditPlan.sha256(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    plan.get("edits").get(0).get("sourceHash").asText());
        }
        try (var files = Files.walk(directory.resolve(".tex-suite/backups"))) {
            assertTrue(files.filter(Files::isRegularFile).anyMatch(path -> {
                try { return Files.readString(path).equals(original); }
                catch (IOException exception) { throw new UncheckedIOException(exception); }
            }));
        }
    }

    @Test
    void cancellationAndEmptySelectionNeverCreateMetadataOrWrite() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "$n+n$").toRealPath();
        for (String answers : new String[] {"n\n\n", "y\nquit\n", "y\n", "y\ny\n\n"}) {
            Result result = run(source, answers, new TexCompileGate(null, true, null, null));
            assertEquals(0, result.code(), result.errors());
            assertEquals("$n+n$", Files.readString(source));
            assertFalse(Files.exists(directory.resolve(".tex-suite")));
        }
    }

    @Test
    void failedCompileLeavesOnlyApprovedIntentAndOriginalSource() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "$n$").toRealPath();
        TexCompileGate gate = new TexCompileGate(Path.of("/fake/pdflatex"), true, source,
                (command, cwd, timeout) -> 1);
        Result result = run(source, "y\napply\n", gate);
        assertEquals(2, result.code());
        assertEquals("$n$", Files.readString(source));
        assertTrue(Files.isDirectory(directory.resolve(".tex-suite/plans")));
        assertFalse(result.output().contains("Applied 1"));
        assertTrue(result.errors().contains("TeX validation failed"));
    }

    @Test
    void staleApprovalRequiresNewDecisionsAndStopsAfterThreeAttempts() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "$n$").toRealPath();
        int[] approvals = {0};
        BufferedReader input = new BufferedReader(new StringReader("y\napply\ny\napply\ny\napply\n")) {
            @Override public String readLine() throws IOException {
                String answer = super.readLine();
                if ("apply".equals(answer)) Files.writeString(source, "$n$ " + ++approvals[0]);
                return answer;
            }
        };
        Result result = run(source, input, new TexCompileGate(null, true, null, null));
        assertEquals(2, result.code());
        assertEquals(3, approvals[0]);
        assertEquals(3, result.output().split("Rename this occurrence", -1).length - 1);
        assertEquals("$n$ 3", Files.readString(source));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    @Test
    void unicodeCrLfAndMissingFinalNewlineArePreserved() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "é😀 $n$\r\n$n$").toRealPath();
        Result result = run(source, "y\nn\napply\n", new TexCompileGate(null, true, null, null));
        assertEquals(0, result.code(), result.errors());
        assertEquals("é😀 $\\numElements$\r\n$n$", Files.readString(source));
    }

    @Test
    void projectScopeEditsBothFilesWhileFileScopeLeavesIncludesAlone() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "$n$\\input{chapter}").toRealPath();
        Path chapter = Files.writeString(directory.resolve("chapter.tex"), "$n$");
        for (var scope : RenameRequest.Scope.values()) {
            Files.writeString(source, "$n$\\input{chapter}");
            Files.writeString(chapter, "$n$");
            StringWriter output = new StringWriter();
            StringWriter errors = new StringWriter();
            String answers = scope == RenameRequest.Scope.FILE ? "y\napply\n" : "y\ny\napply\n";
            int code = new MathematicalRenameWorkflow(new BufferedReader(new StringReader(answers)),
                    new PrintWriter(output), new PrintWriter(errors), false,
                    new TexCompileGate(null, true, null, null)).run(
                    new RenameRequest(source, "n", "length", "m", scope));
            assertEquals(0, code, errors.toString());
            assertEquals("$m$\\input{chapter}", Files.readString(source));
            assertEquals(scope == RenameRequest.Scope.FILE ? "$n$" : "$m$", Files.readString(chapter));
        }
    }

    private Result run(Path source, String input, TexCompileGate gate) {
        return run(source, new BufferedReader(new StringReader(input)), gate);
    }

    @Test
    void contextOnlyDependencyChangesRequireNewOccurrenceDecisions() throws Exception {
        Path source = Files.writeString(directory.resolve("main.tex"), "$n=\\meaning$\\input{defs}").toRealPath();
        Path definitions = Files.writeString(directory.resolve("defs.tex"), "\\newcommand{\\meaning}{one}");
        BufferedReader input = new BufferedReader(new StringReader("y\napply\nn\n")) {
            @Override public String readLine() throws IOException {
                String answer = super.readLine();
                if ("apply".equals(answer)) Files.writeString(definitions, "\\newcommand{\\meaning}{two}");
                return answer;
            }
        };
        Result result = run(source, input, new TexCompileGate(null, true, null, null));

        assertEquals(0, result.code(), result.errors());
        assertEquals(2, result.output().split("Rename this occurrence", -1).length - 1);
        assertTrue(result.output().contains("\\newcommand{\\meaning}{one}"));
        assertTrue(result.output().contains("\\newcommand{\\meaning}{two}"));
        assertTrue(result.output().contains("Saved source changed; reloading"));
        assertEquals("$n=\\meaning$\\input{defs}", Files.readString(source));
        assertFalse(Files.exists(directory.resolve(".tex-suite")));
    }

    private Result run(Path source, BufferedReader input, TexCompileGate gate) {
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        int code = new MathematicalRenameWorkflow(input, new PrintWriter(output), new PrintWriter(errors),
                false, gate).run(new RenameRequest(source, "n", "database length", "\\numElements", RenameRequest.Scope.FILE));
        return new Result(code, output.toString(), errors.toString());
    }

    private record Result(int code, String output, String errors) { }
}
