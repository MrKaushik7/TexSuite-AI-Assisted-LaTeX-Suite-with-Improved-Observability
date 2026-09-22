package texsuite;

import java.awt.AWTError;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

final class DocumentInput {
    private final BufferedReader input;
    private final boolean interactive;
    private final Path workingDirectory;
    private final RecentFolders recentFolders;
    private final Supplier<Optional<Path>> filePicker;
    private final PrintWriter output;
    private final PrintWriter errors;
    private final DocumentInputValidator validator = new DocumentInputValidator();
    private final DocumentInputSanitizer sanitizer = new DocumentInputSanitizer();
    private boolean cancelledByUser;
    private boolean browseOffered;

    DocumentInput(BufferedReader input, boolean interactive, Path workingDirectory,
            RecentFolders recentFolders, Supplier<Optional<Path>> filePicker,
            PrintWriter output, PrintWriter errors) {
        this.input = input;
        this.interactive = interactive;
        this.workingDirectory = workingDirectory;
        this.recentFolders = recentFolders;
        this.filePicker = filePicker;
        this.output = output;
        this.errors = errors;
    }

    Optional<Path> select(String initialPath) {
        String enteredPath = initialPath;
        while (true) {
            if (enteredPath == null) {
                if (!interactive) {
                    errors.println("Provide a .tex file path: texsuite FILE");
                    return Optional.empty();
                }
                enteredPath = prompt();
                if (enteredPath == null) {
                    errors.println("Input ended before a file was selected.");
                    return Optional.empty();
                }
                if (enteredPath.equalsIgnoreCase("quit")) {
                    cancelledByUser = true;
                    output.println("Document selection cancelled; no source file was changed.");
                    return Optional.empty();
                }
                if (enteredPath.equalsIgnoreCase("browse")) {
                    enteredPath = browse();
                    continue;
                }
            }

            if (enteredPath.isBlank()) {
                errors.println("Enter a .tex path, browse, or quit.");
                enteredPath = null;
                continue;
            }

            try {
                Path selectedFile = validator.validate(enteredPath, workingDirectory);
                sanitizer.check(selectedFile);
                try {
                    recentFolders.remember(selectedFile.getParent());
                } catch (IOException exception) {
                    errors.println("Could not save recent folder; selection still succeeded.");
                }
                return Optional.of(selectedFile);
            } catch (DocumentInputValidator.InputException exception) {
                showInputError(enteredPath, exception);
                if (!interactive) {
                    return Optional.empty();
                }
                showSuggestions(recentFolders.suggest(enteredPath, workingDirectory));
                enteredPath = offerBrowse();
            }
        }
    }

    boolean wasCancelledByUser() {
        return cancelledByUser;
    }

    private String prompt() {
        output.print(browseOffered ? "LaTeX file path or quit: "
                : "LaTeX file path, browse, or quit: ");
        output.flush();
        try {
            return input.readLine();
        } catch (IOException exception) {
            errors.println("Could not read terminal input.");
            return null;
        }
    }

    private String browse() {
        try {
            Optional<Path> selectedFile = filePicker.get();
            if (selectedFile.isEmpty()) {
                output.println("No file selected; enter a path or browse again.");
            }
            return selectedFile.map(Path::toString).orElse(null);
        } catch (HeadlessException | AWTError | SecurityException exception) {
            errors.println("Native file picker is unavailable here; enter a path instead.");
            return null;
        }
    }

    private String offerBrowse() {
        if (browseOffered) {
            return null;
        }
        browseOffered = true;
        while (true) {
            output.print("Browse for a file? (y/N): ");
            output.flush();
            String answer;
            try {
                answer = input.readLine();
            } catch (IOException exception) {
                errors.println("Could not read terminal input.");
                return null;
            }
            if (answer == null || answer.isBlank() || answer.equalsIgnoreCase("n")
                    || answer.equalsIgnoreCase("no")) {
                return null;
            }
            if (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes")) {
                return browse();
            }
            errors.println("Type y or n.");
        }
    }

    private void showInputError(String enteredPath, DocumentInputValidator.InputException exception) {
        if (exception.getMessage().equals("file does not exist")) {
            Path attemptedPath = workingDirectory.resolve(enteredPath).normalize();
            errors.printf("Cannot open %s: file does not exist at %s%n",
                    safeDisplay(enteredPath), safeDisplay(attemptedPath));
            return;
        }
        errors.printf("Cannot open %s: %s%n", safeDisplay(enteredPath), exception.getMessage());
    }

    private void showSuggestions(List<Path> suggestions) {
        if (suggestions.isEmpty()) {
            output.printf("No close .tex file found under %s or saved recent folders.%n",
                    safeDisplay(workingDirectory));
        } else {
            output.println("Did you mean:");
            for (Path suggestion : suggestions) {
                output.printf("  %s%n", safeDisplay(suggestion));
            }
        }
    }

    static Optional<Path> openNativePicker() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new HeadlessException();
        }
        requestAppIcon();
        FileDialog picker = new FileDialog((Frame) null, "Select a LaTeX file", FileDialog.LOAD);
        try {
            picker.setVisible(true);
            if (picker.getFile() == null) {
                return Optional.empty();
            }
            return Optional.of(Path.of(picker.getDirectory(), picker.getFile()));
        } finally {
            picker.dispose();
        }
    }

    private static void requestAppIcon() {
        try {
            if (!Taskbar.isTaskbarSupported()) {
                return;
            }
            Taskbar taskbar = Taskbar.getTaskbar();
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                return;
            }
            try (InputStream iconFile = DocumentInput.class.getResourceAsStream("/texsuite-icon.png")) {
                if (iconFile == null) {
                    return;
                }
                BufferedImage iconImage = ImageIO.read(iconFile);
                if (iconImage != null) {
                    taskbar.setIconImage(iconImage);
                }
            }
        } catch (IOException | UnsupportedOperationException | SecurityException | AWTError exception) {
            // A cosmetic failure must not prevent document selection.
        }
    }

    static String safeDisplay(Path path) {
        return safeDisplay(path.toString());
    }

    static String safeDisplay(String text) {
        StringBuilder printable = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                printable.append('?');
            } else {
                printable.appendCodePoint(codePoint);
            }
        });
        return printable.toString();
    }
}
