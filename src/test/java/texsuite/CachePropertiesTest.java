package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import net.jqwik.api.*;

final class CachePropertiesTest {
    @Provide
    Arbitrary<String> text() {
        return Arbitraries.of("a", "é", "😀", " ", "\\alpha", "\t", "\n")
                .list().ofMinSize(0).ofMaxSize(32).map(parts -> String.join("", parts));
    }

    @Property(tries = 150)
    void objectOrderDoesNotMatterButStringWhitespaceDoes(@ForAll("text") String text) throws Exception {
        var left = ModelProtocol.JSON.createObjectNode().put("source", text).put("model", "fixture");
        var reordered = ModelProtocol.JSON.createObjectNode().put("model", "fixture").put("source", text);
        assertEquals(ModelCache.fingerprint(left), ModelCache.fingerprint(reordered));
        reordered.put("source", " " + text + " ");
        assertNotEquals(ModelCache.fingerprint(left), ModelCache.fingerprint(reordered));
    }

    @Property(tries = 150)
    void literalReplacementWhitespaceChangesFullRequestKey(@ForAll("text") String replacement) throws Exception {
        var client = new ModelCacheTest.Fixture();
        assertNotEquals(ModelCache.key("rename", "FILE", "MATH", client,
                        ModelCacheTest.request(replacement), ModelCacheTest.batch()),
                ModelCache.key("rename", "FILE", "MATH", client,
                        ModelCacheTest.request(" " + replacement + " "), ModelCacheTest.batch()));
    }

    @Property(tries = 100)
    void arrayOrderIsPreservedWithinNestedObjects(@ForAll("text") String text) throws Exception {
        var left = ModelProtocol.JSON.createObjectNode();
        left.putObject("nested").putArray("ids").add(text).add(text + "x");
        var right = ModelProtocol.JSON.createObjectNode();
        right.putObject("nested").putArray("ids").add(text + "x").add(text);
        assertNotEquals(ModelCache.fingerprint(left), ModelCache.fingerprint(right));
    }
}
