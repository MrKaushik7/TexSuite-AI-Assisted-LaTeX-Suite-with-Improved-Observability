package texsuite;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record DocumentSnapshot(Path root, Path main, Map<Path, SourceFile> files,
        List<IncludeSite> includeSites, List<AssetReference> assets,
        List<Diagnostic> diagnostics, List<ProtectedRegion> protectedRegions,
        String fingerprint, boolean complete) {
    DocumentSnapshot {
        files = Map.copyOf(files);
        includeSites = List.copyOf(includeSites);
        assets = List.copyOf(assets);
        diagnostics = List.copyOf(diagnostics);
        protectedRegions = List.copyOf(protectedRegions);
    }

    record SourceFile(Path path, byte[] bytes, String hash) {
        SourceFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record IncludeSite(Path source, int byteOffset, String literal, Path target,
            boolean conditional, boolean resolved) { }

    record AssetReference(Path source, int byteOffset, String command, String literal,
            Path target, String hash, boolean resolved) { }

    record Diagnostic(Path source, int byteOffset, String code, String detail) { }

    record ProtectedRegion(Path source, int startByte, int endByte,
            SourceContext reason) { }

    enum SourceContext {
        MATH, PROSE, COMMENT, VERBATIM,
        DEFINITION, CONTROL_SEQUENCE, TEXT_ARGUMENT, METADATA, UNKNOWN
    }
}
