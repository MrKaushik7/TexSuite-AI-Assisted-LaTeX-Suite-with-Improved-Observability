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

Choose **Rename a mathematical symbol** to enter the source, its intended meaning, replacement, and file/project scope. With a configured AI provider and model, choose AI classification or manual review. AI suggests replace, keep or needs-human-review using your stated meaning. Each recommendation has a separate banner and repeats at its answer prompt: **AI KEEP** asks explicitly whether to override KEEP and replace; **AI REPLACE** asks whether to accept replacement; uncertain decisions request manual review. `y` always approves replacement, including a KEEP override; Enter or `n` keeps the original. Uncertain or protected scanner occurrences cannot be selected. Review the accepted-only unified diff, then type `apply`. Quit, EOF, cancellation, and empty selections leave source unchanged. Stale source requires fresh occurrence decisions and remote consent.

Normal rename output shows candidate excerpts and summary counts. Excluded/review rows, internal IDs, snapshot metadata and retrieved-context listings require `--debug`; `--no-debug` overrides it. Replace text also hides skipped-match rows by default. Exact API payload preview and necessary review/validation warnings remain visible.

Rename retrieves source context (listed with `--debug` and included in an approved API request): the enclosing equation and blank-line paragraph, nearest supported section/definition/theorem/proof, and referenced local macro definitions. Static included files may supply context without becoming editable. Batches contain at most eight candidates and 24,000 Unicode source characters, with a requested 1,500-output-token limit; oversized, incomplete or structurally uncertain context requires manual review without truncation. Supporting source changes require fresh review, including changes detected after compilation.

Configure AI through **Settings → 8 → 1**: choose **1 OpenAI**, **2 OpenRouter**, or **3 Gemini**, then enter an explicit model ID, key environment-variable name and timeout (default 60 seconds, range 1–300). No model is chosen automatically. Key references default to `OPENAI_API_KEY`, `OPENROUTER_API_KEY`, or `GEMINI_API_KEY`; supply values through your local environment, never Settings. For OpenRouter, also choose output mode: JSON Schema (default), or explicit prompt JSON for models without `response_format` support. Option 2 saves the selected provider and its profile to `.tex-suite/config.json`, preserving other provider profiles. Session settings override project settings, which override `TEXSUITE_MODEL_PROVIDER` (`openai`, `openrouter`, `gemini`) and `TEXSUITE_<PROVIDER>_MODEL`, `_KEY_ENV`, `_TIMEOUT`, `_OUTPUT_MODE` (`json_schema` or OpenRouter-only `prompt_json`). Existing OpenAI-only configuration remains readable. Option 3 tests fixed synthetic text, without document source, after separate request consent.

At the key-reference prompt, enter the variable's **name**, such as `GEMINI_API_KEY`, rather than the API key itself. Press Enter to use the selected provider's default name. Set that variable to your key in the terminal before launching TexSuite; the running program inherits that terminal's environment.

| Provider | Fixed API | Explicit POC model | Key reference |
| --- | --- | --- | --- |
| OpenAI | `https://api.openai.com/v1/responses` | User-selected model | `OPENAI_API_KEY` |
| OpenRouter | `https://openrouter.ai/api/v1/chat/completions` | `nvidia/nemotron-3-ultra-550b-a55b:free` | `OPENROUTER_API_KEY` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent` | `gemini-2.5-flash` | `GEMINI_API_KEY` |

These are example explicit profiles, not defaults. OpenRouter schema mode requests strict JSON Schema with `require_parameters:true`; unsupported routes fail without an automatic downgrade. The selected Nemotron Ultra 3 free endpoint [does not support `response_format`](https://openrouter.ai/nvidia/nemotron-3-ultra-550b-a55b:free), so explicitly select **prompt JSON** for that model: the schema goes in the system instruction, with the same strict local validation but no provider formatting guarantee. This endpoint documents logging submitted data; use synthetic text for the proof of concept. TexSuite supplies a single model, without an alternate paid model. Native Gemini sends its key in `x-goog-api-key`, never the URL, and requests JSON Schema through `generationConfig.responseMimeType` and `responseJsonSchema`, matching [Google’s Gemini 2.5 SDK example](https://googleapis.github.io/python-genai/#json-response-schema). Free access depends on model availability, account tier and quota; TexSuite cannot impose free billing on a paid Gemini account. Check [OpenRouter's model listing](https://openrouter.ai/nvidia/nemotron-3-ultra-550b-a55b:free) and [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-2.5-flash). Start with synthetic text; Google's free tier may use submitted data to improve products.

Every request shows its model, destination and exact outgoing JSON, excluding the key, before asking permission. Source is sent only after `y`. All providers use bounded non-streaming requests without tools or conversation history; native OpenAI also uses `store:false`. This does not override provider retention policies. All batches must validate before suggestions reach occurrence review. HTTP errors, refusal, timeout, incomplete or invalid output discard the operation's AI suggestions and offer manual review or cancellation. A completed malformed classification may offer one schema repair: its separately approved payload contains only allowed IDs and previous output, which may repeat source, without reattaching original excerpts. Local providers and automatic batch approval are not yet available. Replace text remains manual.

Approved rename intent is stored in `.tex-suite/plans/` as versioned JSON with request, source fingerprint, accepted occurrence IDs/ranges/hashes, and `manual` or provider-specific reviewed provenance (`openai-reviewed`, `openrouter-reviewed`, `gemini-reviewed`). These private local records can contain source text. They record approval, not successful application: a later compilation failure can leave a plan without changing source. There is no plan replay command. For a compilable example, define `\newcommand{\numElements}{n}` and rename selected `n` uses to `\numElements`; `\number` is already a TeX primitive.

Choose **Replace text** for case-sensitive literal replacement. Approve each occurrence with `y`; `n` or Enter skips it. Review the accepted-only preview and type `apply` to write those changes. `quit` or end-of-input cancels; accepting no occurrences writes nothing. If saved source changes, occurrence review starts again. Document-text mode protects TeX syntax and uncertain conditional branches; LaTeX-source mode permits ordinary source values but preserves structural guards.

Apply validates a disposable project copy with `pdflatex`, then rechecks saved files, backs up originals, and replaces files atomically. If pdflatex is unavailable, `--allow-no-compile` explicitly permits uncompiled edits; compiler failure or timeout still blocks writes. For a chapter, TexSuite offers its `% !TEX root = main.tex` hint for confirmation, or asks for a main-file path relative to the displayed project root. Enter at the path prompt cancels. The chosen main must contain every accepted edit through unconditional static includes. `texsuite --compile-main path/to/main.tex path/to/chapter.tex` overrides the hint and also supports a containing project above the chapter folder. Compilation reads that project without expanding edit scope. Failure reports include the selected main and a bounded compiler error summary when available. Copies are limited to 512 files, 16 MiB per file and 64 MiB total; hidden entries and `target` are excluded, and symbolic links are refused.

Settings controls automatic opening in the system text editor (macOS), a browsed application, and **Recover interrupted edits** (option 7). Choose **5 → 3: Browse for editor…** to select an installed `.app`; typed application names are not accepted. Cancel keeps the previous choice. TexSuite validates the bundle and executable, saves its full path locally, and checks it again before opening. Turning automatic opening on immediately opens the current file in the preferred editor. Option 4 opens the current file now; option 5 resets to the system editor with automatic opening enabled. Invalid old application names or removed apps fall back to the system editor while preserving the automatic-opening toggle. Recovery requires `restore` and refuses to overwrite unexpected external changes. Backups and recovery records live under the selected file's project root in `.tex-suite/backups/`.
