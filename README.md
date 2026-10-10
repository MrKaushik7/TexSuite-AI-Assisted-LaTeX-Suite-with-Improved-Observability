# TexSuite

Read [opinions.md](opinions.md) for the program's assumptions, supported syntax, approval rules and limits before editing a project.

## Build

```sh
./mvnw clean verify
```

## Install

```sh
./scripts/install.sh
export PATH="$HOME/.local/bin:$PATH"
```

The JAR, this README and `opinions.md` are installed at `~/Applications/TexSuite/`; the command is at `~/.local/bin/texsuite`.

The installer checks for `pdflatex` on absolute `PATH` entries and in `/Library/TeX/texbin`. If it is missing, it recommends [BasicTeX](https://www.tug.org/mactex/morepackages.html), a smaller TeX installation, and an interactive install offers to open its official download page. Download BasicTeX and run its macOS installer, then press Enter for one availability recheck or type `skip` to finish. You can also rerun `scripts/install.sh` later to check again. Noninteractive installs print the instructions without prompting.

Some documents need extra classes, fonts or packages beyond BasicTeX; install missing packages with its `tlmgr` package manager. [Full MacTeX](https://www.tug.org/mactex/mactex-download.html) is optional for broader package coverage. An existing installation providing `pdflatex` can also be reused. Finding a compiler does not prove a document's packages are available; compilation is checked when applying an edit. Reading and inspecting documents requires no TeX installation.

## Run

```sh
texsuite --help
texsuite --version
texsuite [FILE]
texsuite --debug [FILE]
```

Enter `back` at a prompt to cancel the current flow and return to the main menu. This works while entering an operation, reviewing occurrences, approving an API request or approving a batch. No pending source edits are applied. At literal source/replacement prompts, `back` and `quit` alone are reserved; other input preserves whitespace.

Choose **Rename a mathematical symbol** to enter literal LaTeX source, intended meaning, replacement and file/project scope. Rendering depends on the replacement's TeX syntax. Whole standard Greek commands such as `\alpha` → `\beta` work inside known math, preserving scripts; command prefixes, comments, verbatim, definitions and uncertain scanner regions stay protected. With a configured provider, choose AI or manual selection. AI proposes replace/keep/needs-human-review using your meaning. The normal AI flow shows a batch summary with selected/kept/uncertain/protected counts, per-file totals, scope, literal replacement and compilation target. Choose `diff` to inspect the full diff, `manual` to review individual suggestions, `apply` to approve the selected batch, or Enter/`cancel` to stop. Only AI REPLACE is selected automatically; AI KEEP and uncertainty stay unchanged. This explicit `apply` is the single batch approval, after separate consent for every remote request.

Manual review still asks about every eligible occurrence. AI recommendations lead their excerpts and repeat in prompts: AI KEEP explicitly asks to override KEEP, AI REPLACE asks to accept replacement, and uncertain decisions ask for manual review. `y` replaces; Enter/`n` preserves source. Manual selection finishes with an accepted-only diff and `apply`. Quit, EOF, cancellation and empty selections leave source unchanged. Stale source/context requires fresh decisions and remote consent.

Normal rename output shows the request, summary counts and eligible candidate excerpts. With no eligible matches, it gives one actionable message. Full paths/meaning, excluded/review rows and reasons, internal IDs, snapshot metadata and retrieved-context listings require `--debug`; `--no-debug` overrides it. Matches are lexical findings: eligibility does not prove meaning, and uncertain/protected scanner regions cannot be selected. Replace text also hides skipped-match rows by default. Exact API payload preview and necessary review/validation warnings remain visible.

Rename retrieves source context (listed with `--debug` and included in an approved API request): the enclosing equation and blank-line paragraph, nearest supported section/definition/theorem/proof, and referenced local macro definitions. Static included files may supply context without becoming editable. Batches contain at most eight candidates and 24,000 Unicode source characters, with a requested 1,500-output-token limit; oversized, incomplete or structurally uncertain context requires manual review without truncation. Supporting source changes require fresh review, including changes detected after compilation.

At interactive startup, an unconfigured program offers AI setup or manual use and explains that **Settings → 8** can change it anytime. Choose **1: Change AI settings** to select **1 OpenAI**, **2 OpenRouter**, or **3 Gemini**, then enter an explicit model ID, key environment-variable name and timeout (default 60 seconds, range 1–300). Settings save globally immediately and apply across sessions and projects. No session/project choice or separate save step is needed. An empty model explicitly disables AI and remembers that choice, so startup does not keep asking. Setup makes no API request; **2: Test provider** sends fixed synthetic text only after separate consent. No model is chosen automatically. For OpenRouter, also choose JSON Schema (default) or explicit prompt JSON for models without `response_format` support.

The private profile is `~/Library/Application Support/TexSuite/ai.json` on macOS, or `~/.local/state/texsuite/ai.json` elsewhere. It stores the selected provider and each saved provider profile, including the key variable's name, never the key value. Global settings override environment defaults: `TEXSUITE_MODEL_PROVIDER` (`openai`, `openrouter`, `gemini`) and `TEXSUITE_<PROVIDER>_MODEL`, `_KEY_ENV`, `_TIMEOUT`, `_OUTPUT_MODE` (`json_schema` or OpenRouter-only `prompt_json`). A configured environment profile also skips the startup offer. Legacy project `.tex-suite/config.json` AI profiles are ignored; configure once in Settings to replace them with global preferences. Invalid or unsavable settings are reported without adopting an unsaved profile.

At the key-reference prompt, enter the variable's **name**, such as `GEMINI_API_KEY`, rather than the API key itself. Press Enter to use the selected provider's default name. Set that variable to your key in the terminal before launching TexSuite; the running program inherits that terminal's environment.

| Provider | Fixed API | Explicit POC model | Key reference |
| --- | --- | --- | --- |
| OpenAI | `https://api.openai.com/v1/responses` | User-selected model | `OPENAI_API_KEY` |
| OpenRouter | `https://openrouter.ai/api/v1/chat/completions` | `nvidia/nemotron-3-ultra-550b-a55b:free` | `OPENROUTER_API_KEY` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent` | `gemini-2.5-flash` | `GEMINI_API_KEY` |

These are example explicit profiles, not defaults. OpenRouter schema mode requests strict JSON Schema with `require_parameters:true`; unsupported routes fail without an automatic downgrade. The selected Nemotron Ultra 3 free endpoint [does not support `response_format`](https://openrouter.ai/nvidia/nemotron-3-ultra-550b-a55b:free), so explicitly select **prompt JSON** for that model: the schema goes in the system instruction, with the same strict local validation but no provider formatting guarantee. This endpoint documents logging submitted data; use synthetic text for the proof of concept. TexSuite supplies a single model, without an alternate paid model. Native Gemini sends its key in `x-goog-api-key`, never the URL, and requests JSON Schema through `generationConfig.responseMimeType` and `responseJsonSchema`, matching [Google’s Gemini 2.5 SDK example](https://googleapis.github.io/python-genai/#json-response-schema). Free access depends on model availability, account tier and quota; TexSuite cannot impose free billing on a paid Gemini account. Check [OpenRouter's model listing](https://openrouter.ai/nvidia/nemotron-3-ultra-550b-a55b:free) and [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-2.5-flash). Start with synthetic text; Google's free tier may use submitted data to improve products.

Every request shows its model, destination and exact outgoing JSON, excluding the key, before asking permission. Source is sent only after `y`. All providers use bounded non-streaming requests without tools or conversation history; native OpenAI also uses `store:false`. This does not override provider retention policies. All batches must validate before the batch proposal or manual review is shown. HTTP errors, refusal, timeout, incomplete or invalid output discard the operation's AI suggestions and offer manual review or cancellation. A completed malformed classification may offer one schema repair: its separately approved payload contains only allowed IDs and previous output, which may repeat source, without reattaching original excerpts. Local providers remain future scope. Both supported replacement operations offer AI batch selection and manual fallback.

Approved rename intent is stored in `.tex-suite/plans/` as versioned JSON with request, source fingerprint, accepted occurrence IDs/ranges/hashes, and `manual` or provider-specific reviewed provenance (`openai-reviewed`, `openrouter-reviewed`, `gemini-reviewed`). These private local records can contain source text. They record approval, not successful application: a later compilation failure can leave a plan without changing source. There is no plan replay command. For a compilable example, define `\newcommand{\numElements}{n}` and rename selected `n` uses to `\numElements`; `\number` is already a TeX primitive.

Choose **Replace text** for case-sensitive literal replacement. Select document text (prose inside the document), LaTeX source (eligible values including the preamble and math), or a file line range. Comments, verbatim, definitions, command names, metadata and uncertain branches stay protected in every region. Region-only exclusions offer explicit rerouting; declining keeps the original region. Mathematical rename can also offer source replacement for values outside math, such as TikZ `0.5` → `0.75`. A package argument such as `pifont` → `font` needs source mode and must still compile.

With AI configured, state the intended meaning/purpose (Enter explicitly means all eligible literal matches), then choose AI batch selection or manual review. Manual review approves each occurrence with `y` and finishes with diff/`apply`; AI uses the batch actions above. The replacement always remains your literal input. Failed AI classification discards every AI result for the operation; manual fallback retains its individual checks.

Apply validates a disposable project copy with `pdflatex`, then rechecks saved files, backs up originals, and replaces files atomically. If pdflatex is unavailable, `--allow-no-compile` explicitly permits uncompiled edits; compiler failure or timeout still blocks writes. For a chapter, TexSuite offers its `% !TEX root = main.tex` hint for confirmation, or asks for a main-file path relative to the displayed project root. Enter at the path prompt cancels. The chosen main must contain every accepted edit through unconditional static includes. `texsuite --compile-main path/to/main.tex path/to/chapter.tex` overrides the hint and also supports a containing project above the chapter folder. Compilation reads that project without expanding edit scope. Failure reports include the selected main and a bounded compiler error summary when available. Copies are limited to 512 files, 16 MiB per file and 64 MiB total; hidden entries and `target` are excluded, and symbolic links are refused.

Settings controls automatic opening in the system text editor (macOS), a browsed application, and **Recover interrupted edits** (option 7). Choose **5 → 3: Browse for editor…** to select an installed `.app`; typed application names are not accepted. Cancel keeps the previous choice. TexSuite validates the bundle and executable, saves its full path locally, and checks it again before opening. Turning automatic opening on immediately opens the current file in the preferred editor. Option 4 opens the current file now; option 5 resets to the system editor with automatic opening enabled. Invalid old application names or removed apps fall back to the system editor while preserving the automatic-opening toggle. Recovery requires `restore` and refuses to overwrite unexpected external changes. Backups and recovery records live under the selected file's project root in `.tex-suite/backups/`.


Every apply writes private versioned `changes.json` beside `recovery.properties` and original backups under `.tex-suite/backups/<operation-id>/` **before the first saved source is replaced**. History includes time, operation/intent, decision provenance/provider/model, relative files, before/after hashes, exact old/new text, UTF-8 byte ranges, and before/after positions. Position columns are one-based UTF-16 units for VS Code links. A history persistence failure blocks apply. Journal status is authoritative: `complete` means applied, `pending` requires recovery, and `restored` means originals were restored. An approved plan or failed compilation is not an applied change.

Use **Settings → 9: Edit history**, `texsuite FILE --history`, or `texsuite FILE --history --json`. JSON mode emits only the history array on stdout; human mode includes plain paths plus escaped VS Code current/backup position links. Current links are labelled stale if the saved hash differs; legacy journals are labelled without inventing missing exact edits. History is local private source data, not sent to providers or telemetry.

Choose **7: Revert last change** from the main menu, then confirm `[y/N]`. It restores the whole last completed operation only when every current file still matches its post-edit hash and every backup matches the original hash. Later external edits, damaged backups and pending recovery block revert before any source write. Backups and history remain. An interrupted revert becomes a pending journal; use **Settings → 7** and type `restore` to finish restoring originals. Repeated revert can walk earlier completed operations when their hashes still match. There is no redo or project-wide atomic transaction.

### Coverage, generated cases, and mutation testing

Run `./mvnw clean verify` for JUnit tests, jqwik properties, and JaCoCo coverage.
Open `target/site/jacoco/index.html`; `jacoco.xml` provides machine-readable line
and branch counts. Coverage starts as a baseline, without a percentage gate.
Inspect uncovered safety paths rather than adding tests solely to raise a score.
JaCoCo does not count exception handlers as branches: commit, rollback, and
provider failures still need explicit scenarios and assertions about unchanged files.
Run `sh scripts/test-install.sh` separately for installed-launcher diagnostics.

`EditingPropertiesTest` checks empty-selection rejection without writes, unchanged
segments around valid replacements, exact recovery after interruption, and Unicode
candidate byte ranges. It runs 200 cases per editing/offset property and 50 recovery
cases, using synthetic files in a fresh temporary directory for each trial.
The tests use jqwik's JUnit Platform engine, not Jupiter's `@TempDir` lifecycle.
JUnit regression tests use Arrange–Act–Assert and compare externally visible
results; one rejected operation can require assertions on several files.

On a property failure, preserve the printed seed and shrunk sample from
`target/surefire-reports/` and the replay database `target/jqwik-database` before
running `clean`. Re-run with
`./mvnw -Dtest=EditingPropertiesTest test`; jqwik retries the stored failure first.
For replay on another machine, temporarily put the reported seed in the failing
method's `@Property(seed = "...")` annotation with the same generator code.
After fixing a defect, retain the minimized example as a deterministic JUnit
regression and remove the temporary seed. Do not retry unexplained failures away.

Run focused mutation analysis separately:

```sh
./mvnw --batch-mode -Pmutation -Djacoco.skip=true \
  test-compile org.pitest:pitest-maven:mutationCoverage
```

PIT targets `TextEditPlan`, `TextRecovery`, `DocumentInputValidator`, and
`ModelProtocol` (including nested classes), using deterministic JUnit tests.
Generated property tests are excluded from PIT to keep mutation runs predictable.
Read `target/pit-reports/index.html` or `mutations.xml`; classify surviving
mutations as missing assertions, uncovered behavior, equivalent behavior with
justification, or unresolved. A survivor is a question to investigate, not
permission to weaken a contract. There is initially no mutation-score gate.

Normal CI uploads coverage and test diagnostics for 14 days, including jqwik
replay data when available. Mutation CI runs weekly on Sunday at 02:00 UTC and
through manual dispatch, with a 30-minute timeout and 14-day reports. Neither
workflow hides test/tool failures. Reports use synthetic fixtures; live model
calls and private documents are not part of these tests.
