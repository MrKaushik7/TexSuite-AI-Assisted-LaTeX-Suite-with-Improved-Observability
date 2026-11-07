package texsuite;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Interactive resolution only; planner and transaction retain all authorization checks. */
final class MergeWorkflow {
    private final EditReview review;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Path selected;
    private final TexCompileGate gate;
    private final Supplier<Optional<Path>> filePicker;
    private final Consumer<Path> donorSelected;
    private Path historyRoot;

    MergeWorkflow(BufferedReader input, PrintWriter out, PrintWriter err, Path selected, TexCompileGate gate) {
        this(input, out, err, selected, gate, DocumentInput::openNativePicker);
    }

    MergeWorkflow(BufferedReader input, PrintWriter out, PrintWriter err, Path selected, TexCompileGate gate,
            Supplier<Optional<Path>> filePicker) {
        this(input, out, err, selected, gate, filePicker, donor -> { });
    }

    MergeWorkflow(BufferedReader input, PrintWriter out, PrintWriter err, Path selected, TexCompileGate gate,
            Supplier<Optional<Path>> filePicker, Consumer<Path> donorSelected) {
        this.review = new EditReview(input,out); this.out=out; this.err=err; this.selected=selected; this.gate=gate;
        this.filePicker = filePicker;
        this.donorSelected = donorSelected;
        historyRoot=selected.getParent();
    }

    Path historyRoot() { return historyRoot; }

    int run() {
        try {
            Path donor = path("Donor .tex document path [Enter=browse, or type a path]: ",null);
            donorSelected.accept(donor);
            Path defaultMain = gate.mainFor(new DocumentLoader().load(selected));
            Path main = path("Master compilation main [Enter=" + DocumentInput.safeDisplay(defaultMain) + ", or browse]: ",defaultMain);
            for (int attempt=0; attempt<3; attempt++) {
                try {
                    var masterSnapshot=new DocumentLoader().load(main);
                    var donorSnapshot=new DocumentLoader().load(donor);
                    if (!selected.startsWith(masterSnapshot.root())) throw new IOException("Selected master target is outside the compilation project.");
                    var planner=new MergePlanner(masterSnapshot,masterSnapshot.root().relativize(selected),donorSnapshot);
                    historyRoot=masterSnapshot.root();
                    out.println("Master class, options and existing definitions take precedence. Imported content adopts master formatting.");
                    out.println("Donor stays unchanged. Unresolved conflicts block apply. Compilation is mandatory.");
                    var range=selection(planner.donorText());
                    int anchor=anchor(planner.targetText(), selected.equals(main));
                    var proposal=planner.prepare(range,anchor,new MergePlanner.Resolver() {
                        public String choose(String question,List<String> choices) throws IOException {
                            out.println(DocumentInput.safeDisplay(question));
                            for (int i=0;i<choices.size();i++) out.println("  " + (i+1) + ". " + choices.get(i));
                            while (true) {
                                String answer=review.answer("Choose explicitly (Enter cancels): ").trim();
                                if (answer.isEmpty()) return "cancel";
                                if (choices.contains(answer)) return answer;
                                try { int index=Integer.parseInt(answer); if(index>0&&index<=choices.size()) return choices.get(index-1); } catch(NumberFormatException ignored) { }
                                out.println("Choose one of the listed numbers or names.");
                            }
                        }
                        public String value(String prompt) throws IOException { return review.answer(prompt + ": ").trim(); }
                        public String definition(String name) throws IOException {
                            out.println("Enter one supported definition for " + name + "; finish with a line containing only .");
                            StringBuilder text=new StringBuilder();
                            while(true) {
                                String line=review.answer("definition> "); if(line.equals(".")) break;
                                text.append(line).append('\n'); if(text.length()>16384) throw new IOException("Definition exceeds 16,384 characters.");
                            }
                            return text.toString();
                        }
                    });
                    out.println("Resolved merge:");
                    for(String decision:proposal.decisions()) out.println("  " + DocumentInput.safeDisplay(decision));
                    out.printf("Insertion: %s, character %d; donor %s%n",DocumentInput.safeDisplay(selected),anchor,range.label());
                    review.printUnifiedDiff(masterSnapshot,proposal.updates());
                    for(var file:proposal.creates().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                        out.printf("CREATE %s (%d bytes, SHA-256 %s)%n",DocumentInput.safeDisplay(file.getKey()),file.getValue().length,TextEditPlan.sha256(file.getValue()));
                        if(file.getKey().toString().endsWith(".tex")||file.getKey().toString().endsWith(".bib")) out.println(DocumentInput.safeDisplay(new String(file.getValue(),StandardCharsets.UTF_8)));
                    }
                    if(!review.yes("Compile and apply this exact merge? [y/N]: ")) { out.println("Merge cancelled; no source changes."); return 0; }
                    Path recovery=MergeTransaction.apply(proposal,gate.withMain(main), warning -> out.println(warning));
                    out.println("Merge applied. History: " + DocumentInput.safeDisplay(recovery));
                    out.println("Use menu 7 to undo the whole merge, including unchanged imported files.");
                    return 0;
                } catch(StaleSourceException stale) {
                    out.println("Saved inputs changed; discarding choices and preview. " + DocumentInput.safeDisplay(stale.getMessage()));
                    if(attempt==2) throw stale;
                }
            }
        } catch(EditReview.Back | EditReview.Cancel | EOFException cancelled) {
            out.println("Merge cancelled; no source changes.");
        } catch(IOException | DocumentLoader.LoadException | IllegalArgumentException failure) {
            err.println("Merge stopped: " + DocumentInput.safeDisplay(failure.getMessage()));
        }
        return 0;
    }

    private Path path(String prompt,Path fallback) throws IOException {
        while(true) {
            String answer=review.answer(prompt).trim();
            try {
                Path path;
                if (answer.equalsIgnoreCase("browse") || (answer.isEmpty() && fallback == null)) {
                    Optional<Path> picked = filePicker.get();
                    if (picked.isEmpty()) {
                        out.println("No file selected; enter a path, browse again, or back.");
                        continue;
                    }
                    path = picked.get().toAbsolutePath().normalize();
                } else {
                    path = answer.isEmpty() ? fallback : Path.of(answer).toAbsolutePath().normalize();
                }
                if(!path.toString().endsWith(".tex")) throw new IOException("Choose a .tex file.");
                path=path.toRealPath(); new DocumentLoader().load(path); return path;
            } catch (java.awt.HeadlessException | java.awt.AWTError | SecurityException error) {
                out.println("Native file picker is unavailable here; enter a path instead, or back.");
            } catch(IOException | DocumentLoader.LoadException | java.nio.file.InvalidPathException error) {
                out.println("Cannot use that file: " + DocumentInput.safeDisplay(error.getMessage()));
            }
        }
    }

    private MergePlanner.Range selection(String text) throws IOException {
        var sections=MergePlanner.sections(text);
        for(int i=0;i<sections.size();i++) out.println("  " + (i+1) + ". " + DocumentInput.safeDisplay(sections.get(i).label()));
        while(true) {
            String answer=review.answer("Donor content [Enter=whole body, section number, or lines START END]: ").trim();
            if(answer.isEmpty()) return MergePlanner.body(text);
            try {
                if(answer.startsWith("lines ")) { var parts=answer.split("\\s+"); if(parts.length==3) return MergePlanner.lines(text,Integer.parseInt(parts[1]),Integer.parseInt(parts[2])); }
                else { int index=Integer.parseInt(answer); if(index>0&&index<=sections.size()) return sections.get(index-1); }
            } catch(NumberFormatException ignored) { }
            out.println("Choose a listed section or a valid line range.");
        }
    }

    private int anchor(String text,boolean root) throws IOException {
        var sections=MergePlanner.sections(text);
        for(int i=0;i<sections.size();i++) out.println("  " + (i+1) + ". " + DocumentInput.safeDisplay(sections.get(i).label()));
        while(true) {
            String answer=review.answer("Master insertion [Enter=end of body, before NUMBER, after NUMBER]: ").trim();
            if(answer.isEmpty()) return root ? MergePlanner.body(text).end() : text.length();
            var parts=answer.split("\\s+");
            try {
                if(parts.length==2) { int index=Integer.parseInt(parts[1])-1; if(index>=0&&index<sections.size()) {
                    if(parts[0].equals("before")) return sections.get(index).start();
                    if(parts[0].equals("after")) return sections.get(index).end();
                } }
            } catch(NumberFormatException ignored) { }
            out.println("Choose before/after a listed section or Enter.");
        }
    }
}
