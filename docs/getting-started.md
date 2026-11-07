# Get started

[Home](../README.md) · [Task guide](user-guide.md) · [Troubleshooting](troubleshooting.md)

## Install

1. Check Java. TexSuite needs **Java 21 or newer**, available as `java` on PATH:

   ```sh
   java -version
   ```

2. From the source checkout, build and install:

   ```sh
   ./mvnw clean verify
   ./scripts/install.sh
   export PATH="$HOME/.local/bin:$PATH"
   texsuite --version
   ```

   Maven uses the included wrapper. The installer copies the JAR and offline
   guides to `~/Applications/TexSuite/` and the launcher to `~/.local/bin/`.
   It tests the newly installed launcher before reporting success. If this fails,
   fix the reported Java/JAR problem and rerun; copied files remain. The check
   makes no API request and does not test document compilation or TeX packages.

3. For compiled edits, install `pdflatex` and your document's packages.
   On macOS, the installer checks absolute PATH entries and `/Library/TeX/texbin`.
   If missing, it offers the official [BasicTeX download](https://www.tug.org/mactex/morepackages.html).
   Complete the downloaded macOS installer, then press Enter for one recheck or
   type `skip`. You can rerun TexSuite's installer later. Noninteractive installation
   prints instructions without prompting.

   An existing `pdflatex` installation also works. BasicTeX may need packages
   added with `tlmgr`; [full MacTeX](https://www.tug.org/mactex/mactex-download.html)
   provides broader coverage. Compiler discovery does not verify document packages.
   Reading and inspecting need no TeX installation. TexSuite does not bundle or
   automatically install TeX.

The PATH export applies to this terminal. Add it to your shell startup file to
make the command available in new terminals. To run without installing:

```sh
java -jar target/texsuite.jar path/to/main.tex
```

## Open a document

```sh
texsuite path/to/main.tex
```

Use an interactive terminal for editing menus. With redirected input and a file
argument, TexSuite performs read-only inspection instead.

Choose a saved UTF-8 `.tex` file. With no path, TexSuite prompts for a file and
supports browsing where available. Relative paths start in the launch folder.
Automatic opening uses your configured text editor. Save changes before reviewing:
TexSuite reads disk files, not unsaved editor buffers.

At first interactive startup, press Enter at **Configure AI now?** for manual
review. Enable AI later through **5: Settings → 8: AI provider settings and test**.
No key or account is needed for this walkthrough.

## Make your first edit

Use a copy of a document that already compiles. For a minimal example, save
this as `first-edit.tex`:

```tex
\documentclass{article}
\begin{document}
The sample contains several items.
\end{document}
```

Run `texsuite first-edit.tex`, then:

1. Choose **2: Replace text**.
2. Enter `sample` at **Find literal text**, then `example` at **Replace with**.
3. Press Enter for **file** scope, **document text** region and **whole words** matching.
4. If AI is configured, press Enter at the purpose prompt and choose **2: Manual**.
5. Enter `y` for the intended occurrence. Enter leaves an occurrence unchanged.
6. Read the selected-only diff, then enter `apply` at the final prompt.

TexSuite compiles a disposable copy, rechecks source, records history/backups,
then changes the file. The sentence becomes “The example contains several items.”
Main-menu **7: Undo last change** restores the original after confirmation,
provided the output has not been edited since.

Compilation failure leaves source unchanged. Follow the reported error and
[compilation troubleshooting](troubleshooting.md#compilation-and-chapters).
If `pdflatex` alone is unavailable, ordinary edits can explicitly use
`--allow-no-compile`; this gives no compilation assurance and never permits an
uncompiled merge.

## Navigate safely

- `back` cancels the flow and returns to the menu. Main-menu **6: Quit** ends the session.
- Enter follows the displayed default. Approval prompts leave source unchanged by default.
- Main-menu **8: Help** explains tasks. At prompts marked `?=help`, `?` shows help
  and repeats the question without changing your selection.
- At literal search/replacement or definition inputs, `?` and `help` are literal
  text. Only `back` and `quit` alone are reserved; other search/replacement whitespace stays exact.
- Saved-source changes invalidate an old review. Review the fresh proposal before applying.

Next: [rename, cleanup, merge and undo](user-guide.md). The
[safety reference](../opinions.md) records supported syntax and precise limits.
