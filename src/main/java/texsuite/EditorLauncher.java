package texsuite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@FunctionalInterface
interface EditorLauncher {
    Optional<String> open(Path file, String application);

    static EditorLauncher system() {
        return (file, application) -> {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
                return Optional.of("Automatic text-editor opening is unavailable on this platform.");
            }
            try {
                if (application != null) application = EditorApplication.validate(application);
            } catch (IOException exception) {
                return Optional.of("The selected editor is invalid or unavailable. Browse for an editor in Settings.");
            }

            ProcessBuilder command = application == null
                    ? new ProcessBuilder("/usr/bin/open", "-t", file.toString())
                    : new ProcessBuilder("/usr/bin/open", "-a", application, file.toString());
            command.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            command.redirectError(ProcessBuilder.Redirect.DISCARD);
            try {
                Process process = command.start();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return Optional.of("The editor launch request timed out.");
                }
                return process.exitValue() == 0 ? Optional.empty()
                        : Optional.of("The editor could not open this file.");
            } catch (IOException | SecurityException exception) {
                return Optional.of("The text editor is unavailable.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.of("The editor launch was interrupted.");
            }
        };
    }
}
