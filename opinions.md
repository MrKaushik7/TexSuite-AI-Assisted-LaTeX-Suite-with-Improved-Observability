# Safety and supported syntax

[Home](README.md) · [First edit](docs/getting-started.md) · [Task guide](docs/user-guide.md)

This reference describes the implemented safety boundary and why an operation may
refuse a match. Start with the task guide for steps; use this page for exact guarantees
and limits. It ships beside the README and JAR and must change when behavior changes.

- [Approval and meaning](#approval-and-meaning)
- [Protected syntax and regions](#protected-syntax-and-regions)
- [Saved source, roots and scope](#saved-source-roots-and-scope)
- [Context selection and budgets](#context-selection-and-budgets)
- [Compilation and TeX installation](#compilation-and-tex-installation)
- [Persistence and recovery](#persistence-and-recovery)
- [Deterministic sanitation](#deterministic-sanitation)
- [Smart merge](#smart-merge)

## Approval and meaning

AI classification supports mathematical rename and eligible literal text/source replacement. It uses the stated meaning/purpose and preserves the exact replacement input. Normal AI mode selects REPLACE decisions and presents one batch summary/explicit `apply`, with optional `diff`, individual `manual` review or cancellation. KEEP and needs-human-review are skipped automatically, as are candidates whose complete context does not fit the budget. This avoids dozens of mandatory occurrence prompts, but approving a batch still accepts semantic risk: compilation cannot prove the intended meaning. Confidence is model-reported and uncalibrated; it is not an acceptance threshold.

Manual selection and error fallback retain occurrence-by-occurrence y/N review and an accepted-only diff/final apply. Optional AI-assisted manual review leads each excerpt with the action banner and repeats it in the prompt. AI KEEP explicitly asks for an override; AI REPLACE asks to accept replacement. `y` always replaces and Enter/`n` preserves the original. A human may select an uncertain model decision only when the scanner already allows that occurrence; protected/uncertain scanner regions remain unavailable.

AI cannot authorize writes or widen scope. The batch summary is the mandatory preview; the full diff is optional in AI mode.

AI setup requires explicit provider selection, then offers gpt-6-luna for OpenAI, gemini-2.5-flash for Gemini and nvidia/nemotron-3-ultra-550b-a55b:free for OpenRouter. Enter accepts that model; custom IDs remain available and off disables AI. Nemotron defaults to prompt JSON; other models default to provider-enforced JSON Schema where supported. Setup choices still require the JSON decision contract. Settings save globally by default, across sessions and projects, with no transient session or project profile.

The private file is `~/Library/Application Support/TexSuite/ai.json` on macOS or `~/.local/state/texsuite/ai.json` elsewhere; saved fields override environment defaults. Legacy project AI profiles are ignored rather than silently migrated. Startup offers optional setup when there is no configured model or explicit saved disable; off stores an empty model and remembers manual-only use. Settings 8 can always change the profile, and setup makes no API request.

Failed persistence leaves the previous profile in use. Credentials come only from a named environment variable, default `OPENAI_API_KEY`, `OPENROUTER_API_KEY` or `GEMINI_API_KEY` for the selected provider; global settings store its name, never its value. Settings explains that this field accepts the variable name, gives the provider's default as an example, and offers Enter to use it.

Set the variable's value in the launch terminal before starting TexSuite; pasted API key values are not configuration input. Each provider endpoint is fixed; Gemini requires a bare model ID and uses a key header rather than a query parameter. Saving a profile preserves other provider profiles. Every remote request requires approval after a destination/model/cost warning and exact payload preview; Settings never uploads your document implicitly.

The synthetic provider test also requires consent and may cost money.

All providers use no prior response or conversation state, no tools and no streaming. Native OpenAI also uses `store:false`. This is a request setting, not a promise about all provider retention policies. Source instructions are untrusted evidence; prompts cannot guarantee immunity to prompt injection or semantic mistakes. The response must be a bare JSON object using the exact `action` field; Markdown fences and a `decision` alias are rejected rather than silently normalized.

Strict independent checks require a complete decision set for exactly the supplied IDs, allowed actions, bounded single-line reasons and finite confidence in [0,1]. Non-200 responses, refusal, incomplete output, timeout or invalid decisions discard all AI suggestions for the operation and offer manual review/cancellation, without automatic retries. One approved schema repair can send allowed IDs and previous output only; previous output may itself repeat source.

It never reattaches original excerpts. Local providers remain future scope. OpenRouter defaults to schema-capable routing and supplies one explicit model, without a paid alternate model; unsupported schema routes fail safely. A separately configured `prompt_json` mode places the schema in the system instruction without `response_format`; it retains strict local validation but has no upstream formatting guarantee.

There is no automatic downgrade on errors. The selected [Nemotron Ultra 3 free endpoint](https://openrouter.ai/nvidia/nemotron-3-ultra-550b-a55b:free) needs prompt JSON and documents logging submissions. Free labels and keys do not guarantee quota or free billing for every account. Gemini free-tier submissions may be used to improve Google products; see [provider pricing and data treatment](https://ai.google.dev/gemini-api/docs/pricing#gemini-2.5-flash). Use synthetic source for initial provider tests.

No provider result proves semantic correctness.

Enter `back` at a prompt to cancel the current flow and return to the main menu without applying pending source edits. Literal source/replacement prompts reserve `back` and `quit` alone while preserving other whitespace. Already approved remote requests or saved settings are not undone by subsequent navigation.

Normal output lists the rename request, selectable candidates and summary counts, or one actionable empty-result message; full paths/meaning, lexical/protection notes, excluded/review inventories, IDs and retrieved context require `--debug`. `--no-debug` overrides it. Replace text similarly hides skipped-match rows by default. Necessary safety/review messages and exact outgoing API payload consent remain visible.

Search is case-sensitive and literal. Mathematical rename only offers lexical occurrences classified as mathematical and safe; it cannot prove that all matching symbols have the same meaning. Replace text provides a general literal operation with a selected source region. Neither operation interprets regular expressions or expands macros.

## Protected syntax and regions

The default Replace text region is document text. It excludes preamble declarations, comments, math, commands and uncertain branches to avoid changing TeX structure accidentally. LaTeX-source and explicit line-range regions permit more ordinary source values while retaining structural guards. Broader regions still do not authorize changing arbitrary commands, include paths or macro definitions.

| Example | Current behavior and available choice |
|---|---|
| `pifont` → `font` in a preamble package argument | Default document text excludes it; the program offers explicit source rerouting. Compilation may reject the new package name. |
| `0.5` → `0.75` in `\tikzset{>={Latex[width=0.5mm,length=1mm]}}` | Outside mathematical context; rename offers explicit rerouting to source replacement. Only eligible source values become selectable. |
| `\alpha` → `\beta` in `$\alpha$` or `\[\alpha_1\]` | Supported whole standard Greek symbols in known math are eligible; scripts remain outside the edit. Prefixes such as `\alphabet`, structural/unknown commands, comments, verbatim and definitions remain protected. |
| A match in a comment, verbatim block or uncertain conditional | It is excluded or requires manual source review. Changing region does not remove these guards. |

Sanitation and smart merge are implemented as reviewed deterministic workflows; their supported subsets and refusal rules are described below.

AI classifications are cached per project in private `.tex-suite/cache/`, enabled by default.
Exact canonical request identity includes operation/scope/region, candidates/context hashes,
literal strings including whitespace, actual provider payload, prompt/schema and endpoint/model.
Credential values are not part of stored records. Decision explanations may quote source and
remain private local data. An unchanged preview can be reused after cancellation/restart;
cancellation preserves source and history but may leave a useful validated cache entry.
Hits revalidate decisions and saved-source freshness and require fresh apply approval and compilation.
Settings 10 persists enable/disable and clears only cached result files; selection 3 regenerates
with renewed remote consent.

The provider probe bypasses the cache. Invalid cache entries are
misses, and write failure does not resend a successful request. Storage admits 256 entries of
128 KiB each; full caches skip new entries instead of silently evicting results. These are
per-write bounds for ordinary CLI use, not a multi-process storage quota or an external-writer lock.
No model quality or semantic correctness follows from a cache hit.

## Saved source, roots and scope

TexSuite reads strict UTF-8 from saved files. Unsaved editor buffers are unavailable. Positions use zero-based byte ranges and one-based lines and Unicode-code-point columns; a tab counts as one column and CRLF counts as one newline.

File scope is the default. Project scope follows the selected file's forward, statically resolvable include closure; it does not mean every `.tex` file in a folder. Include and asset paths resolve from the selected project root, which is normally the selected file's parent. Custom search paths, dynamic includes and macro expansion are outside this subset.

Paths cannot escape that root. A chapter does not automatically reveal its containing main file or earlier definitions in that main file. Select the main for project context, or supply the separate main when the compilation prompt asks; `--compile-main` controls compilation rather than rename context.

Context reads never widen edit scope. A file-only rename can read its forward included files for supporting definitions while only the selected file remains editable. Conditional dependencies cannot establish certain macro context. Missing, dynamic or structurally uncertain context is reported for manual review instead of guessed.

## Context selection and budgets

The retriever preserves exact source slices and hashes. It selects the enclosing equation and blank-line-delimited paragraph, plus the nearest preceding or enclosing supported section, definition, theorem-like environment and proof in the same file. Supported theorem-like names are `theorem`, `lemma`, `proposition`, `corollary`, `claim`, `remark` and `example`; starred variants are recognized. Custom environment names are not inferred. Referenced unconditional local macro definitions are followed recursively across definitely reachable files. Multiple definitions are shown together because the retriever does not simulate TeX execution order or scoping.

Each deterministic batch has at most eight candidates and 24,000 Unicode source characters. Overlapping slices merge within a batch. Totals count the actual text of every batch, including context repeated across batches. The native adapter requests at most 1,500 output tokens, limits encoded requests to 256 KiB and responses to 128 KiB, and enforces the configured deadline over headers and complete body.

Reasoning tokens can consume the output budget and cause incomplete output. Complete context that exceeds the source limit is skipped in AI batch mode and available through explicit manual review; it is not silently truncated. Character limits do not measure tokenizer input tokens or request-envelope overhead.

Supporting definitions and edit sources are checked again before review/apply and after compilation. Any detected source or include-closure change invalidates prior decisions. These checks cannot prevent an external editor writing between the last check and replacement.

## Compilation and TeX installation

Ordinary apply requires a successful `pdflatex` run on a disposable project copy. The program uses a timeout and disables shell escape. The copy is limited to 512 files, 16 MiB per file and 64 MiB total; hidden entries and `target` are excluded and symbolic links are refused. One compilation pass catches many syntax/package errors, but does not prove semantic correctness, settle all cross-references or reproduce every custom build. Review the final document yourself.

TexSuite does not bundle, download or automatically install BasicTeX. The installer recommends it when `pdflatex` is missing; any existing compatible `pdflatex` installation can be used. The executable still needs TeX formats, packages and fonts for the document, so shipping that binary alone would not provide general compilation. Missing compiler alone permits explicit `--allow-no-compile`; compilation failure or timeout still blocks apply. Reading and reviewing require no TeX installation.

## Persistence and recovery

Updates use backups, a pending recovery journal and atomic replacement of each file. Failed multi-file updates attempt rollback, and interrupted edits can be restored through Settings after checking for external changes. This is recoverable file editing, not a full ACID database transaction: there is no project-wide atomic commit, writer lock or explicit disk-flush durability protocol.

Approved mathematical-rename intent is stored under `.tex-suite/plans/`; exact details, backups and authoritative journals are under `.tex-suite/backups/<operation-id>/`. Intent can be persisted before a later compilation failure, so a plan does not prove successful application. Version 1 (uniform rename/replacement) or version 2 (heterogeneous sanitation with rule provenance) `changes.json` is saved atomically with private permissions before the first source replacement; failed history persistence blocks all writes.

It records exact intent, decision provenance/provider/model, source hashes, old/new text, before/after UTF-8 ranges and one-based UTF-16 line columns. This column convention makes VS Code links accurate even after supplementary Unicode characters. The journal alone supplies pending/complete/restored status; legacy journals do not gain invented exact details. No plan replay exists.

Settings Edit history and `--history`/`--history --json` expose these private source-bearing records locally. Current editor links use after positions for applied operations and before positions after restoration; saved-hash mismatches are marked stale. Original links point into retained backups. History validates details against journal hashes and exact backups before displaying them. Manual edits to history may make it unreadable; use the retained journals/backups for recovery. Records are not provider context, telemetry or Git-tracked data.

Main-menu Undo last change confirms `[y/N]` and restores the whole latest completed operation. Every target must still have its post-edit hash, and every backup must match its original hash before any source write. Later external edits, damaged backups and pending recovery refuse revert; the program never guesses a three-way merge. New journals carry a completion timestamp for ordering; legacy journals use their saved modification time.

A pending revert can be resumed through existing Settings recovery, accepting only original/post-edit bytes, and backups/history stay intact after restoration. There is no redo. Hash checks leave the same external-writer race as ordinary apply; no project-wide atomicity or disk-flush durability is claimed.

## Deterministic sanitation

Sanitation is selected-file-only and offline. When a separate compilation main is known, its static closure is also checked for hazards, completeness and unconditional reachability of the selected file; it does not become editable. This check repeats at final plan validation, including when the main was chosen after preview. Without a supplied main, no reverse-main discovery is assumed. Automatic cleanup is restricted to unconditional plain prose under standard TeX whitespace rules: trailing ASCII spaces/tabs and an explicitly selected complete eligible EOL conversion. Command-bearing lines, grouped arguments, preamble, math, definitions, comments, verbatim, conditionals, unknown regions and blank lines stay unchanged. Unsupported catcode/space/endline manipulation, opaque command-bearing definitions, dynamic constructs, unknown environments or uncertain structure/dependencies withhold fixes for the closure. Simple command-free literal macro bodies are protected without disabling other prose. Hidden dependencies or arbitrary TeX expansion are not proved safe by lexical inspection or compilation.

Unicode findings and literal duplicate/conflicting package options are advisory only. No syntax repair, package removal, reflow or blanket Unicode correction. Both selection and final apply require explicit user action; whitespace changes are visibly marked. EOL normalization is all-or-nothing, final-newline presence is preserved, and the shared engine rechecks patch eligibility and complete terminator groups before writing. Maximum 200 patches; oversized rule groups are withheld with an actionable explanation. Version 1 history validation remains exact; version 2 reconstructs heterogeneous output from verified backups and journal hashes and validates sanitation intent/rule shapes.

## Smart merge

Master meaning is a recommended explicit conflict choice, not an implicit override. Identical supported macro definitions can be reused automatically only when their declaration kind, signature, body and helper dependencies agree. A differently defined compatible macro requires approval; incompatible signatures require rename/mapping/manual repair. When the donor has no definition, adopting a master definition requires explicit signature review; successful compilation cannot prove that arguments express the intended meaning. Entered missing definitions are parsed and dependency-checked, never generated by a model. Unknown source commands may be package-provided; compiler validation decides whether the assembled program resolves them.

Only selected donor content and reachable supported dependencies are imported. Existing master bytes change only at approved insertion/resource-list spans; original donor bytes remain exact. Import paths are under texsuite-imports/<id>. File creation is published without overwriting an existing path. Version 3 history records exact binary-safe before/after bytes, hashes and decisions; version 2 recovery journals add expected-absence entries. Legacy v1/v2 edit history remains supported. Whole-operation undo removes created files only when their recorded bytes still match. Empty import directories may remain after undo.

A merge requires pdflatex and, when needed, BibTeX. It validates an unchanged master baseline and the proposed result on disposable copies, with bounded passes and no shell escape. There is no merge no-compile override. This does not establish semantic equivalence, mathematical correctness, arbitrary package compatibility or identical layout. Unsupported dynamic/conditional constructs, complex definitions, path/reference-bearing imported macro bodies or optional defaults, local styles and Biber require manual adaptation.

BibTeX reuse checks referenced entries and strings as well as the entry text. Imported string definitions precede uses, crossref children precede parents, and key changes touch parsed crossref values rather than arbitrary bibliography prose. Conflicting string definitions still require manual adaptation.
