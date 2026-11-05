package texsuite;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergeWorkflowTest {
    @TempDir Path directory;

    @Test void reviewedWorkflowAppliesAndCancelDoesNot() throws Exception {
        Path master=source("master/main.tex",MergePlannerTest.doc("","Master."));
        Path donor=source("donor/main.tex",MergePlannerTest.doc("","Donor."));
        byte[] before=Files.readAllBytes(master);
        String cancelled=run(master,donor+"\n\n\n\nn\n",false);
        assertTrue(cancelled.contains("Merge cancelled")); assertArrayEquals(before,Files.readAllBytes(master));
        String applied=run(master,donor+"\n\n\n\ny\n",false);
        assertTrue(applied.contains("Merge applied"),applied);
        assertTrue(Files.readString(master).contains("Donor."));
    }

    @Test void wizardAcceptsMissingDefinitionAndRechecksIt() throws Exception {
        Path master=source("master/main.tex",MergePlannerTest.doc("","Master."));
        Path donor=source("donor/main.tex",MergePlannerTest.doc("","$\\countValue$"));
        String output=run(master,donor+"\n\n\n\ndefine\n\\newcommand{\\countValue}{42}\n.\ny\n",false);
        assertTrue(output.contains("Merge applied"),output);
        assertTrue(Files.readString(master).contains("\\newcommand{\\countValue}{42}"));
    }

    @Test void missingCompilerAllowsPreviewButNeverApply() throws Exception {
        Path master=source("master/main.tex",MergePlannerTest.doc("","Master."));
        Path donor=source("donor/main.tex",MergePlannerTest.doc("","Donor."));
        String output=run(master,donor+"\n\n\n\ny\n",true);
        assertTrue(output.contains("Merge requires pdflatex"),output);
        assertFalse(Files.readString(master).contains("Donor."));
    }

    @Test void chapterMergeThenSanitationUndoInOrderAcrossReopenedSession() throws Exception {
        directory=directory.toRealPath();
        Path main=source("master/main.tex",MergePlannerTest.doc("","\\input{chapters/chapter}"));
        Path chapter=source("master/chapters/chapter.tex","Chapter.  \n");
        Path donor=source("donor/main.tex",MergePlannerTest.doc("","Donor."));
        byte[] originalMain=Files.readAllBytes(main),originalChapter=Files.readAllBytes(chapter),originalDonor=Files.readAllBytes(donor);
        TexCompileGate gate=new TexCompileGate(directory.resolve("pdflatex"),false,main,(command,working,timeout)-> {
            String output=command.stream().filter(s->s.startsWith("-output-directory=")).findFirst().orElseThrow().substring("-output-directory=".length());
            Files.writeString(working.resolve(output).resolve("texsuite-validation.pdf"),"PDF"); return 0;
        });
        String applied=session(chapter,"4\n"+donor+"\n\n\n\ny\n3\nall\n\ny\n6\n",gate);
        assertTrue(applied.contains("Merge applied"),applied);
        assertTrue(applied.contains("sanitation fix(es)"),applied);
        assertTrue(Files.readString(chapter).contains("Donor."));
        assertFalse(Files.readString(chapter).contains("Chapter.  "));

        String undone=session(chapter,"7\ny\n7\ny\n6\n",gate);
        assertEquals(2,undone.split("Last change reverted",-1).length-1,undone);
        assertArrayEquals(originalMain,Files.readAllBytes(main));
        assertArrayEquals(originalChapter,Files.readAllBytes(chapter));
        assertArrayEquals(originalDonor,Files.readAllBytes(donor));
        assertEquals("restored",EditHistory.list(main.getParent()).getFirst().status());
        assertEquals("restored",EditHistory.list(chapter.getParent()).getFirst().status());
    }

    private String session(Path source,String answers,TexCompileGate gate) throws Exception {
        var settings=new ModelSettings(directory.resolve("ai.json"),name->null);
        settings.save(new ModelSettings.Profile("","OPENAI_API_KEY",60));
        var output=new StringWriter(); var errors=new StringWriter();
        var session=new DocumentSession(new BufferedReader(new StringReader(answers)),new PrintWriter(output,true),
                new PrintWriter(errors,true),source,false,()->0,new EditorPreferences(directory.resolve("editor.properties")),
                (path,app)->java.util.Optional.empty(),java.util.Optional::empty,gate,
                profile->{fail("Merge/sanitation must not call AI"); return null;},settings);
        assertEquals(0,session.run(),errors.toString());
        assertEquals("",errors.toString());
        return output.toString();
    }

    private Path source(String path,String text) throws Exception { Path file=directory.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file,text); return file.toRealPath(); }
    private String run(Path master,String input,boolean missing) throws Exception {
        StringWriter output=new StringWriter(); PrintWriter writer=new PrintWriter(output,true);
        TexCompileGate gate=new TexCompileGate(missing?null:directory.resolve("pdflatex"),true,null,(command,working,timeout)-> {
            Files.writeString(working.resolve("texsuite-validation-work/texsuite-validation.pdf"),"PDF"); return 0;
        });
        assertEquals(0,new MergeWorkflow(new BufferedReader(new StringReader(input)),writer,writer,master,gate).run());
        return output.toString();
    }
}
