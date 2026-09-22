package texsuite;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;

final class DocumentInputValidator {
    Path validate(String enteredPath, Path workingDirectory) throws InputException {
        Path candidate;
        try {
            candidate = workingDirectory.resolve(enteredPath).normalize();
        } catch (InvalidPathException exception) {
            throw new InputException("invalid file path");
        }
        if (Files.isDirectory(candidate)) {
            throw new InputException("this is a directory, not a .tex file");
        }
        if (!candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tex")) {
            throw new InputException("filename must end in .tex");
        }

        Path realFile;
        try {
            realFile = candidate.toRealPath();
        } catch (NoSuchFileException exception) {
            throw new InputException("file does not exist");
        } catch (AccessDeniedException exception) {
            throw new InputException("access denied");
        } catch (IOException exception) {
            throw new InputException("could not resolve the file");
        }
        if (!Files.isRegularFile(realFile)) {
            throw new InputException("not a regular file");
        }
        if (!Files.isReadable(realFile)) {
            throw new InputException("file is not readable");
        }
        return realFile;
    }

    static final class InputException extends Exception {
        InputException(String message) {
            super(message);
        }
    }
}
