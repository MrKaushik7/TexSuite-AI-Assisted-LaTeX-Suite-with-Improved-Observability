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

## Run

```sh
texsuite --help
texsuite --version
texsuite [FILE]
```

Choose **Replace text** for case-sensitive literal replacement. Review the proposed changes and type `apply` to write them. Document-text mode protects TeX syntax and uncertain conditional branches; LaTeX-source mode permits ordinary source values but preserves structural guards.

Apply validates a disposable project copy with `pdflatex`, then rechecks saved files, backs up originals, and replaces files atomically. If pdflatex is unavailable, `--allow-no-compile` explicitly permits uncompiled edits; compiler failure or timeout still blocks writes. For a chapter, use `texsuite --compile-main path/to/main.tex path/to/chapter.tex` to validate the containing document without expanding edit scope. Copies are limited to 512 files, 16 MiB per file and 64 MiB total; hidden entries and `target` are excluded, and symbolic links are refused.

Settings controls automatic opening in the system text editor (macOS), a browsed application, and **Recover interrupted edits** (option 7). Choose **5 → 3: Browse for editor…** to select an installed `.app`; typed application names are not accepted. Cancel keeps the previous choice. TexSuite validates the bundle and executable, saves its full path locally, and checks it again before opening. Turning automatic opening on immediately opens the current file in the preferred editor. Option 4 opens the current file now; option 5 resets to the system editor with automatic opening enabled. Invalid old application names or removed apps fall back to the system editor while preserving the automatic-opening toggle. Recovery requires `restore` and refuses to overwrite unexpected external changes. Backups and recovery records live under the selected file's project root in `.tex-suite/backups/`.
