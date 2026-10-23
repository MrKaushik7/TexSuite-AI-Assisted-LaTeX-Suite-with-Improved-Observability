package texsuite;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import picocli.CommandLine;

final class SanitationWorkflow {
    private final EditReview review;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Path selectedFile;
    private final TexCompileGate compileGate;

    SanitationWorkflow(BufferedReader input, PrintWriter out, PrintWriter err, Path selectedFile,
            TexCompileGate compileGate) {
        this.review = new EditReview(input, out);
        this.out = out;
        this.err = err;
        this.selectedFile = selectedFile;
        this.compileGate = compileGate;
    }

    int run() {
        try {
            if (!selectedFile.toRealPath().equals(selectedFile)) throw new IOException("Selected file identity changed; select it again.");
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    inspectAndReview();
                    return CommandLine.ExitCode.OK;
                } catch (StaleSourceException exception) {
                    if (attempt == 2) throw exception;
                    out.println("Saved source changed; discarding selection and preparing a fresh review.");
                }
            }
            throw new IOException("Saved source kept changing; inspect again.");
        } catch (EditReview.Back exception) {
            out.println("Back to menu; no source changes.");
            return CommandLine.ExitCode.OK;
        } catch (EditReview.Cancel | EOFException exception) {
            out.println("Cancelled without source changes.");
            return CommandLine.ExitCode.OK;
        } catch (IOException | DocumentLoader.LoadException exception) {
            err.println("Could not complete sanitation: " + DocumentInput.safeDisplay(exception.getMessage()));
            return CommandLine.ExitCode.USAGE;
        }
    }

    private void inspectAndReview() throws IOException, DocumentLoader.LoadException {
        DocumentSnapshot snapshot = new DocumentLoader().load(selectedFile);
        if (!snapshot.root().resolve(snapshot.main()).equals(selectedFile)) throw new IOException("Selected file identity changed.");
        var report = new SanitationPlanner().inspect(snapshot);
        out.println("Sanitation: " + DocumentInput.safeDisplay(snapshot.main()) + " (selected file only; offline).");
        out.printf("Line endings: LF=%d, CRLF=%d, CR=%d.%n", report.lfCount(), report.crlfCount(), report.crCount());
        out.printf("Selectable trailing-whitespace fixes: %d. Advisory findings: %d.%n",
                report.trailing().size(), report.findings().size() + report.omittedFindings());
        for (var finding : report.findings()) {
            out.printf("  ADVISORY %s:%d:%d [%s] %s%n", DocumentInput.safeDisplay(finding.path()),
                    finding.line(), finding.column(), finding.rule(), DocumentInput.safeDisplay(finding.message()));
        }
        if (report.omittedFindings() > 0) out.printf("  %d further advisory rows omitted; inspect again after manual repairs.%n", report.omittedFindings());
        if (report.lfBlock() != null) out.println(report.lfBlock());
        if (report.crlfBlock() != null) out.println(report.crlfBlock());
        if (report.trailing().isEmpty() && report.lf().isEmpty() && report.crlf().isEmpty()) {
            out.println("No supported safe fixes available; no source changes.");
            return;
        }
        for (int index = 0; index < report.trailing().size(); index++) {
            var edit = report.trailing().get(index);
            out.printf("  %d. %s:%d:%d remove %s%n", index + 1, DocumentInput.safeDisplay(edit.path()),
                    edit.line(), edit.column(), SanitationPlanner.visible(new String(edit.expected(), StandardCharsets.UTF_8)));
        }
        List<TextEditPlan.Edit> selected = new ArrayList<>(selectTrailing(report.trailing()));
        while (true) {
            String answer = review.answer("Normalize line endings? [Enter=keep, lf, crlf]: ").trim().toLowerCase(java.util.Locale.ROOT);
            if (answer.isEmpty() || answer.equals("keep")) break;
            if (answer.equals("lf") || answer.equals("crlf")) {
                String block = answer.equals("lf") ? report.lfBlock() : report.crlfBlock();
                if (block != null) { out.println(block); continue; }
                selected.addAll(answer.equals("lf") ? report.lf() : report.crlf());
                break;
            }
            out.println("Choose keep, lf or crlf.");
        }
        if (selected.isEmpty()) {
            out.println("No fixes selected; no source changes.");
            return;
        }
        if (selected.size() > SanitationPlanner.MAX_EDITS) {
            out.println("Combined selection exceeds 200 edits; inspect again and choose fewer trailing fixes or keep line endings.");
            return;
        }
        selected.sort(Comparator.comparingInt(TextEditPlan.Edit::start));
        var plan = new TextEditPlan(snapshot, selected, compileGate);
        var updates = plan.preview();
        out.println("Exact whitespace preview (␠ = space, ⇥ = tab, <CR>/<LF> = line-ending bytes, ∅ = deletion):");
        for (var edit : selected) {
            out.printf("  %s:%d:%d [%s] %s -> %s%n", DocumentInput.safeDisplay(edit.path()),
                    edit.line(), edit.column(), edit.rule(), SanitationPlanner.visible(new String(edit.expected(), StandardCharsets.UTF_8)),
                    edit.replacement().isEmpty() ? "∅" : SanitationPlanner.visible(edit.replacement()));
        }
        review.printUnifiedDiff(snapshot, updates, selected.stream().noneMatch(edit -> edit.rule().equals(SanitationPlanner.EOL)));
        if (!review.yes("Apply these " + selected.size() + " sanitation fixes? [y/N]: ")) {
            out.println("Cancelled without source changes.");
            return;
        }
        TexCompileGate resolved = review.compilationMain(snapshot, selected, compileGate);
        // Rebuild only the gate; selected bytes and their approval remain tied to this snapshot.
        Path backup = new TextEditPlan(snapshot, selected, resolved).apply();
        out.printf("Applied %d sanitation fix(es). Backup: %s%n", selected.size(), DocumentInput.safeDisplay(backup));
        out.println("Exact edit history: " + DocumentInput.safeDisplay(backup.resolve("changes.json")));
        out.println("Use Revert last change to restore the exact original bytes.");
    }

    private List<TextEditPlan.Edit> selectTrailing(List<TextEditPlan.Edit> fixes) throws IOException {
        if (fixes.isEmpty()) return List.of();
        while (true) {
            String answer = review.answer("Select trailing fixes [Enter=none, all, or numbers separated by spaces]: ").trim();
            if (answer.isEmpty()) return List.of();
            if (answer.equalsIgnoreCase("all")) return fixes;
            try {
                var indexes = new LinkedHashSet<Integer>();
                for (String token : answer.split("\\s+")) {
                    int index = Integer.parseInt(token);
                    if (index < 1 || index > fixes.size()) throw new NumberFormatException();
                    indexes.add(index - 1);
                }
                return indexes.stream().map(fixes::get).toList();
            } catch (NumberFormatException exception) {
                out.println("Choose all, Enter for none, or valid fix numbers.");
            }
        }
    }
}
