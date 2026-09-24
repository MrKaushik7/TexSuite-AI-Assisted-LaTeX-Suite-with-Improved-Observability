package texsuite;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

final class RecentFolders {
    private static final int MAX_FOLDERS = 5;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_VISITED_PER_FOLDER = 2_000;
    private static final int MAX_SUGGESTIONS = 3;

    private final Path storage;

    RecentFolders(Path storage) {
        this.storage = storage;
    }

    static Path defaultStorage() {
        Path userHome = Path.of(System.getProperty("user.home"));
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            return userHome.resolve("Library/Application Support/TexSuite/recent-folders.properties");
        }
        return userHome.resolve(".local/state/texsuite/recent-folders.properties");
    }

    void remember(Path folder) throws IOException {
        if (Files.isSymbolicLink(storage)) {
            throw new IOException("recent-folder storage is a symbolic link");
        }
        List<Path> folders = new ArrayList<>();
        folders.add(folder);
        for (Path previous : load()) {
            if (!previous.equals(folder) && folders.size() < MAX_FOLDERS) {
                folders.add(previous);
            }
        }
        Files.createDirectories(storage.getParent());
        Files.setPosixFilePermissions(storage.getParent(),
                PosixFilePermissions.fromString("rwx------"));
        Properties properties = new Properties();
        for (int index = 0; index < folders.size(); index++) {
            properties.setProperty(Integer.toString(index), folders.get(index).toString());
        }
        try (OutputStream stream = Files.newOutputStream(storage)) {
            properties.store(stream, "TexSuite recent folders (local only)");
        }
        Files.setPosixFilePermissions(storage,
                PosixFilePermissions.fromString("rw-------"));
    }

    List<Path> suggest(String enteredPath, Path workingDirectory) {
        String targetName = basename(enteredPath);
        if (targetName.length() > 255) {
            return List.of();
        }
        if (!targetName.toLowerCase(Locale.ROOT).endsWith(".tex")) {
            targetName += ".tex";
        }
        String target = targetName.toLowerCase(Locale.ROOT);
        List<Path> roots = new ArrayList<>();
        roots.add(workingDirectory);
        for (Path folder : load()) {
            if (!roots.contains(folder)) {
                roots.add(folder);
            }
        }
        List<Path> candidates = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root, MAX_DEPTH)) {
                paths.limit(MAX_VISITED_PER_FOLDER)
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tex"))
                        .forEach(candidates::add);
            } catch (IOException | UncheckedIOException exception) {
                // A stale or inaccessible recent folder must not block path entry.
            }
        }
        String wantedName = target;
        int maximumDistance = Math.max(2, wantedName.length() / 3);
        return candidates.stream()
                .distinct()
                .filter(path -> distance(wantedName,
                        path.getFileName().toString().toLowerCase(Locale.ROOT)) <= maximumDistance)
                .sorted(Comparator.comparingInt((Path path) -> distance(wantedName,
                        path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .thenComparing(Path::toString))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private List<Path> load() {
        if (!Files.isRegularFile(storage, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(storage)) {
            properties.load(stream);
        } catch (IOException | IllegalArgumentException exception) {
            return List.of();
        }
        List<Path> folders = new ArrayList<>();
        for (int index = 0; index < MAX_FOLDERS; index++) {
            String value = properties.getProperty(Integer.toString(index));
            if (value != null) {
                try {
                    folders.add(Path.of(value));
                } catch (InvalidPathException exception) {
                    // Ignore a corrupt entry, not the entire recent list.
                }
            }
        }
        return folders;
    }

    private String basename(String enteredPath) {
        try {
            Path name = Path.of(enteredPath).getFileName();
            return name == null ? "" : name.toString();
        } catch (InvalidPathException exception) {
            return enteredPath;
        }
    }

    private int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            int[] current = new int[right.length() + 1];
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitutionCost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(Math.min(current[column - 1] + 1,
                        previous[column] + 1), previous[column - 1] + substitutionCost);
            }
            previous = current;
        }
        return previous[right.length()];
    }
}
