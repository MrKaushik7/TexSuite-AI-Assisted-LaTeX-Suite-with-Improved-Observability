package texsuite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DocumentInputValidatorTest {
    @TempDir
    Path temporaryDirectory;

    private final DocumentInputValidator validator = new DocumentInputValidator();

    @Test
    void acceptsUtf8AndResolvesSymbolicLink() throws Exception {
        Path realFile = Files.writeString(temporaryDirectory.resolve("real.tex"), "π and \\alpha\n");
        Path link = Files.createSymbolicLink(temporaryDirectory.resolve("link.tex"), realFile);

        assertEquals(realFile.toRealPath(), validator.validate("link.tex", temporaryDirectory));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void distinguishesDirectoryMissingFileAndWrongExtension() throws Exception {
        Files.writeString(temporaryDirectory.resolve("paper.txt"), "hello");
        Files.createDirectory(temporaryDirectory.resolve("folder.tex"));

        assertFailure("folder.tex", "directory");
        assertFailure("missing.tex", "does not exist");
        assertFailure("paper.txt", "end in .tex");
    }

    @Test
    void rejectsInvalidPathWithoutEchoingIt() {
        assertFailure("bad\u0000.tex", "invalid file path");
    }

    private void assertFailure(String name, String expectedMessage) {
        DocumentInputValidator.InputException exception = assertThrows(
                DocumentInputValidator.InputException.class,
                () -> validator.validate(name, temporaryDirectory));
        assertTrue(exception.getMessage().contains(expectedMessage));
    }
}
