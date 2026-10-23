package texsuite;

import static org.junit.jupiter.api.Assertions.*;
import static texsuite.SanitationPlannerTest.bytes;
import static texsuite.SanitationPlannerTest.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.*;

final class SanitationPropertiesTest {
    @Provide
    Arbitrary<List<Line>> lines() {
        return Combinators.combine(Arbitraries.of("Plain.", "Café é.", "日本語 😀.", "Count 42."),
                Arbitraries.of("", " ", "\t", " \t  "), Arbitraries.of("\n", "\r", "\r\n"))
                .as(Line::new).list().ofMinSize(1).ofMaxSize(12);
    }

    @Property(tries = 100)
    void mixedEditsMatchIndependentOracleAndInputOrderIsIrrelevant(@ForAll("lines") List<Line> lines) throws Exception {
        try (var fixture = new Fixture()) {
            String source = original(lines);
            var snapshot = fixture.load(source);
            var report = new SanitationPlanner().inspect(snapshot);
            var edits = combined(report);
            String expected = lines.stream().map(line -> line.text() + "\n").collect(java.util.stream.Collectors.joining()) + "Last.";
            if (edits.isEmpty()) assertEquals(expected, source);
            else {
                var normal = new TextEditPlan(snapshot, edits, gate()).preview().get(Path.of("main.tex"));
                assertArrayEquals(bytes(expected), normal);
                java.util.Collections.reverse(edits);
                assertArrayEquals(normal, new TextEditPlan(snapshot, edits, gate()).preview().get(Path.of("main.tex")));
            }
        }
    }

    @Property(tries = 100)
    void supportedCleanupIsIdempotent(@ForAll("lines") List<Line> lines) throws Exception {
        try (var fixture = new Fixture()) {
            var snapshot = fixture.load(original(lines));
            var edits = combined(new SanitationPlanner().inspect(snapshot));
            if (!edits.isEmpty()) Files.write(fixture.main, new TextEditPlan(snapshot, edits, gate()).preview().get(Path.of("main.tex")));
            var second = new SanitationPlanner().inspect(new DocumentLoader().load(fixture.main));
            assertTrue(second.trailing().isEmpty());
            assertTrue(second.lf().isEmpty());
        }
    }

    @Property(tries = 75)
    void compatibleRuleOrderHasIdenticalBytes(@ForAll("lines") List<Line> lines) throws Exception {
        try (var fixture = new Fixture()) {
            String source = original(lines);
            fixture.load(source);
            applyPreview(fixture, false);
            applyPreview(fixture, true);
            byte[] trailingThenEol = Files.readAllBytes(fixture.main);
            fixture.load(source);
            applyPreview(fixture, true);
            applyPreview(fixture, false);
            assertArrayEquals(trailingThenEol, Files.readAllBytes(fixture.main));
        }
    }

    @Property(tries = 100)
    void protectedSourceIsByteExactBesideEditableText(@ForAll("lines") List<Line> lines) throws Exception {
        String protectedText = "% comment  \n\\newcommand{\\literal}{value  }\n"
                + "\\begin{verbatim}\nkeep  \n\\end{verbatim}\n\\iftrue\nconditional  \n\\fi\n"
                + "\\verb|inline  |  \n\\textbf{command}  \n  \n";
        try (var fixture = new Fixture()) {
            var snapshot = fixture.load(protectedText + original(lines));
            var edits = combined(new SanitationPlanner().inspect(snapshot));
            byte[] rendered = edits.isEmpty() ? snapshot.files().get(Path.of("main.tex")).bytes()
                    : new TextEditPlan(snapshot, edits, gate()).preview().get(Path.of("main.tex"));
            String expected = protectedText + lines.stream().map(line -> line.text() + "\n")
                    .collect(java.util.stream.Collectors.joining()) + "Last.";
            assertArrayEquals(bytes(expected), rendered);
        }
    }

    @Property(tries = 100)
    void unselectedSpansRemainExact(@ForAll("lines") List<Line> lines) throws Exception {
        try (var fixture = new Fixture()) {
            var snapshot = fixture.load(original(lines));
            var proposals = new SanitationPlanner().inspect(snapshot).trailing();
            List<TextEditPlan.Edit> selected = new ArrayList<>();
            for (int index = 0; index < proposals.size(); index += 2) selected.add(proposals.get(index));
            StringBuilder expected = new StringBuilder();
            int dirty = 0;
            for (Line line : lines) {
                boolean remove = !line.trailing().isEmpty() && dirty++ % 2 == 0;
                expected.append(line.text()).append(remove ? "" : line.trailing()).append(line.ending());
            }
            expected.append("Last.");
            byte[] actual = selected.isEmpty() ? bytes(original(lines)) : new TextEditPlan(snapshot, selected, gate()).preview().get(Path.of("main.tex"));
            assertArrayEquals(bytes(expected.toString()), actual);
        }
    }

    @Property(tries = 75)
    void invalidOrStalePatchNeverWrites(@ForAll("lines") List<Line> lines) throws Exception {
        try (var fixture = new Fixture()) {
            String source = "Plain.  \n" + original(lines);
            var snapshot = fixture.load(source);
            var edit = new SanitationPlanner().inspect(snapshot).trailing().getFirst();
            assertThrows(IOException.class, () -> new TextEditPlan(snapshot, List.of(edit, edit), gate()).apply());
            assertArrayEquals(bytes(source), Files.readAllBytes(fixture.main));
            Files.writeString(fixture.main, source + "External.");
            assertThrows(StaleSourceException.class, () -> new TextEditPlan(snapshot, List.of(edit), gate()).apply());
            assertEquals(source + "External.", Files.readString(fixture.main));
            assertFalse(Files.exists(fixture.root.resolve(".tex-suite")));
        }
    }

    @Property(tries = 50)
    void applyAndUndoRestoreExactOriginalBytes(@ForAll("lines") List<Line> lines) throws Exception {
        try (var fixture = new Fixture()) {
            String source = "Plain.  \n" + original(lines);
            var snapshot = fixture.load(source);
            var edits = combined(new SanitationPlanner().inspect(snapshot));
            Path backup = new TextEditPlan(snapshot, edits, gate()).apply();
            assertEquals(2, EditHistory.list(fixture.root).getFirst().record().version());
            TextRecovery.revert(fixture.root, backup);
            assertArrayEquals(bytes(source), Files.readAllBytes(fixture.main));
        }
    }

    private static String original(List<Line> lines) {
        return lines.stream().map(line -> line.text() + line.trailing() + line.ending())
                .collect(java.util.stream.Collectors.joining()) + "Last.";
    }

    private static ArrayList<TextEditPlan.Edit> combined(SanitationPlanner.Report report) {
        var edits = new ArrayList<>(report.trailing());
        edits.addAll(report.lf());
        return edits;
    }

    private static void applyPreview(Fixture fixture, boolean eol) throws Exception {
        var snapshot = new DocumentLoader().load(fixture.main);
        var report = new SanitationPlanner().inspect(snapshot);
        var edits = eol ? report.lf() : report.trailing();
        if (!edits.isEmpty()) Files.write(fixture.main, new TextEditPlan(snapshot, edits, gate()).preview().get(Path.of("main.tex")));
    }

    private record Line(String text, String trailing, String ending) { }

    private static final class Fixture implements AutoCloseable {
        private final Path root;
        private final Path main;

        Fixture() throws IOException {
            root = Files.createTempDirectory("texsuite-sanitation-property-").toRealPath();
            main = root.resolve("main.tex");
        }

        DocumentSnapshot load(String source) throws Exception {
            return new DocumentLoader().load(Files.writeString(main, source));
        }

        @Override public void close() throws IOException {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
