# TexSuite opinions and assumptions

These describe the implemented program. They explain why an operation may refuse a match or ask for review. This file ships beside the README and JAR; future behavior must update it when it ships.

## Approval and meaning

Every edit currently needs occurrence-by-occurrence approval and a final accepted-only diff followed by `apply`. Optional OpenAI, OpenRouter or Gemini classification uses the stated mathematical meaning to suggest replace, keep or needs-human-review; you can correct each eligible suggestion. Recommendations appear in separate banners and at the answer prompt. AI KEEP explicitly asks to override KEEP; AI REPLACE asks to accept replacement. `y` always means replace and Enter/`n` preserves the original, even for AI REPLACE. The displayed replacement beside KEEP is conditional on overriding it. Confidence is model-reported and uncalibrated. AI cannot authorize edits, widen scope, select protected occurrences or change your literal replacement. Large reviews can be tedious; batch AI approval is planned but unavailable. Replace text remains manual. The replacement is the literal LaTeX source you enter, not a generated rendering.

AI has no default model: you must configure an explicit provider and model ID returning the required JSON decision contract (provider-enforced structured output where supported). Profile precedence is session, project, environment. Credentials come only from a named environment variable, default `OPENAI_API_KEY`, `OPENROUTER_API_KEY` or `GEMINI_API_KEY` for the selected provider; project settings store its name, never its value. Settings explains that this field accepts the variable name, gives the provider's default as an example, and offers Enter to use it. Set the variable's value in the launch terminal before starting TexSuite; pasted API key values are not configuration input. Each provider endpoint is fixed; Gemini requires a bare model ID and uses a key header rather than a query parameter. Saving a profile preserves other provider profiles. Every remote request requires approval after a destination/model/cost warning and exact payload preview; Settings never uploads your document implicitly. The synthetic provider test also requires consent and may cost money.

All providers use no prior response or conversation state, no tools and no streaming. Native OpenAI also uses `store:false`. This is a request setting, not a promise about all provider retention policies. Source instructions are untrusted evidence; prompts cannot guarantee immunity to prompt injection or semantic mistakes. The response must be a bare JSON object using the exact `action` field; Markdown fences and a `decision` alias are rejected rather than silently normalized. Strict independent checks require a complete decision set for exactly the supplied IDs, allowed actions, bounded single-line reasons and finite confidence in [0,1]. Non-200 responses, refusal, incomplete output, timeout or invalid decisions discard all AI suggestions for the operation and offer manual review/cancellation, without automatic retries. One approved schema repair can send allowed IDs and previous output only; previous output may itself repeat source. It never reattaches original excerpts. Local providers remain future scope. OpenRouter defaults to schema-capable routing and supplies one explicit model, without a paid alternate model; unsupported schema routes fail safely. A separately configured `prompt_json` mode places the schema in the system instruction without `response_format`; it retains strict local validation but has no upstream formatting guarantee. There is no automatic downgrade on errors. The selected [Nemotron Ultra 3 free endpoint](https://openrouter.ai/nvidia/nemotron-3-ultra-550b-a55b:free) needs prompt JSON and documents logging submissions. Free labels and keys do not guarantee quota or free billing for every account. Gemini free-tier submissions may be used to improve Google products; see [provider pricing and data treatment](https://ai.google.dev/gemini-api/docs/pricing#gemini-2.5-flash). Use synthetic source for initial provider tests. No provider result proves semantic correctness.

Normal output lists selectable rename candidates and summary counts; excluded/review inventories, IDs and retrieved context require `--debug`. `--no-debug` overrides it. Replace text similarly hides skipped-match rows by default. Necessary safety/review messages and exact outgoing API payload consent remain visible.

Search is case-sensitive and literal. Mathematical rename only offers lexical occurrences classified as mathematical and safe; it cannot prove that all matching symbols have the same meaning. Replace text provides a general literal operation with a selected source region. Neither operation interprets regular expressions or expands macros.

## Protected syntax and regions

The default Replace text region is document text. It excludes preamble declarations, comments, math, commands and uncertain branches to avoid changing TeX structure accidentally. LaTeX-source and explicit line-range regions permit more ordinary source values while retaining structural guards. Broader regions still do not authorize changing arbitrary commands, include paths or macro definitions.

| Example | Current behavior and available choice |
|---|---|
| `pifont` → `font` in a preamble package argument | Default document text excludes it. Choose LaTeX source and review the match; compilation may reject the new package name. |
| `0.5` → `0.75` in `\tikzset{>={Latex[width=0.5mm,length=1mm]}}` | This source value is outside mathematical rename. Use Replace text with LaTeX source. |
| `\alpha` → `\beta` in `$\alpha$` or `\[\alpha_1\]` | The current scanner protects control sequences even inside math, so mathematical rename excludes them. Replace text retains that protection. Edit manually until explicit math-command support ships. |
| A match in a comment, verbatim block or uncertain conditional | It is excluded or requires manual source review. Changing region does not remove these guards. |

Sanitize and merge are menu placeholders and do not modify documents.

## Saved source, roots and scope

TexSuite reads strict UTF-8 from saved files. Unsaved editor buffers are unavailable. Positions use zero-based byte ranges and one-based lines and Unicode-code-point columns; a tab counts as one column and CRLF counts as one newline.

File scope is the default. Project scope follows the selected file's forward, statically resolvable include closure; it does not mean every `.tex` file in a folder. Include and asset paths resolve from the selected project root, which is normally the selected file's parent. Custom search paths, dynamic includes and macro expansion are outside this subset. Paths cannot escape that root. A chapter does not automatically reveal its containing main file or earlier definitions in that main file. Select the main for project context, or supply the separate main when the compilation prompt asks; `--compile-main` controls compilation rather than rename context.

Context reads never widen edit scope. A file-only rename can read its forward included files for supporting definitions while only the selected file remains editable. Conditional dependencies cannot establish certain macro context. Missing, dynamic or structurally uncertain context is reported for manual review instead of guessed.

## Context selection and budgets

The retriever preserves exact source slices and hashes. It selects the enclosing equation and blank-line-delimited paragraph, plus the nearest preceding or enclosing supported section, definition, theorem-like environment and proof in the same file. Supported theorem-like names are `theorem`, `lemma`, `proposition`, `corollary`, `claim`, `remark` and `example`; starred variants are recognized. Custom environment names are not inferred. Referenced unconditional local macro definitions are followed recursively across definitely reachable files. Multiple definitions are shown together because the retriever does not simulate TeX execution order or scoping.

Each deterministic batch has at most eight candidates and 24,000 Unicode source characters. Overlapping slices merge within a batch. Totals count the actual text of every batch, including context repeated across batches. The native adapter requests at most 1,500 output tokens, limits encoded requests to 256 KiB and responses to 128 KiB, and enforces the configured deadline over headers and complete body. Reasoning tokens can consume the output budget and cause incomplete output. Complete context that exceeds the source limit is reported for manual review; it is not silently truncated. Character limits do not measure tokenizer input tokens or request-envelope overhead.

Supporting definitions and edit sources are checked again before review/apply and after compilation. Any detected source or include-closure change invalidates prior decisions. These checks cannot prevent an external editor writing between the last check and replacement.

## Compilation and TeX installation

Ordinary apply requires a successful `pdflatex` run on a disposable project copy. The program uses a timeout and disables shell escape. The copy is limited to 512 files, 16 MiB per file and 64 MiB total; hidden entries and `target` are excluded and symbolic links are refused. One compilation pass catches many syntax/package errors, but does not prove semantic correctness, settle all cross-references or reproduce every custom build. Review the final document yourself.

TexSuite does not bundle, download or automatically install BasicTeX. The installer recommends it when `pdflatex` is missing; any existing compatible `pdflatex` installation can be used. The executable still needs TeX formats, packages and fonts for the document, so shipping that binary alone would not provide general compilation. Missing compiler alone permits explicit `--allow-no-compile`; compilation failure or timeout still blocks apply. Reading and reviewing require no TeX installation.

## Persistence and recovery

Updates use backups, a pending recovery journal and atomic replacement of each file. Failed multi-file updates attempt rollback, and interrupted edits can be restored through Settings after checking for external changes. This is recoverable file editing, not a full ACID database transaction: there is no project-wide atomic commit, writer lock or explicit disk-flush durability protocol.

Approved mathematical-rename intent is stored under `.tex-suite/plans/`; backups and recovery records are under `.tex-suite/backups/`. Intent can be persisted before a later compilation failure, so a plan does not prove successful application. There is no plan replay command or complete linked change-history view yet. These local records can contain original and replacement source; treat them as private project data. Positions and hashes describe the saved revision and can become stale after subsequent edits.

Revert last change is planned for the next slice, alongside exact edit history. The current recovery menu handles interrupted applies only. A future revert must confirm explicitly and verify all current post-edit hashes before restoring a completed operation, so later editor changes are preserved.
