package texsuite;

import java.io.IOException;

/** A saved-input change that requires a fresh preview and renewed approval. */
final class StaleSourceException extends IOException {
    StaleSourceException(String message) {
        super(message);
    }
}
