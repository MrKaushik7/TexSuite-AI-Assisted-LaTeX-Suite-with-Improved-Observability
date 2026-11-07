# Use TexSuite

[Home](../README.md) · [First edit](getting-started.md#make-your-first-edit) · [AI setup](ai-setup.md) · [Troubleshooting](troubleshooting.md)

Save editor changes first. File scope is the default. Project scope follows the
selected file's forward static includes, not every file in a folder. Read-only
context never expands edit scope. `back` cancels a flow; main-menu **6: Quit** ends the session.
Protected or uncertain source cannot be selected.

## Rename a mathematical symbol

Use **1** when meaning determines which mathematical occurrences should change.

1. Enter literal LaTeX source, its intended meaning and the literal replacement.
2. Choose file scope (Enter) or project scope (**2**).
3. With AI configured, choose AI batch selection or manual review. Without AI,
   review each eligible occurrence manually.
4. Review and approve the selection as described under [review and apply](#review-and-apply).

Example: define `\newcommand{\numElements}{n}`, then rename intended `n` uses to
`\numElements`. Avoid `\number`: it is already a TeX primitive. Rendering follows
the replacement's TeX syntax. Whole standard Greek commands such as `\alpha` →
`\beta` work in known math and preserve scripts. Command prefixes, comments,
verbatim, definitions and uncertain regions remain protected.

Matches are lexical; eligibility does not prove meaning. Values outside math
can offer explicit source replacement, for example TikZ `0.5` → `0.75`. This
reroute uses substring matching. Declining keeps the original operation unchanged.

## Replace text

Use **2** for case-sensitive literal replacements.

1. Enter **Find literal text** and **Replace with**. Spaces remain exact.
2. Choose file or project scope.
3. Choose **1** document text (default), **2** eligible LaTeX source values,
   including preamble and math, or **3** a file line range.
4. Choose **1** whole words (default) or **2** substrings.
5. With AI configured, enter a purpose (Enter means all eligible literal matches),
   then choose AI or manual review. Replacement stays your literal input.
6. Review and approve the selected changes.

Whole words: `hi` matches `hi.`, `(hi)` and standalone `hi`, but not `This` or
`high`. Unicode letters, digits, combining marks and connector punctuation
(including `_`) are word characters. Boundaries apply only at word-like search
edges. Substrings allow embedded matches such as `0.5` in `0.5mm`. Filtering runs
before AI selection; AI cannot bypass it. Matching mode appears in review/history
and has a separate cache identity.

Comments, verbatim, definitions, command names, metadata and uncertain branches
stay protected in every region. Region-only exclusions can offer an explicit
source-region reroute; declining keeps the selected region. Changing a package name requires source mode and a valid, installed replacement package.

## Review and apply

| Mode | Select | Final approval |
| --- | --- | --- |
| Manual | `y` replaces an occurrence; Enter/`n` keeps it | Read the selected-only diff, then type `apply` |
| AI batch | Validated REPLACE decisions are preselected | Check counts, per-file totals, scope, replacement and compilation target; type `apply` |

AI actions: `diff` shows the full diff, `manual` opens individual review, `apply`
approves, and Enter/`cancel` stops. KEEP, needs-human-review and over-budget
candidates stay unchanged automatically. Individual AI prompts explicitly ask
whether to override KEEP or accept REPLACE; `y` always replaces. An uncertain
AI decision is selectable only if the scanner allows the occurrence.

Every remote request requires [separate payload consent](ai-setup.md#what-is-sent).
All batches must validate before suggestions appear. Classification failure
throws away all operation suggestions and offers manual review or cancellation.
The AI batch summary is mandatory; its full diff is available on request.

Empty selections, cancellation, quit and EOF leave source unchanged. Saved-source
or supporting-context changes require fresh review and consent. Apply compiles
a disposable copy, rechecks source, records private history/backups, then replaces
each affected file atomically. Failed checks block writes.

### Chapters and compilation

For a chapter, confirm its `% !TEX root = main.tex` hint or enter a compilation
main relative to the displayed project root. Enter at the path prompt cancels.
The main must contain every accepted edit through unconditional static includes.
For a containing project above the chapter folder:

```sh
texsuite --compile-main path/to/main.tex path/to/chapter.tex
```

This overrides root hints and changes compilation context, not rename context or
edit scope. Failures name the main and show a bounded compiler summary when
available. Missing `pdflatex` alone permits explicit `--allow-no-compile` for
rename/replacement/cleanup; compiler failure or timeout still blocks writes.
[Copy limits and compilation guarantees](../opinions.md#compilation-and-tex-installation).

## Clean up whitespace

Use **3: Clean up whitespace** for offline inspection of the selected file.
Includes/assets supply read-only context. No model is consulted.

1. Read the summary of fixes, manual-review findings and blockers.
2. Select trailing-fix numbers or `all`; Enter selects none.
3. Choose line endings: Enter keeps them; `lf` or `crlf` requests conversion.
4. Review exact whitespace changes and the diff, then enter `y` to apply.

The preview marks `␠` spaces, `⇥` tabs, `<CR>`/`<LF>` terminators and `∅` deletion.
Cleanup removes trailing ASCII spaces/tabs from eligible unconditional plain
prose. Blank lines, paragraph count and final-newline presence stay unchanged.
Commands, grouped arguments, math, comments, literal blocks, definitions,
uncertain text and preamble stay protected. Line-ending conversion is withheld
entirely if any changed terminator is unsupported.

Suspicious Unicode whitespace/format characters, selected lookalike punctuation,
and repeated literal preamble package imports/options need manual review.
Ordinary non-English letters are not flagged. There is no automatic Unicode
substitution, package removal/reordering, paragraph reflow or syntax repair.

Unsupported whitespace/category-code behavior, dynamic constructs, opaque macro
bodies, unknown environments or uncertain structure/dependencies can block fixes.
Simple literal macro bodies remain protected without blocking unrelated prose.
Missing/cyclic includes and invalid encoding require separate repair.

At most **200 patches** can be selected. Oversized rule groups are withheld,
never partly applied; choose fewer trailing fixes or keep line endings.
At most 200 manual-review rows appear, with an omitted count. Source changes
discard selection. Compilation, freshness, history and undo use the normal
apply path. [Precise cleanup limits](../opinions.md#deterministic-sanitation).

## Merge documents

Use **4: Merge documents** to import donor content into the selected master target.
The donor stays unchanged. Master class/options and existing definitions remain.
Merge is offline and requires compilation; `--allow-no-compile` cannot bypass it.

1. Choose the donor `.tex` path, or Enter/`browse` for a file picker. A valid donor
   opens in your editor when automatic opening is on.
2. Confirm the master compilation main (Enter keeps its default). It also accepts
   `browse`; cancelled browsing returns to the prompt. Type a path if browsing is unavailable.
3. Choose whole donor body, a listed section, or `lines START END` for a balanced
   line range. Choose insertion: end of body (Enter), `before NUMBER` or `after NUMBER`.
4. Resolve definition, package, label and citation conflicts explicitly.
5. Review decisions, files, the diff and imported content. Enter `y` at
   **Compile and apply this exact merge?** to approve.

Equivalent supported definitions/dependencies are reused. Differing compatible
ones need approval to adopt master meaning; incompatible signatures require
renaming or compatible mapping. If only the master defines a command, review
its declaration and signature before accepting. Compilation cannot prove meaning.

Missing definitions need one **complete declaration**, such as
`\newcommand{\missingSteps}{s}` or `\def\missingSteps{s}`, followed by a line
containing only `.`. Bare `s` is insufficient. The wizard provides examples for
the command/environment and shows master/donor declarations separately.

Static includes/images go under `texsuite-imports/<id>/`. BibTeX imports include
required entries, strings and crossref dependencies, keeping the master's style.
The master must already configure a bibliography before importing new citations.
biblatex/Biber is unsupported. Conflicting package options, dynamic/conditional
syntax, local styles, body-local definitions and unsupported macro dependencies
require manual adaptation.

Both the unchanged master baseline and proposed result compile in disposable
copies. BibTeX runs when needed, followed by LaTeX reruns. Existing unresolved
references/citations are reported separately; new ones block apply. History/undo
cover changed and created files. Externally changed imported files block undo.
When reopening a chapter, supply `--compile-main` again or open the master main
to locate project-level history. Both known roots participate in history/recovery;
undo chooses the latest completed operation. [Full merge contract](../opinions.md#smart-merge).

## Undo, history and recovery

| Task | Action |
| --- | --- |
| Undo the latest completed operation | Main-menu **7: Undo last change**, then `y` |
| Inspect history | **5: Settings → 9: Edit history** |
| Recover interrupted edit/undo | **5: Settings → 7: Recover interrupted edits**, then type `restore` |
| Show history in a terminal | `texsuite FILE --history` |
| Output only the JSON history array | `texsuite FILE --history --json` |

Undo restores the whole operation only if all saved outputs and original backups
match recorded hashes. External edits, damaged backups and pending recovery
block it before writes. Backups/history remain. Repeated undo can reach earlier
completed operations when hashes match. There is no redo or project-wide atomic transaction.

Private history lives under `.tex-suite/backups/<operation-id>/`.
`changes.json`, `recovery.properties` and backups precede source replacement;
failed history persistence blocks apply. Records include exact old/new text,
files, before/after hashes, UTF-8 byte ranges, intent, time and provenance/provider/model.
Decision provenance is `manual`, `openai-reviewed`, `openrouter-reviewed` or
`gemini-reviewed`. Before/after positions use one-based UTF-16 columns for VS Code links. Human
output includes escaped current/backup links, marking stale outputs and legacy
records without inventing missing details. Records are not sent to providers or telemetry.

Journal status is authoritative: `complete` means applied, `pending` needs
recovery, `restored` means originals restored. Rename intent plans under
`.tex-suite/plans/` may remain after failed compilation; they do not prove apply
and have no replay command. Uniform edits use v1 exact history, cleanup v2 with
rule provenance and merge v3. [Persistence guarantees](../opinions.md#persistence-and-recovery).

## Settings and command reference

Main-menu **5** opens Settings. Existing numbers are retained:

| Group | Choices |
| --- | --- |
| Editor | **2** toggle automatic opening; **3** browse for a macOS `.app`; **4** open now; **5** reset |
| AI | **8** provider settings/test; **10** project response cache |
| History and recovery | **9** history; **7** recover interrupted edits |
| Other | **1** diagnostics; **6** back (Enter also returns) |

Editor browsing validates the bundle/executable and saves its full path locally;
typed names are not accepted. Cancel keeps the previous choice. Enabling automatic
opening opens the current file immediately. Reset chooses the system text editor
with automatic opening on. Invalid/removed apps fall back to the system editor
while preserving the toggle; paths are rechecked before opening.

| Command or flag | Purpose |
| --- | --- |
| `texsuite [FILE]` | Open a saved `.tex` file, or prompt for one |
| `--help`, `-h` | Usage, options and examples; no workflow starts |
| `--version`, `-V` | Installed version |
| `--compile-main PATH` | Main for chapter compilation; does not widen edit scope |
| `--allow-no-compile` | Ordinary approved edits only when pdflatex is missing; never merge |
| `--history` | Local project history without an editing session |
| `--json` | With `--history`, emit only the JSON array |
| `--debug` | Snapshot, excluded/review rows, IDs/reasons and retrieved context |
| `--no-debug` | Concise output (default); overrides `--debug` |

Normal output shows actionable candidates/counts. Internal metadata and replacement
skipped rows stay in debug; exact API payloads and required warnings remain visible.
Main-menu **8**, `help` or `?` opens task help. Decision prompts marked `?=help`
repeat after help; literal source inputs keep `?` and `help` as text.
