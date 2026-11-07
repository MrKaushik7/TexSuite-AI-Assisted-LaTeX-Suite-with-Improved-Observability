# Fix a problem

[Home](../README.md) · [First edit](getting-started.md) · [Task guide](user-guide.md) · [Safety reference](../opinions.md)

## Startup and installation

| Symptom | Next action |
| --- | --- |
| `texsuite: command not found` | In the launch terminal, run `export PATH="$HOME/.local/bin:$PATH"`; check that `~/.local/bin/texsuite` exists. Run the installer from the source checkout if needed. |
| Java missing or unsupported class version | Make Java 21+ available as `java` on PATH, then rerun the installer. |
| Missing or invalid JAR | From the source checkout, run `./mvnw clean verify`, then `./scripts/install.sh`. |
| Native picker unavailable or cancelled | Enter the `.tex` path instead, or type `browse` again. Relative paths start in the displayed/launch folder. |
| Editor fails to open | Use Settings **3** to browse for an installed `.app`, or **5** to reset the editor. Save source before retrying an operation. |
| API key missing or malformed | Quit, export the configured variable in the terminal, then relaunch. The [key instructions](ai-setup.md#export-the-key-and-restart) distinguish its name from its value. |
| Readable key but provider request fails | Use the separately consented Settings **8 → 2** test. Check access, model ID, quota and timeout. Manual review remains available. |

## AI requests

| Symptom | Reason and next action |
| --- | --- |
| `INCOMPLETE. OpenRouter did not return a completed response.` | Check the appended finish reason, token counts and numeric error code when available. A length limit and a provider failure need different next steps; see [incomplete responses](ai-setup.md#incomplete-responses). Enter `y` for manual review or cancel. |
| `TIMEOUT` | TexSuite reached the configured request deadline. It differs from a returned `INCOMPLETE` response; increase the timeout if you want to allow a longer wait. |
| `RATE_LIMIT` | Check the selected endpoint's quota and retry after its reset, or use manual review. |

## Compilation and chapters

| Symptom | Reason and next action |
| --- | --- |
| `pdflatex` unavailable | Install a TeX distribution or fix PATH. [Installation](getting-started.md#install) explains detection. Ordinary edits alone can explicitly use `--allow-no-compile`. |
| Class/package/font missing | Install the dependency, for example with `tlmgr`, and retry. Compiler discovery does not check packages. |
| Chapter has no compilation main | Confirm its root hint or choose a main containing all edits through unconditional static includes. For a parent project, launch with `--compile-main`; see [chapters](user-guide.md#chapters-and-compilation). |
| Compilation fails or times out | Read the main path and bounded compiler error summary, fix the saved project and rerun review. `--allow-no-compile` does not override failure/timeout. |
| Merge introduces unresolved references/citations | Fix mappings or bibliography dependencies before retrying. Existing warnings and new blockers are reported separately. Merge always requires pdflatex and sometimes BibTeX. |

## Selection, cleanup and merge

| Symptom | Reason and next action |
| --- | --- |
| No eligible matches | Check literal spelling, whole-word mode, region and scope. Use `--debug` for excluded/review reasons. Protected syntax cannot be forced through AI. |
| Expected included file is absent | Project scope follows forward static includes. Open the main for its context; a compilation-main flag does not widen rename context. |
| Cleanup offers no fixes | Source may be protected or use unsupported whitespace semantics. Read the reported blocker and inspect source manually. Repeated packages/Unicode findings are advisory. |
| Line-ending conversion unavailable | Every changed terminator must be eligible. Keep endings or repair unsupported source manually; partial normalization is not offered. |
| Cleanup exceeds 200 patches | Select fewer trailing fixes or keep line endings. Oversized rule groups are withheld, never truncated. |
| Merge asks for a definition | Enter a full declaration for the requested name, then `.` on its own line. Bare replacement text is insufficient. [Merge examples](user-guide.md#merge-documents). |
| Merge rejects dynamic syntax, local styles or Biber | Adapt the donor manually to the [supported merge subset](../opinions.md#smart-merge). Compilation cannot establish arbitrary compatibility. |
| Source changed during review | Old decisions were discarded. Save editor changes, then make a fresh review and consent decision. |

## History and recovery

| Symptom | Next action |
| --- | --- |
| Interrupted edit blocks another operation | Open **Settings → 7**, inspect the pending operation and type `restore` when appropriate. |
| Undo refuses external changes or damaged backups | Preserve your current work. Compare it with retained backups manually; TexSuite will not overwrite unexpected bytes. |
| Chapter cannot find merge history | Reopen the master main or supply the same `--compile-main` used for the merge. |
| Approved plan exists but no edit happened | Plans record intent. Only the recovery journal's `complete` status establishes apply; compilation may have failed later. |
| History has stale links or missing legacy details | Hashes changed or the old journal lacks exact fields. Inspect retained journals/backups; do not fabricate or edit history metadata. |

If a flow is confusing, enter `back` to return without applying pending source
edits. Use main-menu **8: Help**, or `?` at a decision prompt marked `?=help`.
