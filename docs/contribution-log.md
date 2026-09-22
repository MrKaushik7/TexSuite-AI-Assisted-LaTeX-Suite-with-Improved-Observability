# Weekly contribution log

## Week of 14–20 September 2026

Created the Java 21/Maven CLI skeleton with help and version output, an executable JAR, a macOS installer, and CI. `./mvnw --batch-mode clean verify` passed with 2/2 tests, and the installed `texsuite` command worked from `~/.local/bin`. No implementation blocker remains; the next slice is guided `.tex` file selection.

## Week of 21–27 September 2026 (in progress)

After submitting the proposal and design deliverables, I reviewed TexSuite's document-input flow through hands-on CLI and picker trials. We implemented direct and relative `.tex` paths, bounded typo suggestions from the current and recent folders, a native macOS picker, and path, file-type, strict UTF-8, and control-character checks before any editing. My feedback led to clearer relative-path errors, a one-time Browse offer, no Spotlight search, and a black-and-white Dock icon that I visually approved. We logged only the reproducible AppKit picker warning while keeping other errors visible, and added a launcher regression test to CI. Path validation, sanitation, selection, and launcher changes were committed separately; the Java suite passed 20/20 tests and the launcher test passed.
