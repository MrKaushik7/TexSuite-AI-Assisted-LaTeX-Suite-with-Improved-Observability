package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import net.jqwik.api.*;

final class WordBoundaryPropertiesTest {
    @Provide
    Arbitrary<String> words() {
        return Arbitraries.of("h", "i", "é", "中", "𐐀").list().ofMinSize(1).ofMaxSize(12)
                .map(parts -> String.join("", parts));
    }

    @Provide
    Arbitrary<String> wordNeighbors() {
        return Arbitraries.of("x", "é", "中", "𐐀", "1", "_", "\u0301", "\u0903", "\u20dd", "\u203f");
    }

    @Provide
    Arbitrary<String> separators() {
        return Arbitraries.of("", " ", ".", "(", ")", "!", "-", "😀", "\n");
    }

    @Property(tries = 200)
    void wordNeighborsBlockButSeparatorsAllowLiteralWords(@ForAll("words") String word,
            @ForAll("wordNeighbors") String neighbor, @ForAll("separators") String separator) {
        assertFalse(TextReplacementWorkflow.wholeWord(neighbor + word, neighbor.length(), neighbor.length() + word.length()));
        assertFalse(TextReplacementWorkflow.wholeWord(word + neighbor, 0, word.length()));
        assertTrue(TextReplacementWorkflow.wholeWord(separator + word + separator, separator.length(), separator.length() + word.length()));
    }
}
