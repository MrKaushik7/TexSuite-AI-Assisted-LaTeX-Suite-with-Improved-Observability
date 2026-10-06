package texsuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Compiles only a bounded disposable copy; the returned check binds validation to saved inputs. */
final class TexCompileGate {
    private static final int MAX_FILES = 512;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private final Path executable;
    private final boolean allowNoCompile;
    private final Path compilationMain;
    private final Runner runner;

    TexCompileGate(boolean allowNoCompile, Path compilationMain) {
        this(findCompiler(), allowNoCompile, compilationMain, TexCompileGate::runProcess);
    }

    TexCompileGate(Path executable, boolean allowNoCompile, Path compilationMain, Runner runner) {
        this.executable = executable;
        this.allowNoCompile = allowNoCompile;
        this.compilationMain = compilationMain;
        this.runner = runner;
    }

    boolean hasCompiler() {
        return executable != null;
    }

    boolean hasExplicitMain() {
        return compilationMain != null;
    }

    TexCompileGate withMain(Path main) {
        return new TexCompileGate(executable, allowNoCompile, main, runner);
    }

    Path mainFor(DocumentSnapshot edits) {
        return compilationMain == null ? edits.root().resolve(edits.main())
                : compilationMain.toAbsolutePath().normalize();
    }

    void checkMain(DocumentSnapshot edits, List<TextEditPlan.Edit> accepted) throws IOException {
        DocumentSnapshot context = loadMain(mainFor(edits));
        for (TextEditPlan.Edit edit : accepted) {
            Path absolute = edits.root().resolve(edit.path());
            if (!absolute.startsWith(context.root())
                    || !context.definitelyReachable().contains(context.root().relativize(absolute))) {
                throw new IOException("Edited file is not in the compilation main's unconditional include closure.");
            }
        }
    }

    private static DocumentSnapshot loadMain(Path main) throws IOException {
        DocumentSnapshot context;
        try {
            context = new DocumentLoader().load(main.toRealPath());
        } catch (DocumentLoader.LoadException exception) {
            throw new IOException("Cannot load compilation main: " + exception.getMessage(), exception);
        }
        if (!new DocumentLoader().isCurrent(context)) {
            throw new IOException("Compilation requires a complete, resolved source/dependency closure.");
        }
        return context;
    }

    String validationNotice() {
        if (executable != null) return "TeX compilation of a disposable copy is required before writing.";
        return allowNoCompile
                ? "pdflatex is unavailable: --allow-no-compile permits this edit without compilation."
                : "pdflatex is unavailable: applying requires TeX or an explicit --allow-no-compile override.";
    }

    Freshness validate(DocumentSnapshot edits, Map<Path, byte[]> updates) throws IOException {
        if (executable == null) {
            if (!allowNoCompile) {
                throw new IOException("pdflatex was not found. Install TeX or explicitly use "
                        + "--allow-no-compile for an uncompiled edit. No source changes.");
            }
            return () -> { };
        }
        Path selectedMain = mainFor(edits);
        Path main = selectedMain.toRealPath();
        DocumentSnapshot context = loadMain(main);
        Map<Path, byte[]> inputs = readInputs(context.root());
        Map<Path, String> hashes = hashes(inputs);
        for (var update : updates.entrySet()) {
            Path absolute = edits.root().resolve(update.getKey());
            if (!absolute.startsWith(context.root())) {
                throw new IOException("Edited file is outside the compilation project.");
            }
            Path relative = context.root().relativize(absolute);
            if (!context.definitelyReachable().contains(relative)) {
                throw new IOException("Edited file is not in the compilation main's unconditional include closure.");
            }
            if (!java.util.Arrays.equals(inputs.get(relative),
                            edits.files().get(update.getKey()).bytes())) {
                throw new StaleSourceException("Saved source changed or edited file is not in the compilation closure.");
            }
            inputs.put(relative, update.getValue());
        }
        Path temporary = Files.createTempDirectory("texsuite-compile-");
        try {
            for (var entry : inputs.entrySet()) {
                Path copy = temporary.resolve(entry.getKey());
                Files.createDirectories(copy.getParent());
                Files.write(copy, entry.getValue());
            }
            Path output = Files.createDirectory(temporary.resolve(".texsuite-validation"));
            List<String> command = List.of(executable.toString(), "-interaction=nonstopmode",
                    "-halt-on-error", "-no-shell-escape", "-jobname=texsuite-validation",
                    "-output-directory=.texsuite-validation", "./" + context.main());
            int status = runner.run(command, temporary, 30);
            Path pdf = output.resolve("texsuite-validation.pdf");
            if (status != 0 || !Files.isRegularFile(pdf) || Files.size(pdf) == 0) {
                throw new IOException("TeX validation failed for " + DocumentInput.safeDisplay(context.main())
                        + " (exit " + status + "); no source changes. " + diagnostic(output, status));
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Freshness freshness = () -> {
            if (!selectedMain.toRealPath().equals(main)
                    || !new DocumentLoader().isCurrent(context)
                    || !hashes.equals(hashes(readInputs(context.root())))) {
                throw new StaleSourceException("Saved source changed during TeX validation; review a new preview.");
            }
        };
        freshness.check();
        return freshness;
    }

    private static String diagnostic(Path output, int status) throws IOException {
        Path log = output.resolve("texsuite-validation.log");
        if (Files.isRegularFile(log, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes;
            try (var input = Files.newInputStream(log)) {
                bytes = input.readNBytes(64 * 1024);
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                if (line.startsWith("!")) {
                    return DocumentInput.safeDisplay(line.substring(0, Math.min(line.length(), 300)));
                }
            }
        }
        return status == 0 ? "Compiler produced no nonempty PDF."
                : "No TeX error summary available; check the selected main and its required packages.";
    }

    private static Map<Path, byte[]> readInputs(Path root) throws IOException {
        Map<Path, byte[]> inputs = new TreeMap<>();
        long[] total = {0};
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path path,
                    java.nio.file.attribute.BasicFileAttributes attributes) {
                String name = path.getFileName().toString();
                return !path.equals(root) && (name.startsWith(".") || name.equals("target"))
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                        : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path path,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Path relative = root.relativize(path);
                if (path.getFileName().toString().startsWith(".")) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                if (attributes.isSymbolicLink()) {
                    throw new IOException("Compilation copy does not support symbolic links: " + relative);
                }
                if (!attributes.isRegularFile() || !path.toRealPath().equals(path)) {
                    throw new IOException("Compilation input is not a canonical regular file: " + relative);
                }
                byte[] bytes;
                try (var input = Files.newInputStream(path)) {
                    bytes = input.readNBytes(MAX_FILE_BYTES + 1);
                }
                total[0] += bytes.length;
                if (bytes.length > MAX_FILE_BYTES || total[0] > MAX_TOTAL_BYTES
                        || inputs.size() >= MAX_FILES) {
                    throw new IOException("Compilation copy exceeds 512 files, 16 MiB/file or 64 MiB total.");
                }
                inputs.put(relative, bytes);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return inputs;
    }

    private static Map<Path, String> hashes(Map<Path, byte[]> inputs) {
        Map<Path, String> result = new TreeMap<>();
        inputs.forEach((path, bytes) -> result.put(path, TextEditPlan.sha256(bytes)));
        return result;
    }

    private static Path findCompiler() {
        String search = System.getenv("PATH");
        for (String folder : ((search == null ? "" : search) + ":/Library/TeX/texbin").split(":")) {
            if (folder.isBlank() || !Path.of(folder).isAbsolute()) continue;
            Path candidate = Path.of(folder).resolve("pdflatex");
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    static int runProcess(List<String> command, Path directory, long timeoutSeconds) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        // Prevent environment search paths from reconnecting the copy to the live project.
        builder.environment().keySet().removeIf(key -> key.startsWith("TEX")
                || key.startsWith("BIB") || key.equals("VARTEXFONTS"));
        builder.environment().put("openin_any", "p");
        builder.environment().put("openout_any", "p");
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("TeX validation timed out; no source changes.");
            }
            return process.exitValue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("TeX validation interrupted; no source changes.", exception);
        } finally {
            if (process.isAlive()) {
                try (var children = process.descendants()) {
                    children.forEach(ProcessHandle::destroyForcibly);
                } catch (RuntimeException exception) {
                    // Some sandboxes deny process enumeration; still terminate the compiler itself.
                } finally {
                    process.destroyForcibly();
                    try {
                        process.waitFor(1, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    @FunctionalInterface
    interface Runner {
        int run(List<String> command, Path directory, long timeoutSeconds) throws IOException;
    }

    @FunctionalInterface
    interface Freshness {
        void check() throws IOException;
    }
}
