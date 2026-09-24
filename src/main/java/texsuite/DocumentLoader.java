package texsuite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import texsuite.DocumentSnapshot.AssetReference;
import texsuite.DocumentSnapshot.Diagnostic;
import texsuite.DocumentSnapshot.IncludeSite;
import texsuite.DocumentSnapshot.ProtectedRegion;
import texsuite.DocumentSnapshot.SourceFile;

final class DocumentLoader {
    private static final int MAX_FILES = 512;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    private final DocumentInputValidator validator = new DocumentInputValidator();
    private final DocumentInputSanitizer sanitizer = new DocumentInputSanitizer();
    private final Map<Path, LoadedSource> loaded = new LinkedHashMap<>();
    private final List<IncludeSite> includeSites = new ArrayList<>();
    private final List<AssetReference> assets = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final ArrayDeque<Path> active = new ArrayDeque<>();
    private Path projectRoot;
    private long totalBytes;
    private boolean complete = true;

    DocumentSnapshot load(Path selectedFile) throws LoadException {
        loaded.clear();
        includeSites.clear();
        assets.clear();
        diagnostics.clear();
        active.clear();
        totalBytes = 0;
        complete = true;
        Path main;
        try {
            main = validator.validate(selectedFile.toString(), Path.of("").toAbsolutePath());
        } catch (DocumentInputValidator.InputException exception) {
            throw new LoadException(exception.getMessage());
        }
        projectRoot = main.getParent();
        readSource(main);
        Path relativeMain = projectRoot.relativize(main);
        Map<Path, SourceFile> files = new LinkedHashMap<>();
        List<ProtectedRegion> protectedRegions = new ArrayList<>();
        for (Map.Entry<Path, LoadedSource> entry : loaded.entrySet()) {
            Path relative = entry.getKey();
            LoadedSource loadedSource = entry.getValue();
            files.put(relative, new SourceFile(relative, loadedSource.bytes(), loadedSource.hash()));
            for (TexSourceScanner.ProtectedRegion region
                    : loadedSource.scan().protectedRegions()) {
                protectedRegions.add(new ProtectedRegion(relative,
                        loadedSource.scanner().byteOffset(region.startChar()),
                        loadedSource.scanner().byteOffset(region.endChar()),
                        region.reason()));
            }
        }
        protectedRegions.sort(Comparator.comparing((ProtectedRegion item) -> item.source().toString())
                .thenComparingInt(ProtectedRegion::startByte));
        return new DocumentSnapshot(projectRoot, relativeMain, files, includeSites, assets,
                diagnostics, protectedRegions, fingerprint(relativeMain, files), complete);
    }

    boolean isCurrent(DocumentSnapshot snapshot) {
        if (!snapshot.complete() || snapshot.diagnostics().stream()
                .anyMatch(item -> item.code().equals("UNRESOLVED_ASSET"))) {
            return false;
        }
        if (snapshot.assets().stream().anyMatch(asset -> !asset.resolved())) {
            return false;
        }
        try {
            // Re-resolve literals too: unchanged targets can hide retargeted links or new ambiguity.
            DocumentSnapshot current = new DocumentLoader().load(
                    snapshot.root().resolve(snapshot.main()));
            return current.complete()
                    && current.assets().stream().allMatch(AssetReference::resolved)
                    && current.diagnostics().stream()
                            .noneMatch(item -> item.code().equals("UNRESOLVED_ASSET"))
                    && current.fingerprint().equals(snapshot.fingerprint());
        } catch (LoadException exception) {
            return false;
        }
    }

    private void readSource(Path file) throws LoadException {
        Path relative = projectRoot.relativize(file);
        if (active.contains(file)) {
            throw new LoadException("include cycle: " + active + " -> " + relative);
        }
        if (loaded.containsKey(relative)) {
            return;
        }
        if (loaded.size() >= MAX_FILES) {
            throw new LoadException("source closure exceeds " + MAX_FILES + " files");
        }
        active.addLast(file);
        try {
            if (!Files.isRegularFile(file)) {
                throw new LoadException("included path is not a regular file: " + relative);
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new LoadException("source exceeds 16 MiB: " + relative);
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            totalBytes += bytes.length;
            if (bytes.length > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                throw new LoadException("source closure exceeds size limit");
            }
            String text;
            try {
                text = sanitizer.decode(bytes);
            } catch (DocumentInputValidator.InputException exception) {
                throw new LoadException(relative + ": " + exception.getMessage());
            }
            TexSourceScanner scanner = new TexSourceScanner(text);
            TexSourceScanner.Scan scan = scanner.scan();
            loaded.put(relative, new LoadedSource(bytes, sha256(bytes), scanner, scan));
            for (TexSourceScanner.Problem problem : scan.problems()) {
                diagnostics.add(new Diagnostic(relative, problem.byteOffset(),
                        problem.code(), problem.detail()));
                if (problem.code().equals("UNRESOLVED_INCLUDE")
                        || problem.code().equals("UNSUPPORTED_CATCODES")) {
                    complete = false;
                }
            }
            for (TexSourceScanner.AssetReference asset : scan.assets()) {
                addAssets(relative, asset, scanner);
            }
            for (TexSourceScanner.IncludeReference reference : scan.includes()) {
                Path target = reference.supported()
                        ? resolveInclude(relative, reference, scanner) : null;
                includeSites.add(new IncludeSite(relative, scanner.byteOffset(reference.charIndex()),
                        reference.literal(), target == null ? null : projectRoot.relativize(target),
                        reference.conditional(), target != null));
                if (target != null) {
                    readSource(target);
                }
            }
        } catch (IOException exception) {
            throw new LoadException("could not read source: " + relative);
        } finally {
            active.removeLast();
        }
    }

    private Path resolveInclude(Path relative,
            TexSourceScanner.IncludeReference reference, TexSourceScanner scanner)
            throws LoadException {
        String literal = reference.literal();
        if (Path.of(literal).isAbsolute()) {
            throw new LoadException("include escapes project root: " + relative);
        }
        Path fileName = Path.of(literal).getFileName();
        if (!literal.endsWith(".tex") && (fileName == null || !fileName.toString().contains("."))) {
            literal += ".tex";
        }
        if (!literal.endsWith(".tex")) {
            incomplete(relative, scanner.byteOffset(reference.charIndex()),
                    "UNSUPPORTED_INCLUDE", "include is not a .tex source");
            return null;
        }
        Path candidate = projectRoot.resolve(literal).normalize();
        if (!candidate.startsWith(projectRoot)) {
            throw new LoadException("include escapes project root: " + relative);
        }
        if (!Files.exists(candidate)) {
            if (reference.conditional()) {
                incomplete(relative, scanner.byteOffset(reference.charIndex()),
                        "MISSING_CONDITIONAL_INCLUDE", "possible include target is missing");
                return null;
            }
            throw new LoadException("included source is missing: " + projectRoot.relativize(candidate));
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(projectRoot)) {
                throw new LoadException("include escapes project root: " + relative);
            }
            return real;
        } catch (IOException exception) {
            throw new LoadException("cannot resolve include: " + relative);
        }
    }

    private void incomplete(Path source, int byteOffset, String code, String detail) {
        complete = false;
        diagnostics.add(new Diagnostic(source, byteOffset, code, detail));
    }

    private void addAssets(Path relative,
            TexSourceScanner.AssetReference reference, TexSourceScanner scanner)
            throws LoadException {
        String[] names = reference.command().equals("bibliography")
                ? reference.literal().split(",", -1) : new String[] {reference.literal()};
        for (String value : names) {
            String literal = value.trim();
            int byteOffset = scanner.byteOffset(reference.charIndex());
            if (!literal.matches("[A-Za-z0-9_./-]+")) {
                diagnostics.add(new Diagnostic(relative, byteOffset, "UNRESOLVED_ASSET",
                        "asset path is not a supported literal"));
                assets.add(new AssetReference(relative, byteOffset, reference.command(),
                        literal, null, null, false));
                continue;
            }
            if (Path.of(literal).isAbsolute()) {
                throw new LoadException("asset escapes project root: " + relative);
            }
            Path candidate = projectRoot.resolve(literal).normalize();
            if (!candidate.startsWith(projectRoot)) {
                throw new LoadException("asset escapes project root: " + relative);
            }
            if (!candidate.getFileName().toString().contains(".")) {
                String[] extensions = reference.command().equals("includegraphics")
                        ? new String[] {".pdf", ".png", ".jpg", ".jpeg", ".eps"}
                        : new String[] {".bib"};
                List<Path> matches = new ArrayList<>();
                for (String extension : extensions) {
                    Path option = candidate.resolveSibling(candidate.getFileName() + extension);
                    if (Files.isRegularFile(option)) {
                        matches.add(option);
                    }
                }
                if (matches.size() > 1) {
                    diagnostics.add(new Diagnostic(relative, byteOffset, "AMBIGUOUS_ASSET",
                            "asset extension matches multiple local files"));
                    assets.add(new AssetReference(relative, byteOffset, reference.command(),
                            literal, null, null, false));
                    continue;
                }
                if (matches.size() == 1) {
                    candidate = matches.getFirst();
                }
            }
            if (!Files.isRegularFile(candidate)) {
                diagnostics.add(new Diagnostic(relative, byteOffset, "MISSING_ASSET",
                        "static asset reference is not a local regular file"));
                assets.add(new AssetReference(relative, byteOffset, reference.command(),
                        literal, null, null, false));
                continue;
            }
            try {
                Path real = candidate.toRealPath();
                if (!real.startsWith(projectRoot)) {
                    throw new LoadException("asset escapes project root: " + relative);
                }
                assets.add(new AssetReference(relative, byteOffset, reference.command(),
                        literal, projectRoot.relativize(real), sha256File(real), true));
            } catch (IOException exception) {
                throw new LoadException("cannot read asset: " + relative);
            }
        }
    }

    private String fingerprint(Path main, Map<Path, SourceFile> files) {
        StringBuilder manifest = new StringBuilder(projectRoot.toString())
                .append('\0').append(main);
        files.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> manifest.append('\n').append(entry.getKey())
                        .append('\0').append(entry.getValue().hash()));
        assets.stream().filter(AssetReference::resolved)
                .sorted(Comparator.comparing(item -> item.target().toString()))
                .forEach(item -> manifest.append("\nasset:").append(item.target())
                        .append('\0').append(item.hash()));
        return sha256(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256File(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record LoadedSource(byte[] bytes, String hash, TexSourceScanner scanner,
            TexSourceScanner.Scan scan) { }

    static final class LoadException extends Exception {
        LoadException(String message) {
            super(message);
        }
    }
}
