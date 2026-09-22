package texsuite;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

final class DocumentInputSanitizer {
    void check(Path file) throws DocumentInputValidator.InputException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), decoder)) {
            char[] characters = new char[8_192];
            int count;
            while ((count = reader.read(characters)) != -1) {
                for (int index = 0; index < count; index++) {
                    char character = characters[index];
                    if (character < 32 && character != '\n' && character != '\r'
                            && character != '\t') {
                        throw new DocumentInputValidator.InputException(
                                "unsupported control character in source");
                    }
                }
            }
        } catch (CharacterCodingException exception) {
            throw new DocumentInputValidator.InputException("source is not valid UTF-8");
        } catch (AccessDeniedException exception) {
            throw new DocumentInputValidator.InputException("access denied");
        } catch (IOException exception) {
            throw new DocumentInputValidator.InputException("could not read the file");
        }
    }
}
