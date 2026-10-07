# Weekly contribution log

## Week of 14–20 September 2026

Created the Java 21/Maven CLI skeleton with help and version output, an executable JAR, a macOS installer, and CI. `./mvnw --batch-mode clean verify` passed with 2/2 tests, and the installed `texsuite` command worked from `~/.local/bin`. No implementation blocker remains; the next slice is guided `.tex` file selection.

## Week of 21–27 September 2026 (in progress)

After submitting the proposal and design deliverables, I reviewed TexSuite's document-input flow through hands-on CLI and picker trials. We implemented direct and relative `.tex` paths, bounded typo suggestions from the current and recent folders, a native macOS picker, and path, file-type, strict UTF-8, and control-character checks before any editing. My feedback led to clearer relative-path errors, a one-time Browse offer, no Spotlight search, and a black-and-white Dock icon that I visually approved. We logged only the reproducible AppKit picker warning while keeping other errors visible, and added a launcher regression test to CI. Path validation, sanitation, selection, and launcher changes were committed separately; the Java suite passed 20/20 tests and the launcher test passed.

## Week of 5–11 October 2026 (in progress)

Implemented optional mathematical meaning classification through native OpenAI, OpenRouter and Gemini adapters, explicit provider/model/environment-key profiles and reviewed provider provenance. Exact payload consent, bounded transport, strict complete-ID/schema checks, one separately approved repair and operation-wide manual fallback preserve the existing source/compile/backup/recovery guards. Corrected Gemini schema settings using the official Gemini 2.5 SDK contract and documented credential-name guidance and provider assumptions. Local fixtures exercise provider envelopes, failures, profile persistence, selective approval and exact repair evidence; live AI appears to work according to user feedback, with detailed provider results unrecorded and OpenAI testing deferred.
