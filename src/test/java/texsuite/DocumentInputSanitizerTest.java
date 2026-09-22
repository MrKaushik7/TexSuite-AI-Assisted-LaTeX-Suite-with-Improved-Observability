package texsuite;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DocumentInputSanitizerTest {
    @TempDir
    Path temporaryDirectory;

    private final DocumentInputSanitizer sanitizer = new DocumentInputSanitizer();

    @Test
    void acceptsUtf8WithoutChangingSource() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "π and \\alpha\n");
        byte[] original = Files.readAllBytes(source);

        sanitizer.check(source);

        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    void rejectsMalformedUtf8AndControlCharacters() throws Exception {
        Path malformed = Files.write(temporaryDirectory.resolve("bad.tex"),
                new byte[] {(byte) 0xC3, (byte) 0x28});
        Path control = Files.writeString(temporaryDirectory.resolve("control.tex"), "a\u0000b");

        assertFailure(malformed, "not valid UTF-8");
        assertFailure(control, "control character");
    }

    private void assertFailure(Path file, String expectedMessage) {
        DocumentInputValidator.InputException exception = assertThrows(
                DocumentInputValidator.InputException.class, () -> sanitizer.check(file));
        assertTrue(exception.getMessage().contains(expectedMessage));
    }
}
