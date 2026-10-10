package texsuite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import texsuite.DocumentSnapshot.SourceContext;

final class RenameCandidateDiscovery {
    Result discover(DocumentSnapshot snapshot, RenameRequest request) {
        if (!snapshot.root().resolve(snapshot.main()).equals(request.target())) {
            throw new IllegalArgumentException("request target differs from snapshot entry file");
        }
        if ((request.scope() == RenameRequest.Scope.FILE
                && snapshot.coverage() != DocumentSnapshot.Coverage.FILE)
                || (request.scope() == RenameRequest.Scope.PROJECT
                && snapshot.coverage() != DocumentSnapshot.Coverage.STATIC_CLOSURE)) {
            throw new IllegalArgumentException("snapshot coverage differs from requested scope");
        }
        if (request.scope() == RenameRequest.Scope.PROJECT && !snapshot.complete()) {
            throw new IllegalArgumentException("static include closure is incomplete");
        }

        Set<Path> definitelyReachable = snapshot.definitelyReachable();
        List<Occurrence> occurrences = new ArrayList<>();
        for (var entry : snapshot.files().entrySet()) {
            occurrences.addAll(scanFile(request, entry.getKey(), entry.getValue(), definitelyReachable));
        }
        occurrences.sort(Comparator.comparing((Occurrence item) -> item.path().toString())
                .thenComparingInt(Occurrence::startByte));
        return new Result(List.copyOf(occurrences), snapshot.files().size());
    }

    private static List<Occurrence> scanFile(RenameRequest request, Path path,
            DocumentSnapshot.SourceFile file, Set<Path> definitelyReachable) {
        List<Occurrence> occurrences = new ArrayList<>();
        byte[] bytes = file.bytes();
        String text = new String(bytes, StandardCharsets.UTF_8);
        TexSourceScanner scanner = new TexSourceScanner(text);
        TexSourceScanner.Scan scan = scanner.scanFor(request.source());
        checkCoverage(text, request.source(), scan.occurrences());
        for (int index = 0; index < scan.occurrences().size(); index++) {
            TexSourceScanner.RawOccurrence raw = scan.occurrences().get(index);
            int startChar = raw.charIndex();
            int endChar = startChar + request.source().length();
            Classification classification = classify(scanner, scan, index,
                    request.source().length(), definitelyReachable.contains(path));
            int start = scanner.byteOffset(startChar);
            int end = scanner.byteOffset(endChar);
            occurrences.add(new Occurrence(id(path, file.hash(), start, end, request.source()),
                    path, file.hash(), start, end, scanner.line(startChar),
                    scanner.column(startChar), classification.status(), classification.reason()));
        }
        return List.copyOf(occurrences);
    }

    private static Classification classify(TexSourceScanner scanner, TexSourceScanner.Scan scan,
            int index, int length, boolean definitelyReachable) {
        TexSourceScanner.RawOccurrence raw = scan.occurrences().get(index);
        int startChar = raw.charIndex();
        int endChar = startChar + length;
        Status status;
        String reason;
        SourceContext context = raw.reason();
        boolean symbol = scanner.mathSymbol(startChar, endChar);
        if (context == SourceContext.UNKNOWN) {
            status = Status.REVIEW;
            reason = "unknown scanner context";
        } else if (context != SourceContext.MATH && !symbol) {
            status = Status.EXCLUDED;
            reason = context == SourceContext.PROSE ? "outside mathematical context"
                    : context.name().toLowerCase(java.util.Locale.ROOT);
        } else if (scan.overlaps(index, length)) {
            status = Status.REVIEW;
            reason = "overlapping literal matches";
        } else if (scanner.partialGroup(startChar, endChar)) {
            status = Status.REVIEW;
            reason = "partial TeX group";
        } else if (!scan.problems().isEmpty()) {
            status = Status.REVIEW;
            reason = "scanner diagnostic in source";
        } else if (!definitelyReachable) {
            status = Status.REVIEW;
            reason = "only conditionally reachable";
        } else if (raw.conditional()) {
            status = Status.REVIEW;
            reason = "inside conditional";
        } else if (!symbol && scanner.adjacentLetters(startChar, endChar)) {
            status = Status.REVIEW;
            reason = "adjacent letter";
        } else {
            status = Status.CANDIDATE;
            reason = "math context; meaning still requires review";
        }
        return new Classification(status, reason);
    }

    private record Classification(Status status, String reason) { }

    private static void checkCoverage(String text, String source,
            List<TexSourceScanner.RawOccurrence> occurrences) {
        int next = 0;
        for (int index = text.indexOf(source); index >= 0;
                index = text.indexOf(source, index + 1)) {
            if (next >= occurrences.size() || occurrences.get(next).charIndex() != index) {
                throw new IllegalStateException("scanner did not account for every literal match");
            }
            next++;
        }
        if (next != occurrences.size()) {
            throw new IllegalStateException("scanner recorded a literal match more than once");
        }
    }


    static String id(Path path, String hash, int start, int end, String source) {
        String key = path.toString() + '\0' + hash + '\0' + start + '\0' + end + '\0'
                + source;
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    enum Status {
        CANDIDATE, REVIEW, EXCLUDED
    }

    record Occurrence(String id, Path path, String sourceHash, int startByte, int endByte,
            int line, int column, Status status, String reason) { }

    record Result(List<Occurrence> occurrences, int fileCount) {
        Result {
            occurrences = List.copyOf(occurrences);
        }

        long count(Status status) {
            return occurrences.stream().filter(item -> item.status() == status).count();
        }
    }
}
