package texsuite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Resolves a bounded donor closure into an explicit master-side proposal. */
final class MergePlanner {
    private static final Set<String> BUILTINS = Set.copyOf(Arrays.asList(("documentclass usepackage RequirePackage begin end section subsection subsubsection chapter part paragraph subparagraph "
            + "label ref pageref eqref cite citep citet nocite bibliography bibliographystyle input include includegraphics "
            + "textbf textit texttt textrm textsf textsc emph underline footnote caption item title author date maketitle "
            + "centering raggedright raggedleft small large Large LARGE huge Huge tiny normalsize scriptsize footnotesize "
            + "textwidth linewidth columnwidth hsize vspace hspace vfill hfill noindent par newpage clearpage tableofcontents "
            + "appendix today LaTeX TeX textbackslash textasciitilde textasciicircum ldots cdots vdots ddots dots "
            + "frac sqrt overline bar hat tilde vec dot ddot left right big Big bigg Bigg sum prod int iint oint lim min max "
            + "sin cos tan log ln exp det gcd sup inf alpha beta gamma delta epsilon varepsilon zeta eta theta vartheta iota "
            + "kappa lambda mu nu xi pi varpi rho sigma tau upsilon phi varphi chi psi omega Gamma Delta Theta Lambda Xi Pi Sigma Upsilon Phi Psi Omega "
            + "infty partial nabla forall exists in notin subset subseteq supset supseteq cup cap emptyset le leq ge geq neq approx sim simeq equiv "
            + "times cdot pm mp div to rightarrow leftarrow Rightarrow Leftarrow iff mapsto neg land lor ldots mathrm mathbf mathit mathsf mathtt mathcal "
            + "text normalfont bfseries itshape rmfamily sffamily ttfamily ensuremath protect relax newline linebreak nobreakline "
            + "multicolumn hline cline rule fbox mbox makebox raisebox setlength addtolength setcounter addtocounter arabic roman Roman alph Alph "
            + "thanks url href phantom vphantom hphantom null quad qquad limits nolimits nonumber notag tag displaystyle textstyle scriptstyle "
            + "theequation thesection thesubsection thepage baselineskip tabcolsep arraystretch baselinestretch").split(" ")));
    private static final Set<String> ENVIRONMENTS = Set.of("document", "equation", "equation*", "displaymath", "math", "itemize", "enumerate", "description",
            "figure", "figure*", "table", "table*", "tabular", "tabular*", "center", "flushleft", "flushright", "quote", "quotation", "abstract", "minipage", "thebibliography");
    private static final Map<String, String> PROVIDERS = Map.ofEntries(
            Map.entry("includegraphics", "graphicx"), Map.entry("mathbb", "amssymb"), Map.entry("mathfrak", "amssymb"),
            Map.entry("text", "amsmath"), Map.entry("eqref", "amsmath"), Map.entry("DeclareMathOperator", "amsmath"),
            Map.entry("url", "url"), Map.entry("href", "hyperref"), Map.entry("citep", "natbib"), Map.entry("citet", "natbib"),
            Map.entry("env:align", "amsmath"), Map.entry("env:align*", "amsmath"), Map.entry("env:gather", "amsmath"),
            Map.entry("env:gather*", "amsmath"), Map.entry("env:split", "amsmath"), Map.entry("env:cases", "amsmath"),
            Map.entry("env:matrix", "amsmath"), Map.entry("env:pmatrix", "amsmath"), Map.entry("env:bmatrix", "amsmath"));
    private final DocumentSnapshot master;
    private final DocumentSnapshot donor;
    private final Path target;
    private final Map<String, MergeSyntax.Definition> masterDefinitions;
    private final Map<String, MergeSyntax.Definition> donorDefinitions;
    private final Map<String, String> masterPackages;
    private final Map<String, String> donorPackages;
    private final String masterMain;
    private final String targetText;
    private final String donorText;

    MergePlanner(DocumentSnapshot master, Path target, DocumentSnapshot donor) throws IOException {
        this.master = master;
        this.donor = donor;
        this.target = target;
        requireCurrent();
        if (!master.definitelyReachable().contains(target)) throw new IOException("Master edit target must be unconditionally reachable from its compilation main.");
        if (donor.files().keySet().stream().anyMatch(p -> donor.root().resolve(p).equals(master.root().resolve(target))
                || donor.root().resolve(p).equals(master.root().resolve(master.main())))) throw new IOException("Master edits would change donor input.");
        masterMain = source(master, master.main()); targetText = source(master, target); donorText = source(donor, donor.main());
        masterDefinitions = inventory(master); donorDefinitions = inventory(donor);
        masterPackages = packages(master); donorPackages = packages(donor);
        if (masterPackages.containsKey("biblatex") || donorPackages.containsKey("biblatex")) throw new IOException("biblatex/Biber merge is not supported; use a BibTeX pair.");
        body(masterMain); body(donorText);
    }

    void requireCurrent() throws IOException {
        if (!new DocumentLoader().isCurrent(master) || !new DocumentLoader().isCurrent(donor)) throw new StaleSourceException("Master or donor dependency closure is incomplete or changed.");
    }

    String donorText() { return donorText; }
    String targetText() { return targetText; }
    DocumentSnapshot master() { return master; }

    static String source(DocumentSnapshot snapshot, Path path) { return new String(snapshot.files().get(path).bytes(), StandardCharsets.UTF_8); }

    static Range body(String text) throws IOException {
        int start = -1, end = -1;
        for (var t : MergeSyntax.tokens(text)) {
            if (Set.of("begin", "end").contains(t.name())) {
                var g = MergeSyntax.group(text, t.end(), '{', '}');
                if (g.value().equals("document")) {
                    if (t.name().equals("begin")) { if (start >= 0) throw new IOException("Multiple document bodies."); start = g.end(); }
                    else end = t.start();
                }
            }
        }
        if (start < 0 || end < start) throw new IOException("Select a complete document root with begin/end document.");
        return new Range(start, end, "whole body");
    }

    static List<Range> sections(String text) throws IOException {
        var ts = MergeSyntax.tokens(text);
        var result = new ArrayList<Range>();
        List<String> levels = List.of("part", "chapter", "section", "subsection", "subsubsection", "paragraph", "subparagraph");
        for (int i = 0; i < ts.size(); i++) {
            var t = ts.get(i);
            if (!levels.contains(t.name())) continue;
            int end = text.length();
            for (int j = i + 1; j < ts.size(); j++) {
                var next = ts.get(j);
                if ((levels.contains(next.name()) && levels.indexOf(next.name()) <= levels.indexOf(t.name()))
                        || (next.name().equals("end") && MergeSyntax.group(text, next.end(), '{', '}').value().equals("document"))) { end = next.start(); break; }
            }
            var g = argument(text, t);
            result.add(new Range(t.start(), end, t.name() + ": " + g.value()));
        }
        return List.copyOf(result);
    }

    static Range lines(String text, int first, int last) throws IOException {
        if (first < 1 || last < first) throw new IOException("Invalid line range.");
        int line = 1, start = first == 1 ? 0 : -1, end = text.length();
        for (int p = 0; p < text.length(); p++) {
            if (text.charAt(p) == '\r' || text.charAt(p) == '\n') {
                if (text.charAt(p) == '\r' && p + 1 < text.length() && text.charAt(p + 1) == '\n') p++;
                line++;
                if (line == first) start = p + 1;
                if (line == last + 1) { end = p + 1; break; }
            }
        }
        if (start < 0 || line < last) throw new IOException("Line range exceeds source.");
        return new Range(start, end, "lines " + first + "–" + last);
    }

    Proposal prepare(Range selected, int anchor, Resolver resolver) throws IOException {
        requireCurrent();
        Range donorBody = body(donorText);
        if (selected.start() < donorBody.start() || selected.end() > donorBody.end() || selected.start() >= selected.end()) throw new IOException("Selection must be nonempty and within the donor body.");
        Range masterBody = target.equals(master.main()) ? body(targetText) : new Range(0, targetText.length(), "chapter");
        if (anchor < masterBody.start() || anchor > masterBody.end()) throw new IOException("Insertion must be inside the master body.");
        // Both sides must be balanced; a line inside a group/environment is not a safe boundary.
        MergeSyntax.tokens(targetText.substring(masterBody.start(), anchor));
        MergeSyntax.tokens(targetText.substring(anchor, masterBody.end()));
        String selectedText = donorText.substring(selected.start(), selected.end());
        MergeSyntax.tokens(selectedText);
        String identity = TextEditPlan.sha256((master.fingerprint() + donor.fingerprint() + selected + anchor).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        State state = new State(resolver, Path.of("texsuite-imports", identity));
        state.decisions.add("Master " + master.root().resolve(master.main()) + " fingerprint " + master.fingerprint());
        state.decisions.add("Donor " + donor.root().resolve(donor.main()) + " fingerprint " + donor.fingerprint() + "; selected " + selected + "; insertion " + target + ":" + anchor);
        state.contents.put(donor.main(), selectedText);
        gather(state, donor.main(), selectedText, new LinkedHashSet<>());
        for (String text : List.copyOf(state.contents.values())) for (String use : uses(text)) resolve(state, use, new LinkedHashSet<>());
        reconcileLabels(state);
        reconcileBibliography(state);
        var updates = new TreeMap<Path, byte[]>();
        var creates = new TreeMap<Path, byte[]>();
        for (var entry : state.contents.entrySet()) {
            String transformed = transform(state, entry.getKey(), entry.getValue());
            if (entry.getKey().equals(donor.main())) selectedText = transformed;
            else creates.put(state.folder.resolve(entry.getKey()), transformed.getBytes(StandardCharsets.UTF_8));
        }
        creates.putAll(state.assets);
        StringBuilder preamble = new StringBuilder();
        for (String pkg : state.packages) preamble.append("\\usepackage").append(donorPackages.getOrDefault(pkg, "").isEmpty() ? "" : "[" + donorPackages.get(pkg) + "]").append('{').append(pkg).append("}\n");
        for (var entry : state.imports.entrySet()) preamble.append(MergeSyntax.rewrite(entry.getValue().declaration(), state.mapping)).append('\n');
        var edits = new ArrayList<TextEditPlan.Edit>();
        addEdit(edits, target, anchor, anchor, "\n" + selectedText + "\n", "merge-body");
        if (!preamble.isEmpty()) addEdit(edits, master.main(), documentStart(masterMain), documentStart(masterMain), preamble.toString(), "merge-preamble");
        if (state.bibliography != null) {
            creates.put(state.folder.resolve("references.bib"), state.bibliography.getBytes(StandardCharsets.UTF_8));
            var bib = commands(masterMain, "bibliography");
            if (bib.size() != 1) throw new IOException("Master needs one literal bibliography command in its main file.");
            var group = argument(masterMain, bib.getFirst());
            addEdit(edits, master.main(), group.start(), group.end() - 1, group.value() + "," + state.folder.resolve("references").toString().replace('\\','/'), "merge-bibliography");
        }
        for (Path path : new TreeSet<>(edits.stream().map(TextEditPlan.Edit::path).toList())) {
            String text = source(master, path);
            var fileEdits = edits.stream().filter(e -> e.path().equals(path)).sorted(java.util.Comparator.comparingInt(TextEditPlan.Edit::start).reversed()).toList();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            for (var edit : fileEdits) {
                var output = new java.io.ByteArrayOutputStream(); output.write(bytes, 0, edit.start()); output.writeBytes(edit.replacement().getBytes(StandardCharsets.UTF_8)); output.write(bytes, edit.end(), bytes.length - edit.end()); bytes = output.toByteArray();
            }
            updates.put(path, bytes);
        }
        for (Path path : creates.keySet()) if (Files.exists(master.root().resolve(path), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Import destination already exists: " + path);
        requireCurrent();
        return new Proposal(master, List.copyOf(edits), updates, creates, List.copyOf(state.decisions), this::requireCurrent);
    }

    private static int documentStart(String text) throws IOException {
        for (var token : MergeSyntax.tokens(text)) {
            if (token.name().equals("begin") && MergeSyntax.group(text, token.end(), '{', '}').value().equals("document")) return token.start();
        }
        throw new IOException("Missing document start.");
    }

    private void addEdit(List<TextEditPlan.Edit> edits, Path path, int start, int end, String replacement, String rule) {
        String text = source(master, path);
        var scanner = new TexSourceScanner(text);
        int a = scanner.byteOffset(start), b = scanner.byteOffset(end);
        edits.add(new TextEditPlan.Edit(path, a, b, Arrays.copyOfRange(master.files().get(path).bytes(), a, b), scanner.line(start), scanner.editorColumn(start), replacement, rule));
    }

    private static Map<String, MergeSyntax.Definition> inventory(DocumentSnapshot snapshot) throws IOException {
        var definitions = new LinkedHashMap<String, MergeSyntax.Definition>();
        for (Path path : new TreeSet<>(snapshot.files().keySet())) {
            if (!snapshot.definitelyReachable().contains(path)) throw new IOException("Conditional dependencies are unsupported for merge.");
            String text = source(snapshot, path);
            for (var definition : MergeSyntax.definitions(text)) {
                if (definitions.putIfAbsent(definition.name(), definition) != null) throw new IOException("Multiple definitions of " + definition.name());
            }
        }
        return definitions;
    }

    private static Map<String, String> packages(DocumentSnapshot snapshot) throws IOException {
        var result = new TreeMap<String, String>();
        for (Path path : new TreeSet<>(snapshot.files().keySet())) {
            String text = source(snapshot, path);
            for (var t : MergeSyntax.tokens(text)) if (Set.of("usepackage", "RequirePackage").contains(t.name())) {
                int p = MergeSyntax.skip(text, t.end()); String options = "";
                if (p < text.length() && text.charAt(p) == '[') { var g = MergeSyntax.group(text,p,'[',']'); options = g.value(); p = g.end(); }
                for (String pkg : MergeSyntax.group(text,p,'{','}').value().split(",")) {
                    pkg = pkg.trim();
                    if (!pkg.matches("[A-Za-z0-9-]+")) throw new IOException("Dynamic/local package import unsupported.");
                    String previous = result.putIfAbsent(pkg, options);
                    if (previous != null && !previous.equals(options)) throw new IOException("Conflicting package options: " + pkg);
                    if (Files.exists(snapshot.root().resolve(pkg + ".sty"))) throw new IOException("Local package implementation requires manual setup: " + pkg);
                }
            }
        }
        return result;
    }

    private void gather(State state, Path path, String text, Set<Path> stack) throws IOException {
        if (!stack.add(path)) throw new IOException("Cyclic imported include.");
        if (!MergeSyntax.definitions(text).isEmpty()) throw new IOException("Definitions in selected body/includes must be moved to the donor preamble first.");
        for (var t : MergeSyntax.tokens(text)) {
            if (Set.of("maketitle", "documentclass", "addbibresource", "printbibliography", "nocite").contains(t.name())) throw new IOException("Select content without document-level command \\" + t.name());
            if (Set.of("input", "include").contains(t.name())) {
                var group = argument(text,t);
                Path dependency = literalDependency(group.value(), false);
                if (!donor.files().containsKey(dependency)) throw new IOException("Include is not in donor's verified closure: " + group.value());
                if (!state.contents.containsKey(dependency)) { String content = source(donor,dependency); state.contents.put(dependency,content); gather(state,dependency,content,stack); }
            }
            if (Set.of("bibliography", "bibliographystyle").contains(t.name())) {
                state.decisions.add("Donor " + t.name() + " declaration is replaced by the master bibliography setup");
            }
            if (t.name().equals("includegraphics")) {
                var g = argument(text,t); Path dependency = literalDependency(g.value(), true);
                byte[] bytes = Files.readAllBytes(donor.root().resolve(dependency));
                var identical = master.assets().stream().filter(a -> a.resolved() && a.hash().equals(TextEditPlan.sha256(bytes))).findFirst();
                if (identical.isPresent()) state.reusedAssets.put(dependency, identical.get().target());
                else state.assets.put(state.folder.resolve(dependency), bytes);
            }
        }
        stack.remove(path);
    }

    private Path literalDependency(String literal, boolean asset) throws IOException {
        var paths = asset ? donor.assets().stream().filter(a -> a.literal().equals(literal) && a.resolved()).map(DocumentSnapshot.AssetReference::target).distinct().toList()
                : donor.includeSites().stream().filter(a -> a.literal().equals(literal) && a.resolved()).map(DocumentSnapshot.IncludeSite::target).distinct().toList();
        if (paths.size() != 1) throw new IOException("Ambiguous/unresolved donor path: " + literal);
        return paths.getFirst();
    }

    private static Set<String> uses(String text) throws IOException {
        Set<String> result = new TreeSet<>();
        for (var t : MergeSyntax.tokens(text)) {
            if (Set.of("begin", "end").contains(t.name())) result.add("env:" + MergeSyntax.group(text,t.end(),'{','}').value());
            else if (t.name().matches("[A-Za-z]+")) result.add(t.name());
        }
        return result;
    }

    private void resolve(State state, String name, Set<String> active) throws IOException {
        if (state.resolved.contains(name)) return;
        if (!active.add(name)) throw new IOException("Cyclic macro dependency: " + name);
        var incoming = donorDefinitions.get(name); var existing = masterDefinitions.get(name);
        if (existing != null) {
            if (incoming != null && equivalent(name, new LinkedHashSet<>())) {
                state.decisions.add("Reuse equivalent master definition " + name);
            } else if (incoming != null) {
                // Even equal bodies can depend on conflicting donor helpers; ask for master adoption.
                String answer = state.resolver.choose("Macro " + name + "\nMASTER: " + existing.declaration() + "\nDONOR: " + incoming.declaration(),
                        incoming.compatible(existing) ? List.of("master", "rename", "map", "cancel") : List.of("rename", "map", "cancel"));
                if (answer.equals("master") && incoming.compatible(existing)) state.decisions.add(name + " adopts master definition and dependencies");
                else if (answer.equals("rename")) {
                    String fresh = freshMacro(state, name); state.mapping.put(name, fresh); importDefinition(state, name, incoming, active);
                } else if (answer.equals("map")) map(state,name,incoming);
                else throw new IOException("Macro conflict left unresolved: " + name);
            } else {
                String choice = state.resolver.choose("Donor has no definition for " + name
                        + "; its intended signature is unknown. Check every imported use against MASTER: "
                        + existing.declaration(), List.of("master", "cancel"));
                if (!choice.equals("master")) throw new IOException("Master-only definition left unresolved: " + name);
                state.decisions.add("Explicitly use master definition and signature for donor-undefined " + name);
            }
        } else if (incoming != null) {
            String choice = state.resolver.choose("Needed donor definition: " + incoming.declaration(), List.of("import", "map", "cancel"));
            if (choice.equals("import")) importDefinition(state,name,incoming,active);
            else if (choice.equals("map")) map(state,name,incoming);
            else throw new IOException("Missing required macro: " + name);
        } else if (PROVIDERS.containsKey(name)) requirePackage(state, PROVIDERS.get(name));
        else if (!BUILTINS.contains(name) && !(name.startsWith("env:") && ENVIRONMENTS.contains(name.substring(4)))) {
            String choice = state.resolver.choose("Unresolved command/environment " + name + " (may be package-provided)", List.of("define", "map", "package", "compiler", "cancel"));
            if (choice.equals("define")) {
                String declaration = state.resolver.definition(name);
                var parsed = MergeSyntax.definitions(declaration);
                if (parsed.size() != 1 || !parsed.getFirst().name().equals(name) || !declaration.substring(0, parsed.getFirst().start()).isBlank() || !declaration.substring(parsed.getFirst().end()).isBlank()) throw new IOException("Enter exactly one supported definition for " + name);
                importDefinition(state,name,parsed.getFirst(),active);
            } else if (choice.equals("map")) throw new IOException("Unknown source signature; enter its definition before mapping.");
            else if (choice.equals("package")) { String pkg = state.resolver.value("Package providing " + name); requirePackage(state,pkg); }
            else if (choice.equals("compiler")) state.decisions.add("Compiler must resolve " + name + " from the master environment");
            else throw new IOException("Unresolved macro: " + name);
        }
        active.remove(name); state.resolved.add(name);
    }

    private boolean equivalent(String name, Set<String> active) throws IOException {
        if (!active.add(name)) return false;
        var a = masterDefinitions.get(name);
        var b = donorDefinitions.get(name);
        if (a == null || b == null || !a.identical(b)) return false;
        Set<String> dependencies = uses(b.body() + "\n" + b.endBody());
        if (b.optional() != null) dependencies.addAll(uses(b.optional()));
        for (String dependency : dependencies) {
            if (donorDefinitions.containsKey(dependency) || masterDefinitions.containsKey(dependency)) {
                if (!equivalent(dependency, active)) return false;
            } else if (PROVIDERS.containsKey(dependency)) {
                String pkg = PROVIDERS.get(dependency);
                if (!masterPackages.containsKey(pkg) || !java.util.Objects.equals(masterPackages.get(pkg), donorPackages.get(pkg))) return false;
            } else if (!BUILTINS.contains(dependency) && !(dependency.startsWith("env:") && ENVIRONMENTS.contains(dependency.substring(4)))) return false;
        }
        active.remove(name);
        return true;
    }

    private void importDefinition(State state, String name, MergeSyntax.Definition definition, Set<String> active) throws IOException {
        Set<String> dependencies = uses(definition.body() + "\n" + definition.endBody());
        if (dependencies.stream().anyMatch(Set.of("label", "ref", "pageref", "eqref", "cite", "citep", "citet", "includegraphics", "input", "include")::contains)) {
            throw new IOException("Path/reference-bearing macro definitions need manual adaptation: " + name);
        }
        for (String dependency : dependencies) resolve(state,dependency,active);
        if (definition.optional() != null) for (String dependency : uses(definition.optional())) resolve(state,dependency,active);
        if (definition.declaration().startsWith("\\DeclareMathOperator")) requirePackage(state,"amsmath");
        state.imports.put(name,definition); state.decisions.add("Import definition " + name);
    }

    private void map(State state, String name, MergeSyntax.Definition incoming) throws IOException {
        String mapped = state.resolver.value("Existing compatible master macro name").replaceFirst("^\\\\", "");
        var definition = masterDefinitions.get(mapped);
        if (definition == null || !incoming.compatible(definition)) throw new IOException("Mapping target has an unknown/incompatible signature.");
        state.mapping.put(name,mapped); state.decisions.add(name + " maps to master " + mapped);
    }

    private String freshMacro(State state, String name) {
        String base = name.startsWith("env:") ? "env:donor" + name.substring(4) : "donor" + name;
        String result = base;
        while (masterDefinitions.containsKey(result) || donorDefinitions.containsKey(result) || state.mapping.containsValue(result) || BUILTINS.contains(result)) result += "x";
        return result;
    }

    private void requirePackage(State state, String pkg) throws IOException {
        if (!pkg.matches("[A-Za-z0-9-]+") || pkg.equals("biblatex")) throw new IOException("Unsupported package: " + pkg);
        if (masterPackages.containsKey(pkg)) {
            if (donorPackages.containsKey(pkg) && !masterPackages.get(pkg).equals(donorPackages.get(pkg))) throw new IOException("Package option conflict: " + pkg);
            return;
        }
        if (state.packages.contains(pkg)) return;
        if (!donorPackages.containsKey(pkg)) throw new IOException("Package not declared in donor; add the required declaration and retry: " + pkg);
        if (!state.resolver.choose("Required package " + pkg + " [" + donorPackages.get(pkg) + "]", List.of("import", "cancel")).equals("import")) throw new IOException("Required package excluded: " + pkg);
        state.packages.add(pkg); state.decisions.add("Import package " + pkg);
    }

    private void reconcileLabels(State state) throws IOException {
        Set<String> masterLabels = new TreeSet<>(), donorLabels = new TreeSet<>(), references = new TreeSet<>();
        for (var file : master.files().keySet()) collectArguments(source(master,file),Set.of("label"),masterLabels);
        for (String text : state.contents.values()) { collectArguments(text,Set.of("label"),donorLabels); collectArguments(text,Set.of("ref","pageref","eqref"),references); }
        Set<String> occupied = new TreeSet<>(masterLabels); occupied.addAll(donorLabels);
        for (String label : donorLabels) if (masterLabels.contains(label)) {
            String fresh = freshKey(label,occupied); occupied.add(fresh);
            if (!state.resolver.choose("Label collision " + label + " -> " + fresh,List.of("rename","cancel")).equals("rename")) throw new IOException("Unresolved label collision.");
            state.labels.put(label,fresh); state.decisions.add("Label " + label + " -> " + fresh);
        }
        for (String ref : references) if (!donorLabels.contains(ref)) {
            String mapped = state.resolver.value("Reference " + ref + " is outside the selection. Enter an existing master label");
            if (!masterLabels.contains(mapped)) throw new IOException("Unresolved reference: " + ref);
            state.labels.put(ref,mapped);
        }
    }

    private void reconcileBibliography(State state) throws IOException {
        Set<String> citations = new TreeSet<>();
        for (String text : state.contents.values()) collectArguments(text,Set.of("cite","citep","citet"),citations);
        if (citations.isEmpty()) return;
        var masterBib = bibliography(master); var donorBib = bibliography(donor);
        var accepted = new LinkedHashMap<String,MergeBibliography.Entry>();
        for (String key : citations) bibEntry(state,key,masterBib,donorBib,accepted,new LinkedHashSet<>());
        if (accepted.isEmpty()) return;
        if (commands(masterMain,"bibliography").size() != 1 || commands(masterMain,"bibliographystyle").size() != 1) throw new IOException("Set up one master BibTeX bibliography and style before importing citations.");
        var output = new StringBuilder();
        // Strings precede their uses; BibTeX crossref parents must follow child entries.
        var ordered = new ArrayList<>(accepted.values().stream().filter(e -> e.key().startsWith("string:")).toList());
        var entries = new ArrayList<>(accepted.values().stream().filter(e -> !e.key().startsWith("string:")).toList());
        java.util.Collections.reverse(entries);
        ordered.addAll(entries);
        for (var e : ordered) {
            String text = e.source();
            if (e.crossref() != null && state.citations.containsKey(e.crossref())) {
                text = text.substring(0,e.crossrefStart()) + "{" + state.citations.get(e.crossref()) + "}" + text.substring(e.crossrefEnd());
            }
            String mapped = state.citations.get(e.key());
            if (mapped != null) { int opening = text.indexOf('{') + 1; int comma = text.indexOf(',',opening); text = text.substring(0,opening) + mapped + text.substring(comma); }
            output.append(text).append('\n');
        }
        state.bibliography = output.toString(); state.decisions.add("Import bibliography entries " + accepted.keySet());
    }

    private void bibEntry(State state, String key, Map<String,MergeBibliography.Entry> existing, Map<String,MergeBibliography.Entry> incoming,
            Map<String,MergeBibliography.Entry> accepted, Set<String> active) throws IOException {
        if (accepted.containsKey(key)) return;
        if (!active.add(key)) throw new IOException("Cyclic bibliography dependency: " + key);
        var a = existing.get(key); var b = incoming.get(key);
        if (b == null) { if (a == null) throw new IOException("Undefined citation/string: " + key); active.remove(key); return; }
        if (a != null && equivalentEntry(key,existing,incoming,new LinkedHashSet<>())) { active.remove(key); return; }
        if (a != null) {
            if (key.startsWith("string:")) throw new IOException("Conflicting BibTeX string: " + key);
            String fresh = freshKey(key, union(existing.keySet(),incoming.keySet(),new TreeSet<>(state.citations.values())));
            if (!state.resolver.choose("Bibliography key collision " + key + " -> " + fresh,List.of("rename","cancel")).equals("rename")) throw new IOException("Unresolved citation conflict.");
            state.citations.put(key,fresh);
        }
        for (String dependency : new TreeSet<>(b.dependencies())) bibEntry(state,dependency,existing,incoming,accepted,active);
        active.remove(key); accepted.put(key,b);
    }

    private static boolean equivalentEntry(String key, Map<String,MergeBibliography.Entry> existing,
            Map<String,MergeBibliography.Entry> incoming, Set<String> active) {
        if (!active.add(key)) return false;
        var a = existing.get(key); var b = incoming.get(key);
        if (a == null || b == null || !a.source().equals(b.source())) return false;
        for (String dependency : b.dependencies()) {
            if (!equivalentEntry(dependency,existing,incoming,active)) return false;
        }
        active.remove(key);
        return true;
    }

    @SafeVarargs private static Set<String> union(Set<String>... sets) { Set<String> result = new TreeSet<>(); for (var set : sets) result.addAll(set); return result; }
    private static String freshKey(String key, Set<String> occupied) { String result = "donor:" + key; while (occupied.contains(result)) result = "donor:" + result; return result; }

    private static Map<String,MergeBibliography.Entry> bibliography(DocumentSnapshot snapshot) throws IOException {
        var result = new LinkedHashMap<String,MergeBibliography.Entry>();
        for (Path path : snapshot.assets().stream().filter(a -> a.command().equals("bibliography")).map(DocumentSnapshot.AssetReference::target).distinct().sorted().toList()) {
            for (var entry : MergeBibliography.parse(Files.readString(snapshot.root().resolve(path))).entrySet()) {
                if (result.putIfAbsent(entry.getKey(),entry.getValue()) != null) throw new IOException("Repeated bibliography key: " + entry.getKey());
            }
        }
        return result;
    }

    private String transform(State state, Path path, String text) throws IOException {
        var replacements = new ArrayList<Replacement>();
        for (var token : MergeSyntax.tokens(text)) {
            if (Set.of("input","include","includegraphics").contains(token.name())) {
                var group = argument(text,token); Path dependency = literalDependency(group.value(),token.name().equals("includegraphics"));
                String mapped = (token.name().equals("includegraphics") && state.reusedAssets.containsKey(dependency) ? state.reusedAssets.get(dependency) : state.folder.resolve(dependency)).toString().replace('\\','/');
                if (token.name().equals("include") && mapped.endsWith(".tex")) mapped = mapped.substring(0,mapped.length()-4);
                replacements.add(new Replacement(group.start(),group.end()-1,mapped));
            } else if (Set.of("bibliography", "bibliographystyle").contains(token.name())) {
                var group = argument(text, token);
                replacements.add(new Replacement(token.start(), group.end(), ""));
            } else if (Set.of("label","ref","pageref","eqref","cite","citep","citet").contains(token.name())) {
                var group = argument(text,token); var mapping = token.name().startsWith("cite") ? state.citations : state.labels;
                String mapped = Arrays.stream(group.value().split(",")).map(String::trim).map(v -> mapping.getOrDefault(v,v)).collect(java.util.stream.Collectors.joining(","));
                if (!mapped.equals(group.value())) replacements.add(new Replacement(group.start(),group.end()-1,mapped));
            }
        }
        var output = new StringBuilder(text);
        replacements.sort(java.util.Comparator.comparingInt(Replacement::start).reversed());
        for (var change : replacements) output.replace(change.start(),change.end(),change.text());
        return MergeSyntax.rewrite(output.toString(),state.mapping);
    }

    private static void collectArguments(String text, Set<String> names, Set<String> output) throws IOException {
        for (var token : MergeSyntax.tokens(text)) if (names.contains(token.name())) {
            for (String argument : argument(text,token).value().split(",")) {
                String key = argument.trim();
                if (!key.matches("[A-Za-z0-9:_.-]+")) throw new IOException("Dynamic reference/key unsupported: " + key);
                if (token.name().equals("label") && !output.add(key)) throw new IOException("Duplicate label: " + key);
                output.add(key);
            }
        }
    }

    static MergeSyntax.Group argument(String text, MergeSyntax.Token token) throws IOException {
        int p = MergeSyntax.skip(text,token.end());
        if (p < text.length() && text.charAt(p) == '*') p = MergeSyntax.skip(text,p+1);
        while (p < text.length() && text.charAt(p) == '[') p = MergeSyntax.skip(text,MergeSyntax.group(text,p,'[',']').end());
        return MergeSyntax.group(text,p,'{','}');
    }

    private static List<MergeSyntax.Token> commands(String text, String name) throws IOException { return MergeSyntax.tokens(text).stream().filter(t -> t.name().equals(name)).toList(); }

    private static final class State {
        final Resolver resolver;
        final Path folder;
        final Map<Path,String> contents = new LinkedHashMap<>();
        final Map<Path,byte[]> assets = new TreeMap<>();
        final Map<Path,Path> reusedAssets = new TreeMap<>();
        final Map<String,MergeSyntax.Definition> imports = new LinkedHashMap<>();
        final Map<String,String> mapping = new TreeMap<>(), labels = new TreeMap<>(), citations = new TreeMap<>();
        final Set<String> resolved = new TreeSet<>(), packages = new LinkedHashSet<>();
        final List<String> decisions = new ArrayList<>();
        String bibliography;
        State(Resolver resolver, Path folder) { this.resolver = resolver; this.folder = folder; }
    }

    record Range(int start, int end, String label) { }
    private record Replacement(int start, int end, String text) { }
    record Proposal(DocumentSnapshot master, List<TextEditPlan.Edit> edits, Map<Path,byte[]> updates,
            Map<Path,byte[]> creates, List<String> decisions, TexCompileGate.Freshness freshness) { }
    interface Resolver {
        String choose(String question, List<String> choices) throws IOException;
        String value(String prompt) throws IOException;
        String definition(String name) throws IOException;
    }
}
