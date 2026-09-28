package texsuite;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.Optional;

final class EditorPreferences {
    private final Path storage;
    private boolean automatic = true;
    private String application;

    EditorPreferences(Path storage) {
        this.storage = storage;
    }

    Optional<String> load() throws IOException {
        if (!Files.exists(storage, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.isSymbolicLink(storage) || !Files.isRegularFile(storage)) {
            throw new IOException("editor settings path is not a regular file");
        }
        Properties values = new Properties();
        try (InputStream stream = Files.newInputStream(storage)) {
            values.load(stream);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid editor settings file", exception);
        }
        String enabled = values.getProperty("automatic", "true");
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IOException("invalid automatic editor setting");
        }
        String editor = values.getProperty("application", "");
        automatic = Boolean.parseBoolean(enabled);
        application = null;
        if (!editor.isEmpty()) {
            try {
                setApplication(editor);
            } catch (IOException exception) {
                return Optional.of("Saved editor is invalid or unavailable; using the system text editor. Browse for an editor in Settings.");
            }
        }
        return Optional.empty();
    }

    boolean automatic() {
        return automatic;
    }

    String application() {
        return application;
    }

    void setAutomatic(boolean value) {
        automatic = value;
    }

    void setApplication(String value) throws IOException {
        application = value == null ? null : EditorApplication.validate(value);
    }

    void reset() {
        automatic = true;
        application = null;
    }

    void save() throws IOException {
        if (Files.isSymbolicLink(storage)) throw new IOException("editor settings path is a symbolic link");
        Path parent = storage.getParent();
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent)) throw new IOException("editor settings folder is a symbolic link");
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        Path temporary = Files.createTempFile(parent, ".editor-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Properties values = new Properties();
            values.setProperty("automatic", Boolean.toString(automatic));
            values.setProperty("application", application == null ? "" : application);
            try (OutputStream stream = Files.newOutputStream(temporary)) {
                values.store(stream, "TexSuite editor preferences (local only)");
            }
            Files.move(temporary, storage, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
