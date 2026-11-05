package texsuite;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class MergePropertiesTest {
    @Property(tries=120)
    void importedDefinitionsMatchIndependentReachabilityOracle(@ForAll int seed) throws Exception {
        Random random=new Random(seed); int size=6;
        var graph=new ArrayList<List<Integer>>();
        for(int i=0;i<size;i++) { var edges=new ArrayList<Integer>(); for(int j=0;j<i;j++) if(random.nextBoolean()) edges.add(j); graph.add(edges); }
        var expected=new HashSet<Integer>(); var queue=new ArrayDeque<Integer>(); queue.add(size-1);
        while(!queue.isEmpty()) { int node=queue.remove(); if(expected.add(node)) queue.addAll(graph.get(node)); }
        StringBuilder definitions=new StringBuilder();
        for(int i=0;i<size;i++) { definitions.append("\\newcommand{\\node").append((char)('A'+i)).append("}{"); for(int j:graph.get(i)) definitions.append("\\node").append((char)('A'+j)).append(' '); definitions.append(i).append("}\n"); }
        Path root=Files.createTempDirectory("merge-property-");
        try {
            var planner=planner(root,MergePlannerTest.doc("","Unchanged master."),MergePlannerTest.doc(definitions.toString(),"$\\nodeF$"));
            var proposal=MergePlannerTest.prepare(planner,MergePlannerTest.DEFAULTS);
            String text=MergePlannerTest.result(proposal);
            for(int i=0;i<size;i++) assertEquals(expected.contains(i),text.contains("\\newcommand{\\node"+(char)('A'+i)+"}"));
            assertTrue(text.contains("Unchanged master."));
            assertEquals(MergePlannerTest.doc(definitions.toString(),"$\\nodeF$"),Files.readString(root.resolve("donor/main.tex")));
        } finally { delete(root); }
    }

    @Property(tries=100)
    void tokenMappingPreservesProtectedBytesAndLongerNames(@ForAll @IntRange(min=1,max=500) int number) throws Exception {
        String original="\\value + \\valueLong % \\value "+number+"\n\\verb|\\value|\n";
        assertEquals("\\donorvalue + \\valueLong % \\value "+number+"\n\\verb|\\value|\n",MergeSyntax.rewrite(original,java.util.Map.of("value","donorvalue")));
    }

    @Property(tries=30)
    void mergedInsertionsUndoToExactOriginalBytes(@ForAll @IntRange(min=0,max=10000) int number) throws Exception {
        Path root=Files.createTempDirectory("merge-undo-property-");
        try {
            String original=MergePlannerTest.doc("","Master Ω "+number+".");
            var planner=planner(root,original,MergePlannerTest.doc("","Donor é "+number+"."));
            var proposal=MergePlannerTest.prepare(planner,MergePlannerTest.DEFAULTS);
            var compiler=new TexCompileGate(root.resolve("pdflatex"),false,null,(command,working,timeout)-> {
                Files.writeString(working.resolve("texsuite-validation-work/texsuite-validation.pdf"),"PDF"); return 0;
            });
            Path backup=MergeTransaction.apply(proposal,compiler);
            TextRecovery.revert(proposal.master().root(),backup);
            assertArrayEquals(original.getBytes(java.nio.charset.StandardCharsets.UTF_8),Files.readAllBytes(root.resolve("master/main.tex")));
        } finally { delete(root); }
    }

    private static MergePlanner planner(Path root,String master,String donor) throws Exception {
        Files.createDirectories(root.resolve("master")); Files.createDirectories(root.resolve("donor"));
        Files.writeString(root.resolve("master/main.tex"),master); Files.writeString(root.resolve("donor/main.tex"),donor);
        return new MergePlanner(new DocumentLoader().load(root.resolve("master/main.tex").toRealPath()),Path.of("main.tex"),new DocumentLoader().load(root.resolve("donor/main.tex").toRealPath()));
    }
    private static void delete(Path root) throws Exception { try(var paths=Files.walk(root)) { for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
}
