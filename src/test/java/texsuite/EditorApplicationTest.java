package texsuite;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class EditorApplicationTest {
    @TempDir Path temporaryDirectory;

    static Path application(Path directory) throws IOException {
        Path app = directory.resolve("Éditeur test.app");
        Files.createDirectories(app.resolve("Contents/MacOS"));
        Files.writeString(app.resolve("Contents/Info.plist"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                <key>CFBundlePackageType</key><string>APPL</string>
                <key>CFBundleExecutable</key><string>editor</string>
                </dict></plist>
                """);
        Path executable = Files.writeString(app.resolve("Contents/MacOS/editor"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return app.toRealPath();
    }

    @Test
    void rejectsNamesRelativePathsAndUnsafeUnicode() {
        for (String value : List.of("hel", "Visual", "", "relative.app", "/tmp/bad\u0000.app",
                "/tmp/bad\u001b.app", "/tmp/bad\u202e.app", "/tmp/bad\u2028.app",
                "/tmp/bad\u2029.app", "/tmp/bad\ud800.app")) {
            assertThrows(IOException.class, () -> EditorApplication.validate(value), value);
        }
        assertTrue(EditorApplication.safeText("Éditeur test.app"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void validatesXmlAndBinaryBundlesAndPersistsExactPath() throws Exception {
        Path app = application(temporaryDirectory);
        assertEquals(app.toString(), EditorApplication.validate(app.toString()));
        EditorApplication.command(List.of("/usr/bin/plutil", "-convert", "binary1",
                app.resolve("Contents/Info.plist").toString()), 5);
        assertEquals(app.toString(), EditorApplication.validate(app.toString()));

        Path storage = temporaryDirectory.resolve("prefs/editor.properties");
        EditorPreferences preferences = new EditorPreferences(storage);
        preferences.setAutomatic(false);
        preferences.setApplication(app.toString());
        preferences.save();
        EditorPreferences loaded = new EditorPreferences(storage);
        assertTrue(loaded.load().isEmpty());
        assertEquals(app.toString(), loaded.application());
        assertFalse(loaded.automatic());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void invalidSelectionsCannotReplaceAnExistingChoice() throws Exception {
        Path app = application(temporaryDirectory);
        EditorPreferences preferences = new EditorPreferences(temporaryDirectory.resolve("editor.properties"));
        preferences.setApplication(app.toString());
        Path fake = Files.createDirectory(temporaryDirectory.resolve("fake.app"));
        for (String value : List.of("hel", temporaryDirectory.toString(), fake.toString(),
                temporaryDirectory.resolve("missing.app").toString())) {
            assertThrows(IOException.class, () -> preferences.setApplication(value));
            assertEquals(app.toString(), preferences.application());
        }

        Files.writeString(app.resolve("Contents/Info.plist"), "not a plist");
        assertThrows(IOException.class, () -> EditorApplication.validate(app.toString()));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void rejectsWrongPackageTypeMissingAndEscapingExecutables() throws Exception {
        Path app = application(temporaryDirectory);
        Path metadata = app.resolve("Contents/Info.plist");
        String original = Files.readString(metadata);
        Files.writeString(metadata, original.replace("APPL", "BNDL"));
        assertThrows(IOException.class, () -> EditorApplication.validate(app.toString()));
        Files.writeString(metadata, original.replace("<string>editor</string>", "<string>../editor</string>"));
        assertThrows(IOException.class, () -> EditorApplication.validate(app.toString()));
        Files.writeString(metadata, original);

        Path executable = app.resolve("Contents/MacOS/editor");
        Files.delete(executable);
        assertThrows(IOException.class, () -> EditorApplication.validate(app.toString()));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside"), "outside");
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rwx------"));
        Files.createSymbolicLink(executable, outside);
        assertThrows(IOException.class, () -> EditorApplication.validate(app.toString()));
    }

    @Test
    void badSavedApplicationPreservesAutomaticFlagAndIsClearedOnSave() throws Exception {
        Path storage = temporaryDirectory.resolve("editor.properties");
        for (String application : List.of("hel", "Visual", "/missing/editor.app", "\\u001Bbad")) {
            Files.writeString(storage, "automatic=false\napplication=" + application + "\n");
            EditorPreferences preferences = new EditorPreferences(storage);
            assertTrue(preferences.load().isPresent());
            assertNull(preferences.application());
            assertFalse(preferences.automatic());
            preferences.save();
            EditorPreferences loaded = new EditorPreferences(storage);
            assertTrue(loaded.load().isEmpty());
            assertFalse(loaded.automatic());
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void launchRevalidatesAnApplicationDeletedAfterSelection() throws Exception {
        Path app = application(temporaryDirectory);
        EditorPreferences preferences = new EditorPreferences(temporaryDirectory.resolve("editor.properties"));
        preferences.setApplication(app.toString());
        Files.delete(app.resolve("Contents/MacOS/editor"));
        assertTrue(EditorLauncher.system().open(temporaryDirectory.resolve("paper.tex"),
                preferences.application()).orElseThrow().contains("invalid or unavailable"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void nativeCommandsReportTimeoutAndFailure() {
        IOException timeout = assertThrows(IOException.class,
                () -> EditorApplication.command(List.of("/bin/sleep", "10"), 0));
        assertTrue(timeout.getMessage().contains("timed out"));
        assertThrows(IOException.class, () -> EditorApplication.command(List.of("/usr/bin/false"), 5));
    }
}
