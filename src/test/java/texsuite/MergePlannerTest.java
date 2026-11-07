package texsuite;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergePlannerTest {
    @TempDir Path directory;

    static final MergePlanner.Resolver DEFAULTS = new MergePlanner.Resolver() {
        public String choose(String question,List<String> choices) { return choices.getFirst(); }
        public String value(String question) { return ""; }
        public String definition(String name) { return "\\newcommand{\\"+name+"}{42}"; }
    };

    Path file(String name,String text) throws Exception {
        Path path=directory.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path,text); return path.toRealPath();
    }
    static String doc(String preamble,String body) { return "\\documentclass{article}\n"+preamble+"\n\\begin{document}\n"+body+"\n\\end{document}\n"; }
    MergePlanner planner(String master,String donor) throws Exception {
        Path a=file("master/main.tex",master),b=file("donor/main.tex",donor);
        return new MergePlanner(new DocumentLoader().load(a),Path.of("main.tex"),new DocumentLoader().load(b));
    }
    static MergePlanner.Proposal prepare(MergePlanner planner,MergePlanner.Resolver resolver) throws Exception {
        return planner.prepare(MergePlanner.body(planner.donorText()),MergePlanner.body(planner.targetText()).end(),resolver);
    }
    static String result(MergePlanner.Proposal proposal) { return new String(proposal.updates().get(Path.of("main.tex")),java.nio.charset.StandardCharsets.UTF_8); }

    @Test void importsOnlyReachableDefinitionsAndKeepsDonorBytes() throws Exception {
        var planner=planner(doc("","Master."),doc("\\newcommand{\\samples}{\\countValue}\n\\newcommand{\\countValue}{42}\n\\newcommand{\\unused}{99}","Donor has $\\samples$ samples."));
        byte[] donor=Files.readAllBytes(directory.resolve("donor/main.tex"));
        var p=prepare(planner,DEFAULTS); String result=result(p);
        assertTrue(result.contains("\\newcommand{\\countValue}{42}"));
        assertTrue(result.contains("\\newcommand{\\samples}{\\countValue}"));
        assertTrue(result.indexOf("\\newcommand{\\countValue}")<result.indexOf("\\newcommand{\\samples}"));
        assertFalse(result.contains("unused")); assertTrue(result.contains("Master."));
        assertArrayEquals(donor,Files.readAllBytes(directory.resolve("donor/main.tex")));
    }

    @Test void acceptedMasterMeaningPrunesDonorDependencies() throws Exception {
        var planner=planner(doc("\\newcommand{\\samples}{10}","Master $\\samples$."),doc("\\newcommand{\\samples}{\\helper}\n\\newcommand{\\helper}{99}","Donor $\\samples$."));
        String result=result(prepare(planner,DEFAULTS));
        assertFalse(result.contains("helper")); assertTrue(result.contains("{10}")); assertFalse(result.contains("{99}"));
    }

    @Test void incompatibleSignatureCannotAdoptMaster() throws Exception {
        var planner=planner(doc("\\newcommand{\\samples}[1]{#1}","Master."),doc("\\newcommand{\\samples}{99}","Donor $\\samples$."));
        var resolver=new MergePlanner.Resolver() {
            public String choose(String q,List<String> c) { return "master"; }
            public String value(String q) { return ""; }
            public String definition(String n) { return ""; }
        };
        assertThrows(IOException.class,()->prepare(planner,resolver));
        String renamed=result(prepare(planner,DEFAULTS));
        assertTrue(renamed.contains("\\newcommand{\\donorsamples}{99}")); assertTrue(renamed.contains("$\\donorsamples$"));
    }

    @Test void undefinedCommandCanReceiveExplicitDefinition() throws Exception {
        var planner=planner(doc("","Master."),doc("","Donor $\\samples$."));
        assertTrue(result(prepare(planner,DEFAULTS)).contains("\\newcommand{\\samples}{42}"));
    }

    @Test void renamingProtectsCommentAndVerbatim() throws Exception {
        String source="\\samples % \\samples\n\\verb|\\samples|\n\\begin{verbatim}\n\\samples\n\\end{verbatim}";
        String rewritten=MergeSyntax.rewrite(source,java.util.Map.of("samples","donorsamples"));
        assertEquals(source.replaceFirst("\\\\samples","\\\\donorsamples"),rewritten);
    }

    @Test void importsNestedIncludeAndAssetWithRewrittenPaths() throws Exception {
        file("donor/parts/body.tex","Figure: \\includegraphics{picture.pdf}\n");
        file("donor/picture.pdf","test-image-bytes");
        var planner=planner(doc("\\usepackage{graphicx}","Master."),doc("\\usepackage{graphicx}","\\input{parts/body}"));
        var p=prepare(planner,DEFAULTS);
        assertEquals(2,p.creates().size());
        assertTrue(result(p).contains("texsuite-imports/"));
        assertTrue(p.creates().keySet().stream().anyMatch(path->path.endsWith("parts/body.tex")));
        assertTrue(new String(p.creates().entrySet().stream().filter(e->e.getKey().toString().endsWith(".tex")).findFirst().orElseThrow().getValue()).contains("texsuite-imports/"));
    }

    @Test void labelCollisionRewritesOnlyImportedUses() throws Exception {
        var planner=planner(doc("","\\section{Master}\\label{same}\nSee \\ref{same}."),doc("","\\section{Donor}\\label{same}\nSee \\ref{same}."));
        String result=result(prepare(planner,DEFAULTS));
        assertTrue(result.contains("\\label{donor:same}")); assertTrue(result.contains("\\ref{donor:same}")); assertTrue(result.contains("\\label{same}"));
    }

    @Test void cyclesAndDynamicDefinitionsBlock() throws Exception {
        var planner=planner(doc("","Master."),doc("\\newcommand{\\aaa}{\\bbb}\n\\newcommand{\\bbb}{\\aaa}","$\\aaa$"));
        assertThrows(IOException.class,()->prepare(planner,DEFAULTS));
        assertThrows(IOException.class,()->MergeSyntax.tokens("\\csname foo\\endcsname"));
    }

    @Test void staleDonorInvalidatesProposal() throws Exception {
        var planner=planner(doc("","Master."),doc("","Donor."));
        var p=prepare(planner,DEFAULTS); Files.writeString(directory.resolve("donor/main.tex"),doc("","Changed."));
        assertThrows(IOException.class,()->p.freshness().check());
    }

    @Test void definitionsParseSignaturesAndEnvironment() throws Exception {
        var defs=MergeSyntax.definitions("\\newcommand*{\\foo}[2][x]{#1+#2}\n\\def\\bar#1#2{#1#2}\n\\DeclareMathOperator{\\rank}{rank}\n\\newenvironment{boxed}{\\begin{quote}}{\\end{quote}}");
        assertEquals(4,defs.size()); assertEquals(2,defs.getFirst().arguments()); assertEquals("x",defs.getFirst().optional()); assertTrue(defs.getFirst().star()); assertEquals("env:boxed",defs.getLast().name());
    }

    @Test void bibParserFollowsStringsAndCrossrefs() throws Exception {
        var entries=MergeBibliography.parse("@string{venue={Conference}}\n@proceedings{parent,title=venue,year=2020}\n@inproceedings{child,title={Paper},crossref={parent}}");
        assertEquals(java.util.Set.of("parent"),entries.get("child").dependencies());
        assertEquals(java.util.Set.of("string:venue"),entries.get("parent").dependencies());
        assertThrows(IOException.class,()->MergeBibliography.parse("@preamble{danger}"));
    }
    @Test void identicalBodiesWithDifferentHelpersStillRequireExplicitChoice() throws Exception {
        var planner=planner(doc("\\newcommand{\\aaa}{\\bbb}\n\\newcommand{\\bbb}{10}","Master."),doc("\\newcommand{\\aaa}{\\bbb}\n\\newcommand{\\bbb}{20}","$\\aaa$"));
        var prompts=new java.util.ArrayList<String>();
        var resolver=new MergePlanner.Resolver() {
            public String choose(String q,List<String> c) { prompts.add(q); return "cancel"; }
            public String value(String q) { return ""; }
            public String definition(String n) { return ""; }
        };
        assertThrows(IOException.class,()->prepare(planner,resolver)); assertEquals(1,prompts.size());
    }

    @Test void equivalentDefinitionsReuseWithoutAQuestion() throws Exception {
        var planner=planner(doc("\\newcommand{\\aaa}{42}","Master."),doc("\\newcommand{\\aaa}{42}","$\\aaa$"));
        var resolver=new MergePlanner.Resolver() {
            public String choose(String q,List<String> c) { fail("Equivalent definition should not prompt: " + q); return "cancel"; }
            public String value(String q) { return ""; }
            public String definition(String n) { return ""; }
        };
        String result=result(prepare(planner,resolver)); assertEquals(1,result.split("newcommand",-1).length-1);
    }

    @Test void sectionSelectionDoesNotImportUnselectedDependencies() throws Exception {
        var planner=planner(doc("","Master."),doc("\\newcommand{\\used}{1}\n\\newcommand{\\unused}{2}","\\section{First}\n$\\used$\n\\section{Second}\n$\\unused$"));
        var sections=MergePlanner.sections(planner.donorText());
        var p=planner.prepare(sections.getFirst(),MergePlanner.body(planner.targetText()).end(),DEFAULTS);
        assertFalse(result(p).contains("unused")); assertFalse(result(p).contains("Second"));
    }

    @Test void packageProviderAndOptionsAreExplicit() throws Exception {
        var planner=planner(doc("","Master."),doc("\\usepackage{amsmath}","\\begin{align}a&=b\\end{align}"));
        assertTrue(result(prepare(planner,DEFAULTS)).contains("\\usepackage{amsmath}"));
        file("donor/picture.pdf","image");
        var conflict=planner(doc("\\usepackage[demo]{graphicx}","Master."),doc("\\usepackage[draft]{graphicx}","\\includegraphics{picture.pdf}"));
        assertThrows(IOException.class,()->prepare(conflict,DEFAULTS));
    }

    @Test void optionalSignatureAndDelimitedDefAreProtected() throws Exception {
        assertThrows(IOException.class,()->MergeSyntax.definitions("\\def\\aaa#1 #2{#1#2}"));
        assertThrows(IOException.class,()->MergeSyntax.definitions("{\\newcommand{\\aaa}{1}}"));
        var a=MergeSyntax.definitions("\\newcommand{\\aaa}[1][x]{#1}").getFirst();
        var b=MergeSyntax.definitions("\\newcommand{\\aaa}[1][y]{#1}").getFirst();
        assertFalse(a.compatible(b));
        assertEquals("\\newenvironment{donorbox}{A}{B}\\begin{donorbox}x\\end{donorbox}",MergeSyntax.rewrite("\\newenvironment{box}{A}{B}\\begin{box}x\\end{box}",java.util.Map.of("env:box","env:donorbox")));
    }

    @Test void donorUndefinedMasterMacroRequiresExplicitSignatureAcceptance() throws Exception {
        var planner=planner(doc("\\newcommand{\\samples}[1]{#1}","Master."),doc("","$\\samples{10}$"));
        var questions=new java.util.ArrayList<String>();
        var refusing=new MergePlanner.Resolver() {
            public String choose(String q,List<String> c) { questions.add(q); return "cancel"; }
            public String value(String q) { return ""; }
            public String definition(String n) { return ""; }
        };
        assertThrows(IOException.class,()->prepare(planner,refusing));
        assertEquals(1,questions.size());
        assertTrue(questions.getFirst().contains("intended signature is unknown"));
        var accepted=prepare(planner,DEFAULTS);
        assertTrue(accepted.decisions().stream().anyMatch(s->s.contains("donor-undefined samples")));
        assertEquals(1,result(accepted).split("newcommand",-1).length-1);
    }

    @Test void staleSnapshotAndChangeDuringResolutionNeverProduceProposal() throws Exception {
        var planner=planner(doc("","Master."),doc("\\newcommand{\\samples}{10}","$\\samples$"));
        var donor=new DocumentLoader().load(directory.resolve("donor/main.tex").toRealPath());
        var changing=new MergePlanner.Resolver() {
            public String choose(String q,List<String> c) throws IOException {
                Files.writeString(directory.resolve("donor/main.tex"),doc("","Changed.")); return c.getFirst();
            }
            public String value(String q) { return ""; }
            public String definition(String n) { return ""; }
        };
        assertThrows(StaleSourceException.class,()->prepare(planner,changing));
        assertThrows(StaleSourceException.class,()->new MergePlanner(planner.master(),Path.of("main.tex"),donor));
        assertThrows(StaleSourceException.class,()->prepare(planner,DEFAULTS));
    }

    @Test void missingReferencesCitationsAndBodyDefinitionsBlockBeforeCompile() throws Exception {
        for (String body:List.of("See \\ref{missing}.","See \\cite{missing}.","\\newcommand{\\local}{1} $\\local$")) {
            var planner=planner(doc("","Master."),doc("",body));
            assertThrows(IOException.class,()->prepare(planner,DEFAULTS),body);
        }
    }

    @Test void explicitOutsideSelectionReferenceMapsOnlyImportedReference() throws Exception {
        var planner=planner(doc("","\\section{Master}\\label{destination}\\ref{destination}"),doc("","See \\ref{external}."));
        var mapping=new MergePlanner.Resolver() {
            public String choose(String q,List<String> c) { return c.getFirst(); }
            public String value(String q) { return "destination"; }
            public String definition(String n) { return ""; }
        };
        String merged=result(prepare(planner,mapping));
        assertFalse(merged.contains("external"));
        assertEquals(2,merged.split("\\\\ref\\{destination\\}",-1).length-1);
    }

    @Test void lineSelectionRespectsMixedTerminatorsAndBalancedBoundaries() throws Exception {
        String lines="one\r\ntwó\rthree\nfour";
        var range=MergePlanner.lines(lines,2,3);
        assertEquals("twó\rthree\n",lines.substring(range.start(),range.end()));
        range=MergePlanner.lines(lines,4,4);
        assertEquals("four",lines.substring(range.start(),range.end()));
        assertEquals(0,MergePlanner.lines(lines,1,1).start());
        for (int[] invalid:List.of(new int[]{0,1},new int[]{2,1},new int[]{5,5},new int[]{1,5})) {
            assertThrows(IOException.class,()->MergePlanner.lines(lines,invalid[0],invalid[1]));
        }
        var planner=planner(doc("","\\section{Master}Master."),doc("","Selected.\nExcluded."));
        var selected=MergePlanner.lines(planner.donorText(),4,4);
        var p=planner.prepare(selected,MergePlanner.sections(planner.targetText()).getFirst().start(),DEFAULTS);
        assertTrue(result(p).indexOf("Selected.")<result(p).indexOf("\\section{Master}"));
        assertFalse(result(p).contains("Excluded."));
        var grouped=planner(doc("","Master."),doc("","{Group\ninside}\n"));
        assertThrows(IOException.class,()->grouped.prepare(MergePlanner.lines(grouped.donorText(),4,4),MergePlanner.body(grouped.targetText()).end(),DEFAULTS));
    }

    @Test void identicalBibEntryWithDifferentStringDoesNotSilentlyReuseMasterMeaning() throws Exception {
        String entry="@article{same,title=venue}";
        file("master/references.bib","@string{venue={Master}}\n"+entry);
        file("donor/references.bib","@string{venue={Donor}}\n"+entry);
        var planner=planner(doc("","\\bibliographystyle{plain}\\bibliography{references}"),doc("","\\cite{same}\\bibliography{references}"));
        IOException failure=assertThrows(IOException.class,()->prepare(planner,DEFAULTS));
        assertTrue(failure.getMessage().contains("Conflicting BibTeX string"));
    }

    @Test void renamesOnlyParsedCrossrefFieldAndKeepsTitleText() throws Exception {
        file("master/references.bib","@book{parent,title={Master}}");
        file("donor/references.bib","@book{parent,title={Donor}}\n@inbook{child,title={crossref={parent}},crossref={par} # {ent}}");
        var planner=planner(doc("","\\bibliographystyle{plain}\\bibliography{references}"),doc("","\\cite{child}\\bibliography{references}"));
        var p=prepare(planner,DEFAULTS);
        String bib=new String(p.creates().values().iterator().next());
        assertTrue(bib.contains("title={crossref={parent}}"));
        assertTrue(bib.contains(",crossref={donor:parent}"));
        assertFalse(bib.contains("{par} # {ent}"));
    }

    @Test
    void equalTextFromDifferentDeclarationKindsStillNeedsExplicitMeaningChoice() throws Exception {
        var planner = planner(doc("\\newcommand{\\rank}{rank}", "Master $\\rank A$."),
                doc("\\usepackage{amsmath}\n\\DeclareMathOperator{\\rank}{rank}", "Donor $\\rank A$."));
        var questions = new java.util.ArrayList<String>();
        var refusing = new MergePlanner.Resolver() {
            public String choose(String question, List<String> choices) {
                questions.add(question);
                return "cancel";
            }
            public String value(String question) { return ""; }
            public String definition(String name) { return ""; }
        };

        assertThrows(IOException.class, () -> prepare(planner, refusing));
        assertEquals(1, questions.size());
        assertTrue(questions.getFirst().contains("Macro rank"));
    }

    @Test
    void optionalDefaultsWithReferencesCannotBypassManualAdaptation() throws Exception {
        var planner = planner(doc("", "Master\\label{shared}."),
                doc("\\newcommand{\\result}[1][\\ref{shared}]{#1}",
                        "Donor\\label{shared}: \\result."));
        byte[] donorBefore = Files.readAllBytes(directory.resolve("donor/main.tex"));

        IOException failure = assertThrows(IOException.class, () -> prepare(planner, DEFAULTS));
        assertTrue(failure.getMessage().contains("manual adaptation"));
        assertArrayEquals(donorBefore, Files.readAllBytes(directory.resolve("donor/main.tex")));
    }

    @Test
    void starredEnvironmentImportsAndRenamesItsDeclarationAndUses() throws Exception {
        var planner = planner(doc("\\newenvironment*{example}{Master.}{End.}", "Master."),
                doc("\\newenvironment*{example}[1][Default.]{#1}{Done.}",
                        "\\begin{example}Donor.\\end{example}"));
        String merged = result(prepare(planner, DEFAULTS));

        assertTrue(merged.contains("\\newenvironment*{donorexample}[1][Default.]{#1}{Done.}"));
        assertTrue(merged.contains("\\begin{donorexample}Donor.\\end{donorexample}"));
        assertTrue(merged.contains("\\newenvironment*{example}{Master.}{End.}"));
    }

}
