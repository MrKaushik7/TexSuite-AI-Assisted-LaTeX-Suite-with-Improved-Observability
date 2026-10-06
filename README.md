# TexSuite

## Build

```sh
./mvnw clean verify
```

## Install

```sh
./scripts/install.sh
export PATH="$HOME/.local/bin:$PATH"
```

The JAR is installed at `~/Applications/TexSuite/texsuite.jar` and the command at `~/.local/bin/texsuite`.

The installer checks for `pdflatex` on absolute `PATH` entries and in `/Library/TeX/texbin`. If it is missing, it recommends [BasicTeX](https://www.tug.org/mactex/morepackages.html), a smaller TeX installation, and an interactive install offers to open its official download page. Download BasicTeX and run its macOS installer, then press Enter for one availability recheck or type `skip` to finish. You can also rerun `scripts/install.sh` later to check again. Noninteractive installs print the instructions without prompting.

Some documents need extra classes, fonts or packages beyond BasicTeX; install missing packages with its `tlmgr` package manager. [Full MacTeX](https://www.tug.org/mactex/mactex-download.html) is optional for broader package coverage. An existing installation providing `pdflatex` can also be reused. Finding a compiler does not prove a document's packages are available; compilation is checked when applying an edit. Reading and inspecting documents requires no TeX installation.

## Run

```sh
texsuite --help
texsuite --version
texsuite [FILE]
```

Choose **Rename a mathematical symbol** to enter the source, its intended meaning, replacement, and file/project scope. Review each eligible mathematical occurrence with `y`; Enter or `n` skips. Uncertain or protected occurrences cannot be selected. Review the accepted-only unified diff, then type `apply`. Selection is manual at this stage; model classification is not implemented. Quit, EOF, cancellation, and empty selections leave source unchanged. Stale source requires fresh occurrence decisions.

Approved rename intent is stored in `.tex-suite/plans/` as versioned JSON with request, source fingerprint, accepted occurrence IDs/ranges/hashes, and manual provenance. These private local records can contain source text. They record approval, not successful application: a later compilation failure can leave a plan without changing source. There is no plan replay command. For a compilable example, define `\newcommand{\numElements}{n}` and rename selected `n` uses to `\numElements`; `\number` is already a TeX primitive.

Choose **Replace text** for case-sensitive literal replacement. Approve each occurrence with `y`; `n` or Enter skips it. Review the accepted-only preview and type `apply` to write those changes. `quit` or end-of-input cancels; accepting no occurrences writes nothing. If saved source changes, occurrence review starts again. Document-text mode protects TeX syntax and uncertain conditional branches; LaTeX-source mode permits ordinary source values but preserves structural guards.

Apply validates a disposable project copy with `pdflatex`, then rechecks saved files, backs up originals, and replaces files atomically. If pdflatex is unavailable, `--allow-no-compile` explicitly permits uncompiled edits; compiler failure or timeout still blocks writes. For a chapter, TexSuite offers its `% !TEX root = main.tex` hint for confirmation, or asks for a main-file path relative to the displayed project root. Enter at the path prompt cancels. The chosen main must contain every accepted edit through unconditional static includes. `texsuite --compile-main path/to/main.tex path/to/chapter.tex` overrides the hint and also supports a containing project above the chapter folder. Compilation reads that project without expanding edit scope. Failure reports include the selected main and a bounded compiler error summary when available. Copies are limited to 512 files, 16 MiB per file and 64 MiB total; hidden entries and `target` are excluded, and symbolic links are refused.

Settings controls automatic opening in the system text editor (macOS), a browsed application, and **Recover interrupted edits** (option 7). Choose **5 → 3: Browse for editor…** to select an installed `.app`; typed application names are not accepted. Cancel keeps the previous choice. TexSuite validates the bundle and executable, saves its full path locally, and checks it again before opening. Turning automatic opening on immediately opens the current file in the preferred editor. Option 4 opens the current file now; option 5 resets to the system editor with automatic opening enabled. Invalid old application names or removed apps fall back to the system editor while preserving the automatic-opening toggle. Recovery requires `restore` and refuses to overwrite unexpected external changes. Backups and recovery records live under the selected file's project root in `.tex-suite/backups/`.
