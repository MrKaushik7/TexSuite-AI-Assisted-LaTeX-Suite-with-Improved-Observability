package texsuite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@FunctionalInterface
interface EditorPicker {
    Optional<Path> choose() throws IOException;

    static EditorPicker system() {
        return () -> {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
                throw new IOException("Browsing for an editor is available only on macOS.");
            }

            String script = """
                    try
                        set chosenApp to choose file with prompt "Select an editor application" of type {"com.apple.application-bundle"} default location (POSIX file "/Applications") without multiple selections allowed and showing package contents
                        return POSIX path of chosenApp
                    on error number -128
                        return ""
                    end try
                    """;
            String selected = EditorApplication.command(List.of("/usr/bin/osascript", "-e", script), 300);

            if (selected.isEmpty()) return Optional.empty();
            if (!EditorApplication.safeText(selected)) throw new IOException("The selected path contains unsafe characters.");

            return Optional.of(Path.of(selected));
        };
    }
}
