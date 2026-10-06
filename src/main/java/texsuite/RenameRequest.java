package texsuite;

import java.nio.file.Path;
import java.util.Objects;

record RenameRequest(Path target, String source, String meaning, String replacement, Scope scope) {
    RenameRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(meaning, "meaning");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(scope, "scope");
        if (!target.isAbsolute() || !target.normalize().equals(target)) {
            throw new IllegalArgumentException("target must be an absolute normalized path");
        }
        validateLiteral(source, "source");
        meaning = meaning.strip();
        if (meaning.isEmpty()) {
            throw new IllegalArgumentException("meaning must describe the intended symbol");
        }
        validateLiteral(replacement, "replacement");
        if (replacement.equals(source)) {
            throw new IllegalArgumentException("replacement must differ from the source string");
        }
    }

    static void validateLiteral(String value, String label) {
        if (value.codePoints().allMatch(codePoint -> Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint))) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT
                || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR
                || codePoint >= Character.MIN_SURROGATE
                        && codePoint <= Character.MAX_SURROGATE)) {
            throw new IllegalArgumentException(
                    label + " must be one line of printable Unicode text");
        }
    }

    enum Scope {
        FILE, PROJECT
    }
}
