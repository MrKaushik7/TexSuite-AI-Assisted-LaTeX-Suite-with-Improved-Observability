package texsuite;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergeTransactionTest {
    @TempDir Path directory;

    MergePlanner.Proposal proposal(boolean include) throws Exception {
        Path master=directory.resolve("master"),donor=directory.resolve("donor"); Files.createDirectories(master); Files.createDirectories(donor);
        Files.writeString(master.resolve("main.tex"),MergePlannerTest.doc("","Master."));
        Files.writeString(donor.resolve("main.tex"),MergePlannerTest.doc("",include ? "\\input{part}" : "Donor."));
        if(include) Files.writeString(donor.resolve("part.tex"),"Imported content.\n");
        var planner=new MergePlanner(new DocumentLoader().load(master.resolve("main.tex").toRealPath()),Path.of("main.tex"),new DocumentLoader().load(donor.resolve("main.tex").toRealPath()));
        return MergePlannerTest.prepare(planner,MergePlannerTest.DEFAULTS);
    }

    TexCompileGate gate(int status) throws Exception {
        Path binary=directory.resolve("pdflatex"); Files.writeString(binary,"");
        return new TexCompileGate(binary,false,null,(command,working,timeout)-> {
            Files.writeString(working.resolve("texsuite-validation-work/texsuite-validation.pdf"),"fake PDF"); return status;
        });
    }

    @Test void applyHistoryAndExactUndoWithCreatedFiles() throws Exception {
        var p=proposal(true); Path root=p.master().root(); byte[] original=Files.readAllBytes(root.resolve("main.tex"));
        byte[] donor=Files.readAllBytes(directory.resolve("donor/main.tex"));
        Path recovery=MergeTransaction.apply(p,gate(0));
        assertEquals(3,EditHistory.list(root).getFirst().record().version());
        for(var e:p.creates().entrySet()) assertArrayEquals(e.getValue(),Files.readAllBytes(root.resolve(e.getKey())));
        assertArrayEquals(p.updates().get(Path.of("main.tex")),Files.readAllBytes(root.resolve("main.tex")));
        TextRecovery.revert(root,recovery);
        assertArrayEquals(original,Files.readAllBytes(root.resolve("main.tex")));
        assertArrayEquals(donor,Files.readAllBytes(directory.resolve("donor/main.tex")));
        for(var path:p.creates().keySet()) assertFalse(Files.exists(root.resolve(path)));
        assertEquals("restored",EditHistory.list(root).getFirst().status());
    }

    @Test void compilationFailureAndMissingCompilerNeverWrite() throws Exception {
        var p=proposal(true); byte[] before=Files.readAllBytes(p.master().root().resolve("main.tex"));
        assertThrows(IOException.class,()->MergeTransaction.apply(p,gate(1)));
        assertThrows(IOException.class,()->MergeTransaction.apply(p,new TexCompileGate(null,true,null,(a,b,c)->0)));
        assertArrayEquals(before,Files.readAllBytes(p.master().root().resolve("main.tex")));
        assertFalse(Files.exists(p.master().root().resolve(".tex-suite/backups")));
    }

    @Test void createdFilesRollbackAfterMasterCommitFailure() throws Exception {
        var p=proposal(true); byte[] before=Files.readAllBytes(p.master().root().resolve("main.tex"));
        assertThrows(IOException.class,()->MergeTransaction.apply(p,gate(0),(a,b)-> { throw new IOException("injected failure"); }));
        assertArrayEquals(before,Files.readAllBytes(p.master().root().resolve("main.tex")));
        for(var path:p.creates().keySet()) assertFalse(Files.exists(p.master().root().resolve(path)));
        assertTrue(TextRecovery.pending(p.master().root()).isEmpty());
        assertEquals("restored",EditHistory.list(p.master().root()).getFirst().status());
    }

    @Test void externalEditToImportBlocksWholeUndo() throws Exception {
        var p=proposal(true); Path backup=MergeTransaction.apply(p,gate(0));
        Path created=p.master().root().resolve(p.creates().keySet().iterator().next()); Files.writeString(created,"User edits");
        byte[] main=Files.readAllBytes(p.master().root().resolve("main.tex"));
        assertThrows(IOException.class,()->TextRecovery.revert(p.master().root(),backup));
        assertArrayEquals(main,Files.readAllBytes(p.master().root().resolve("main.tex"))); assertEquals("User edits",Files.readString(created));
    }

    @Test void destinationCollisionAndSymlinkNeverOverwrite() throws Exception {
        var p=proposal(true); Path target=p.master().root().resolve(p.creates().keySet().iterator().next()); Files.createDirectories(target.getParent()); Files.writeString(target,"existing");
        assertThrows(IOException.class,()->MergeTransaction.apply(p,gate(0))); assertEquals("existing",Files.readString(target));
        Files.delete(target); Files.createSymbolicLink(target,directory.resolve("donor/main.tex"));
        assertThrows(IOException.class,()->MergeTransaction.apply(p,gate(0)));
    }

    @Test void sourceChangeDuringCompileInvalidatesApproval() throws Exception {
        var p=proposal(false); AtomicInteger calls=new AtomicInteger();
        var gate=new TexCompileGate(directory.resolve("pdflatex"),false,null,(command,working,timeout)-> {
            Files.writeString(working.resolve("texsuite-validation-work/texsuite-validation.pdf"),"PDF");
            if(calls.incrementAndGet()==4) Files.writeString(directory.resolve("donor/main.tex"),MergePlannerTest.doc("","external edit")); return 0;
        });
        assertThrows(IOException.class,()->MergeTransaction.apply(p,gate));
        assertEquals(MergePlannerTest.doc("","Master."),Files.readString(p.master().root().resolve("main.tex")));
    }

    @Test void forgedAnchoredEditCannotWriteDifferentProposal() throws Exception {
        var p=proposal(false);
        var forged=new MergePlanner.Proposal(p.master(),p.edits(),Map.of(Path.of("main.tex"),"forged".getBytes()),p.creates(),p.decisions(),p.freshness());
        assertThrows(IOException.class,()->MergeTransaction.apply(forged,gate(0)));
    }

    @Test void tamperedHistoryIsRejected() throws Exception {
        var p=proposal(true); Path backup=MergeTransaction.apply(p,gate(0));
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)ModelProtocol.JSON.readTree(Files.readAllBytes(backup.resolve("changes.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.withArray("edits").get(0)).put("newText","Zm9yZ2Vk");
        Files.write(backup.resolve("changes.json"),ModelProtocol.JSON.writeValueAsBytes(tree));
        assertThrows(IOException.class,()->EditHistory.list(p.master().root()));
    }

    @Test void interruptedUndoCanResumeWithCreatedFileAlreadyAbsent() throws Exception {
        var p=proposal(true); Path backup=MergeTransaction.apply(p,gate(0));
        var journal=TextRecovery.journal(p.master().root(),backup); journal.setProperty("status","pending"); journal.setProperty("action","revert"); TextRecovery.writeJournalValues(backup,journal);
        for(Path path:p.creates().keySet()) Files.delete(p.master().root().resolve(path));
        TextRecovery.restore(p.master().root(),backup);
        assertEquals(MergePlannerTest.doc("","Master."),Files.readString(p.master().root().resolve("main.tex")));
        assertTrue(TextRecovery.pending(p.master().root()).isEmpty());
    }

    @Test void destinationCreatedDuringCompileIsPreserved() throws Exception {
        var p=proposal(true);
        Path target=p.master().root().resolve(p.creates().keySet().iterator().next());
        var gate=new TexCompileGate(directory.resolve("pdflatex"),false,null,(command,working,timeout)-> {
            Files.writeString(working.resolve("texsuite-validation-work/texsuite-validation.pdf"),"PDF");
            Files.createDirectories(target.getParent()); Files.writeString(target,"External file"); return 0;
        });
        assertThrows(IOException.class,()->MergeTransaction.apply(p,gate));
        assertEquals("External file",Files.readString(target));
        assertEquals(MergePlannerTest.doc("","Master."),Files.readString(p.master().root().resolve("main.tex")));
        assertFalse(Files.exists(p.master().root().resolve(".tex-suite/backups")));
    }

    @Test void changedCompileAssetInvalidatesApproval() throws Exception {
        var p=proposal(false);
        Path asset=p.master().root().resolve("figure.pdf"); Files.writeString(asset,"Before");
        var gate=new TexCompileGate(directory.resolve("pdflatex"),false,null,(command,working,timeout)-> {
            Files.writeString(working.resolve("texsuite-validation-work/texsuite-validation.pdf"),"PDF");
            Files.writeString(asset,"After"); return 0;
        });
        assertThrows(StaleSourceException.class,()->MergeTransaction.apply(p,gate));
        assertEquals(MergePlannerTest.doc("","Master."),Files.readString(p.master().root().resolve("main.tex")));
    }

    @Test void baselineWarningsAreReportedAndNewWarningsBlock() throws Exception {
        var p=proposal(false); var reported=new java.util.ArrayList<String>();
        AtomicInteger calls=new AtomicInteger();
        var gate=new TexCompileGate(directory.resolve("pdflatex"),false,null,(command,working,timeout)-> {
            Path output=working.resolve("texsuite-validation-work");
            Files.writeString(output.resolve("texsuite-validation.pdf"),"PDF");
            String warning="LaTeX Warning: Reference `existing' on page 1 undefined on input line 4.\n";
            if(calls.incrementAndGet()>3) warning+="LaTeX Warning: Citation `new' on page 1 undefined on input line 8.\n";
            Files.writeString(output.resolve("texsuite-validation.log"),warning); return 0;
        });
        IOException failure=assertThrows(IOException.class,()->MergeTransaction.apply(p,gate,warning -> reported.add(warning)));
        assertTrue(failure.getMessage().contains("introduces unresolved"));
        assertEquals(1,reported.size()); assertTrue(reported.getFirst().contains("Pre-existing master warning:"));
        assertFalse(reported.getFirst().contains("`new'"));
        assertFalse(Files.exists(p.master().root().resolve(".tex-suite/backups")));
    }

    @Test void ambiguousInsertionOrderIsRejectedEvenIfBytesReconstruct() throws Exception {
        var p=proposal(false); var e=p.edits().getFirst();
        var duplicate=new TextEditPlan.Edit(e.path(),e.start(),e.end(),e.expected(),e.line(),e.column(),"",e.rule());
        var edits=new java.util.ArrayList<>(p.edits()); edits.add(duplicate);
        var forged=new MergePlanner.Proposal(p.master(),edits,p.updates(),p.creates(),p.decisions(),p.freshness());
        assertThrows(IOException.class,()->MergeTransaction.apply(forged,gate(0)));
        assertFalse(Files.exists(p.master().root().resolve(".tex-suite/backups")));
    }

    @Test void mergeHistoryCannotClaimDifferentAuthorization() throws Exception {
        var p=proposal(false); Path backup=MergeTransaction.apply(p,gate(0));
        byte[] original=Files.readAllBytes(backup.resolve("changes.json"));
        for(String field:java.util.List.of("operation","scope","region","source","replacement","decisionSource","provider","model")) {
            var tree=(com.fasterxml.jackson.databind.node.ObjectNode)ModelProtocol.JSON.readTree(original);
            ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("intent")).put(field,"forged");
            Files.write(backup.resolve("changes.json"),ModelProtocol.JSON.writeValueAsBytes(tree));
            assertThrows(IOException.class,()->EditHistory.list(p.master().root()),field);
        }
    }
}
