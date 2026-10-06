package texsuite;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

record DocumentSnapshot(Path root, Path main, Map<Path, SourceFile> files,
        List<IncludeSite> includeSites, List<AssetReference> assets,
        List<Diagnostic> diagnostics, List<ProtectedRegion> protectedRegions,
        String fingerprint, boolean complete, Coverage coverage) {
    DocumentSnapshot {
        files = Map.copyOf(files);
        includeSites = List.copyOf(includeSites);
        assets = List.copyOf(assets);
        diagnostics = List.copyOf(diagnostics);
        protectedRegions = List.copyOf(protectedRegions);
    }

    Set<Path> definitelyReachable() {
        Set<Path> reachable = new HashSet<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.add(main());
        while (!pending.isEmpty()) {
            Path current = pending.removeFirst();
            if (!reachable.add(current)) {
                continue;
            }
            for (DocumentSnapshot.IncludeSite site : includeSites()) {
                if (site.source().equals(current) && site.resolved() && !site.conditional()) {
                    pending.add(site.target());
                }
            }
        }
        return reachable;
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

    enum Coverage {
        FILE, STATIC_CLOSURE
    }
}
