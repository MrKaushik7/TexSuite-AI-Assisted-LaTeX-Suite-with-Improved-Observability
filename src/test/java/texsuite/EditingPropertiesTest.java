package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.*;

final class EditingPropertiesTest {
    @Provide
    Arbitrary<String> text() {
        return Arbitraries.of("a", "Z", " ", "é", "π", "😀", "e\u0301", "\n", "\r", "\r\n")
                .list().ofMinSize(0).ofMaxSize(64).map(parts -> String.join("", parts));
    }

    @Provide
    Arbitrary<List<String>> segments() {
        return text().list().ofMinSize(2).ofMaxSize(5);
    }

    @Property(tries = 200)
    void emptySelection_rejectsWithoutChangingBytes(@ForAll("text") String text) throws Exception {
        try (var fixture = new Fixture()) {
            Path main = Files.writeString(fixture.root.resolve("main.tex"), text);
            byte[] original = Files.readAllBytes(main);
            var plan = new TextEditPlan(new DocumentLoader().load(main), List.of(), "x", noCompiler());

            assertTrue(plan.preview().isEmpty());
            assertThrows(IOException.class, plan::apply);

            assertArrayEquals(original, Files.readAllBytes(main));
            assertFalse(Files.exists(fixture.root.resolve(".tex-suite")));
        }
    }

    @Property(tries = 200)
    void validEdits_preserveEveryUnselectedSegment(@ForAll("segments") List<String> segments,
            @ForAll("text") String replacement) throws Exception {
        try (var fixture = new Fixture()) {
            // Independent oracle: the specification joins unchanged segments with replacement text.
            String original = String.join("MARK", segments);
            String expected = String.join(replacement, segments);
            Path main = Files.writeString(fixture.root.resolve("main.tex"), original);
            var edits = new ArrayList<TextEditPlan.Edit>();
            int offset = 0;
            for (int i = 0; i < segments.size() - 1; i++) {
                offset += bytes(segments.get(i)).length;
                edits.add(edit("main.tex", offset, "MARK"));
                offset += 4;
            }
            var plan = new TextEditPlan(new DocumentLoader().load(main), edits, replacement, noCompiler());

            plan.apply();

            assertArrayEquals(bytes(expected), Files.readAllBytes(main));
        }
    }

    @Property(tries = 50)
    void interruptedRecovery_restoresBothOriginalByteSequences(@ForAll("text") String prefix,
            @ForAll("text") String replacement) throws Exception {
        try (var fixture = new Fixture()) {
            String mainText = prefix + "MARK\\input{chapter}";
            String chapterText = prefix + "MARK";
            Path main = Files.writeString(fixture.root.resolve("main.tex"), mainText);
            Path chapter = Files.writeString(fixture.root.resolve("chapter.tex"), chapterText);
            int start = bytes(prefix).length;
            var plan = new TextEditPlan(new DocumentLoader().load(main),
                    List.of(edit("main.tex", start, "MARK"), edit("chapter.tex", start, "MARK")),
                    replacement, noCompiler(), (staged, target) -> {
                        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                        throw new InterruptedCommit();
                    });

            assertThrows(InterruptedCommit.class, plan::apply);
            List<Path> pending = TextRecovery.pending(fixture.root);
            assertEquals(1, pending.size());
            TextRecovery.restore(fixture.root, pending.getFirst());

            assertArrayEquals(bytes(mainText), Files.readAllBytes(main));
            assertArrayEquals(bytes(chapterText), Files.readAllBytes(chapter));
            assertTrue(TextRecovery.pending(fixture.root).isEmpty());
        }
    }

    @Property(tries = 200)
    void unicodePrefix_candidateByteRangeSelectsExactlyTheToken(@ForAll("text") String prefix)
            throws Exception {
        try (var fixture = new Fixture()) {
            String text = prefix + "$n$";
            Path main = Files.writeString(fixture.root.resolve("main.tex"), text);
            var request = new RenameRequest(main, "n", "count", "m", RenameRequest.Scope.FILE);
            var result = new RenameCandidateDiscovery().discover(new DocumentLoader().loadFile(main), request);

            assertEquals(1, result.occurrences().size());
            var candidate = result.occurrences().getFirst();

            assertEquals(RenameCandidateDiscovery.Status.CANDIDATE, candidate.status());
            assertEquals(bytes(prefix + "$").length, candidate.startByte());
            assertArrayEquals(bytes("n"), Arrays.copyOfRange(bytes(text),
                    candidate.startByte(), candidate.endByte()));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static TextEditPlan.Edit edit(String path, int start, String expected) {
        return new TextEditPlan.Edit(Path.of(path), start, start + bytes(expected).length,
                bytes(expected), 1, 1);
    }

    private static TexCompileGate noCompiler() {
        return new TexCompileGate(null, true, null, null);
    }

    private static final class InterruptedCommit extends Error { }

    // jqwik owns its lifecycle; Jupiter's @TempDir is deliberately not used here.
    private static final class Fixture implements AutoCloseable {
        private final Path root;

        Fixture() throws IOException {
            root = Files.createTempDirectory("texsuite-property-").toRealPath();
        }

        @Override
        public void close() throws IOException {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
