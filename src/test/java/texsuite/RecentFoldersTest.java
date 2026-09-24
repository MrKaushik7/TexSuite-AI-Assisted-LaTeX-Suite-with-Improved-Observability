package texsuite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecentFoldersTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void searchIsBoundedAndRankedByFilenameOnly() throws Exception {
        Path workingDirectory = Files.createDirectory(temporaryDirectory.resolve("work"));
        Path close = Files.writeString(workingDirectory.resolve("paper.tex"), "x");
        Files.writeString(workingDirectory.resolve("pacer.tex"), "x");
        Files.writeString(workingDirectory.resolve("paler.tex"), "x");
        Files.writeString(workingDirectory.resolve("payer.tex"), "x");
        Files.writeString(workingDirectory.resolve("unrelated.tex"), "x");
        Path tooDeep = Files.createDirectories(workingDirectory.resolve("a/b/c"));
        Files.writeString(tooDeep.resolve("paper.tex"), "x");
        RecentFolders recentFolders = new RecentFolders(temporaryDirectory.resolve("recent.properties"));

        List<Path> suggestions = recentFolders.suggest("elsewhere/papr.tex", workingDirectory);

        assertEquals(3, suggestions.size());
        assertEquals(close, suggestions.getFirst());
        assertFalse(suggestions.stream().anyMatch(path -> path.startsWith(tooDeep)));
        assertTrue(suggestions.stream().allMatch(path -> path.getFileName().toString().endsWith(".tex")));
    }

    @Test
    void irrelevantTexFilesAreNotSuggested() throws Exception {
        Path workingDirectory = Files.createDirectory(temporaryDirectory.resolve("work"));
        Files.writeString(workingDirectory.resolve("unrelated.tex"), "x");
        Files.writeString(workingDirectory.resolve("paper.pdf"), "x");
        RecentFolders recentFolders = new RecentFolders(temporaryDirectory.resolve("recent.properties"));

        List<Path> suggestions = recentFolders.suggest("abstract.tex", workingDirectory);

        assertTrue(suggestions.isEmpty());
    }

    @Test
    void corruptPropertiesDoNotPreventRememberingOrSuggesting() throws Exception {
        Path storage = temporaryDirectory.resolve("recent.properties");
        Files.writeString(storage, "0=\\uBROKEN");
        Path source = Files.writeString(temporaryDirectory.resolve("paper.tex"), "$n$");
        RecentFolders recentFolders = new RecentFolders(storage);

        assertEquals(List.of(source), recentFolders.suggest("papr.tex", temporaryDirectory));
        recentFolders.remember(temporaryDirectory);
        assertTrue(Files.readString(storage).contains(temporaryDirectory.toString()));
    }
}
