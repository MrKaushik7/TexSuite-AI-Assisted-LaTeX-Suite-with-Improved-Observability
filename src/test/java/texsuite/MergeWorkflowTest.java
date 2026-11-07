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
        String masterDefinition="\\newcommand{\\vectorSymbol}[1]{\\mathbf{#1}}";
        String donorDefinition="\\newcommand{\\vectorSymbol}{v}";
        Path master=source("master/main.tex",MergePlannerTest.doc(masterDefinition,"Master."));
        Path donor=source("donor/main.tex",MergePlannerTest.doc(donorDefinition,"$\\countValue$ $\\vectorSymbol$"));
        String output=run(master,donor+"\n\n\n\ndefine\n\\newcommand{\\countValue}{42}\n.\nrename\ny\n",false);
        assertTrue(output.contains("Merge applied"),output);
        assertTrue(output.contains("Macro vectorSymbol"+System.lineSeparator()+"MASTER: "+masterDefinition
                +System.lineSeparator()+"DONOR: "+donorDefinition),output);
        assertTrue(output.contains("\\newcommand{\\countValue}{s}"));
        assertTrue(output.contains("\\def\\countValue{s}"));
        assertTrue(output.contains("A bare value such as s is not a declaration."));
        assertTrue(output.indexOf("Enter a complete LaTeX command declaration")<output.indexOf("definition>"));
        assertTrue(Files.readString(master).contains("\\newcommand{\\countValue}{42}"));
        assertTrue(Files.readString(master).contains("\\newcommand{\\donorvectorSymbol}{v}"));
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

    @Test void browseValidatesSelectedFilesAndAcceptsPathsWithSpaces() throws Exception {
        Path master=source("master folder/main.tex",MergePlannerTest.doc("","Master."));
        Path donor=source("donor folder/main.tex",MergePlannerTest.doc("","Donor."));
        Path invalid=source("donor folder/notes.txt","Not TeX");
        var selections=new java.util.ArrayDeque<>(java.util.List.of(invalid,donor,master));
        var opened=new java.util.ArrayList<Path>();
        String output=run(master,"\nbrowse\nBROWSE\n\n\nn\n",true,
                ()->java.util.Optional.of(selections.removeFirst()),opened::add);

        assertTrue(selections.isEmpty());
        assertEquals(java.util.List.of(donor),opened);
        assertTrue(output.contains("Enter=browse"));
        assertTrue(output.contains("Choose a .tex file"));
        assertTrue(output.contains("Resolved merge:"),output);
        assertTrue(output.contains("Donor."));
        assertEquals(MergePlannerTest.doc("","Master."),Files.readString(master));
        assertFalse(Files.exists(master.getParent().resolve(".tex-suite")));
    }

    @Test void cancellingPickerReturnsToPromptAndBackLeavesSourcesUntouched() throws Exception {
        Path master=source("master/main.tex",MergePlannerTest.doc("","Master."));
        String output=run(master,"browse\nback\n",true,java.util.Optional::empty);

        assertTrue(output.contains("No file selected"));
        assertTrue(output.contains("Merge cancelled"));
        assertFalse(output.contains("Resolved merge:"));
        assertEquals(MergePlannerTest.doc("","Master."),Files.readString(master));
        assertFalse(Files.exists(master.getParent().resolve(".tex-suite")));
    }

    @Test void unavailablePickerAllowsTypedPathFallback() throws Exception {
        Path master=source("master/main.tex",MergePlannerTest.doc("","Master."));
        Path donor=source("donor/main.tex",MergePlannerTest.doc("","Donor."));
        String output=run(master,"browse\n"+donor+"\n\n\n\nn\n",true,
                ()->{throw new java.awt.HeadlessException();});

        assertTrue(output.contains("Native file picker is unavailable"));
        assertTrue(output.contains("Resolved merge:"),output);
        assertEquals(MergePlannerTest.doc("","Master."),Files.readString(master));
        assertFalse(Files.exists(master.getParent().resolve(".tex-suite")));
    }

    @Test void sessionOpensOnlyValidDonorAndRespectsEditorPreferenceAndFailure() throws Exception {
        directory=directory.toRealPath();
        Path master=source("master/main.tex",MergePlannerTest.doc("","Master."));
        Path donor=source("donor/main.tex",MergePlannerTest.doc("","Donor."));
        Path invalid=source("notes.txt","Not TeX");
        var settings=new ModelSettings(directory.resolve("ai.json"),name->null);
        settings.save(new ModelSettings.Profile("","OPENAI_API_KEY",60));
        for (int mode=0;mode<3;mode++) {
            boolean enabled=mode!=1,failLaunch=mode==2;
            var preferences=new EditorPreferences(directory.resolve("editor.properties"));
            preferences.setAutomatic(enabled); preferences.save();
            var opened=new java.util.ArrayList<Path>();
            var output=new StringWriter(); var errors=new StringWriter();
            EditorLauncher launcher=(path,app)-> {
                opened.add(path);
                return failLaunch&&path.equals(donor) ? java.util.Optional.of("Donor editor unavailable") : java.util.Optional.empty();
            };
            var session=new DocumentSession(new BufferedReader(new StringReader("4\n"+invalid+"\n"+donor+"\nback\n6\n")),
                    new PrintWriter(output,true),new PrintWriter(errors,true),master,false,()->0,preferences,launcher,
                    java.util.Optional::empty,new TexCompileGate(null,true,null,null),
                    profile->{fail("Merge must not call AI");return null;},settings);

            assertEquals(0,session.run(),errors.toString());
            assertEquals(enabled?java.util.List.of(master,donor):java.util.List.of(),opened);
            assertTrue(output.toString().contains("Master compilation main"));
            assertTrue(output.toString().contains("Merge cancelled"));
            assertEquals(failLaunch,errors.toString().contains("Donor editor unavailable"));
            assertEquals(MergePlannerTest.doc("","Master."),Files.readString(master));
            assertEquals(MergePlannerTest.doc("","Donor."),Files.readString(donor));
            assertFalse(Files.exists(master.getParent().resolve(".tex-suite")));
        }
    }

    private Path source(String path,String text) throws Exception { Path file=directory.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file,text); return file.toRealPath(); }
    private String run(Path master,String input,boolean missing) throws Exception {
        return run(master,input,missing,()->{fail("Typed-path tests must not open a native picker"); return java.util.Optional.empty();});
    }

    private String run(Path master,String input,boolean missing,java.util.function.Supplier<java.util.Optional<Path>> picker) throws Exception {
        return run(master,input,missing,picker,donor->{ });
    }

    private String run(Path master,String input,boolean missing,java.util.function.Supplier<java.util.Optional<Path>> picker,
            java.util.function.Consumer<Path> donorSelected) throws Exception {
        StringWriter output=new StringWriter(); PrintWriter writer=new PrintWriter(output,true);
        TexCompileGate gate=new TexCompileGate(missing?null:directory.resolve("pdflatex"),true,null,(command,working,timeout)-> {
            Files.writeString(working.resolve("texsuite-validation-work/texsuite-validation.pdf"),"PDF"); return 0;
        });
        assertEquals(0,new MergeWorkflow(new BufferedReader(new StringReader(input)),writer,writer,master,gate,picker,donorSelected).run());
        return output.toString();
    }
}
