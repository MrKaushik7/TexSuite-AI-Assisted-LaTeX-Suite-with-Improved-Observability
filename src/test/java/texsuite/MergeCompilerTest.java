package texsuite;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergeCompilerTest {
    @TempDir Path directory;

    Path source(String name,String text) throws Exception {
        Path path=directory.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path,text); return path.toRealPath();
    }
    MergePlanner planner(String master,String donor) throws Exception {
        Path a=source("master/main.tex",master),b=source("donor/main.tex",donor);
        return new MergePlanner(new DocumentLoader().load(a),Path.of("main.tex"),new DocumentLoader().load(b));
    }
    TexCompileGate realGate() {
        var gate=new TexCompileGate(false,null); assumeTrue(gate.hasCompiler(),"Real TeX smoke requires pdflatex"); return gate;
    }

    @Test void realCompilerValidatesMacroImportsAndExactUndo() throws Exception {
        var gate=realGate();
        var planner=planner(MergePlannerTest.doc("","Master."),MergePlannerTest.doc("\\newcommand{\\sampleCount}{42}","Donor has $\\sampleCount$ samples."));
        var p=MergePlannerTest.prepare(planner,MergePlannerTest.DEFAULTS);
        byte[] before=Files.readAllBytes(p.master().root().resolve("main.tex"));
        Path backup=MergeTransaction.apply(p,gate);
        assertTrue(Files.readString(p.master().root().resolve("main.tex")).contains("\\newcommand{\\sampleCount}{42}"));
        TextRecovery.revert(p.master().root(),backup); assertArrayEquals(before,Files.readAllBytes(p.master().root().resolve("main.tex")));
        assertFalse(Files.exists(p.master().root().resolve("main.pdf")));
    }

    @Test void realBibtexImportsOnlyCitedEntriesAndRenamesCollision() throws Exception {
        var gate=realGate(); assumeTrue(Files.isExecutable(Path.of("/Library/TeX/texbin/bibtex")));
        source("master/references.bib","@article{shared,author={Master, A.},title={Master paper},journal={Journal},year={2020}}\n");
        source("donor/references.bib","@article{shared,author={Donor, B.},title={Donor paper},journal={Journal},year={2021}}\n@article{unused,title={Unused},year={2022}}\n");
        var planner=planner(MergePlannerTest.doc("","Master cites \\cite{shared}.\n\\bibliographystyle{plain}\n\\bibliography{references}"),
                MergePlannerTest.doc("","Donor cites \\cite{shared}.\n\\bibliographystyle{plain}\n\\bibliography{references}"));
        var p=MergePlannerTest.prepare(planner,MergePlannerTest.DEFAULTS);
        assertEquals(1,p.creates().size());
        String imported=new String(p.creates().values().iterator().next()); assertTrue(imported.contains("donor:shared")); assertFalse(imported.contains("Unused"));
        Path backup=MergeTransaction.apply(p,gate);
        assertTrue(Files.readString(p.master().root().resolve("main.tex")).contains("\\cite{donor:shared}"));
        TextRecovery.revert(p.master().root(),backup);
    }

    @Test void unresolvedCommandAtCompilerCannotWrite() throws Exception {
        var gate=realGate();
        var planner=planner(MergePlannerTest.doc("","Master."),MergePlannerTest.doc("","\\neverDefined"));
        var resolver=new MergePlanner.Resolver() {
            public String choose(String q,List<String> c) { return "compiler"; }
            public String value(String q) { return ""; }
            public String definition(String n) { return ""; }
        };
        var p=MergePlannerTest.prepare(planner,resolver);
        assertThrows(IOException.class,()->MergeTransaction.apply(p,gate));
        assertEquals(MergePlannerTest.doc("","Master."),Files.readString(p.master().root().resolve("main.tex")));
    }

    @Test void realBibtexRenamesEqualChildWithConflictingParentAndOrdersDependencies() throws Exception {
        var gate=realGate(); assumeTrue(Files.isExecutable(Path.of("/Library/TeX/texbin/bibtex")));
        String child="@inproceedings{child,author={Writer, A.},title={Child},crossref={parent}}\n";
        String parent="@proceedings{parent,editor={Editor, E.},title={Proceedings},booktitle={Proceedings},year={2020}}\n";
        source("master/references.bib",child+parent);
        source("donor/references.bib",child+parent.replace("2020","2021"));
        var planner=planner(MergePlannerTest.doc("","Master \\cite{child}.\\bibliographystyle{plain}\\bibliography{references}"),
                MergePlannerTest.doc("","Donor \\cite{child}.\\bibliography{references}"));
        var p=MergePlannerTest.prepare(planner,MergePlannerTest.DEFAULTS);
        String imported=new String(p.creates().values().iterator().next());
        assertTrue(imported.contains("@inproceedings{donor:child"));
        assertTrue(imported.contains("crossref={donor:parent}"));
        assertTrue(imported.indexOf("@inproceedings")<imported.indexOf("@proceedings"));
        Path backup=MergeTransaction.apply(p,gate);
        TextRecovery.revert(p.master().root(),backup);
        assertFalse(Files.exists(p.master().root().resolve(p.creates().keySet().iterator().next())));
    }
}
