package texsuite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class EditorApplication {
    private EditorApplication() { }

    static boolean safeText(String value) {
        return value.codePoints().noneMatch(point -> Character.isISOControl(point)
                || Character.getType(point) == Character.FORMAT
                || Character.getType(point) == Character.SURROGATE
                || point == 0x2028 || point == 0x2029);
    }

    static String validate(String value) throws IOException {
        if (!safeText(value)) throw new IOException("The application path contains unsafe characters.");

        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) throw new IOException("Browse to an application; application names are not accepted.");

            Path app = path.toRealPath();
            if (!safeText(app.toString()) || !app.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".app")
                    || !Files.isDirectory(app) || !Files.isReadable(app)) {
                throw new IOException("Select a readable .app application bundle.");
            }

            Path metadata = app.resolve("Contents/Info.plist").toRealPath();
            if (!metadata.startsWith(app) || !Files.isRegularFile(metadata) || !Files.isReadable(metadata)) {
                throw new IOException("The application metadata is invalid.");
            }
            if (!property(metadata, "CFBundlePackageType").equals("APPL")) {
                throw new IOException("The selected bundle is not an application.");
            }

            String executable = property(metadata, "CFBundleExecutable");
            if (executable.isBlank() || !safeText(executable) || executable.contains("/")
                    || executable.equals(".") || executable.equals("..")) {
                throw new IOException("The application executable is invalid.");
            }

            Path binaries = app.resolve("Contents/MacOS").toRealPath();
            Path binary = binaries.resolve(executable).toRealPath();
            if (!binaries.startsWith(app) || !binary.startsWith(binaries)
                    || !Files.isRegularFile(binary) || !Files.isReadable(binary) || !Files.isExecutable(binary)) {
                throw new IOException("The application executable is missing or outside its bundle.");
            }
            return app.toString();
        } catch (InvalidPathException | SecurityException exception) {
            throw new IOException("The application path is invalid or inaccessible.", exception);
        }
    }

    private static String property(Path metadata, String key) throws IOException {
        return command(List.of("/usr/bin/plutil", "-extract", key, "raw", "-expect", "string",
                "-o", "-", metadata.toString()), 5);
    }

    // A file avoids pipe backpressure while the native dialog waits for the user.
    static String command(List<String> arguments, long timeoutSeconds) throws IOException {
        Path output = Files.createTempFile("texsuite-editor-", ".out");
        Process process = null;
        try {
            process = new ProcessBuilder(arguments).redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("The editor selection or validation timed out.");
            }
            if (process.exitValue() != 0) throw new IOException("The application picker or validation is unavailable or failed.");

            byte[] bytes;
            try (var stream = Files.newInputStream(output)) {
                bytes = stream.readNBytes(16_385);
            }
            if (bytes.length > 16_384) throw new IOException("The application response is too long.");

            String result = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            // Remove only the native tool's output terminator, never path whitespace.
            return result.endsWith("\n") ? result.substring(0, result.length() - 1) : result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Editor selection or validation was interrupted.", exception);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
}
