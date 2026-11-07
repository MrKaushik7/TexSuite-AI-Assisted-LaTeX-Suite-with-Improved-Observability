# TexSuite

Review and apply edits to saved LaTeX projects: rename mathematical symbols,
replace text, clean up whitespace and merge documents. AI assistance is optional.
Edits require review and approval, with compilation checks before writing by default.

## Get started

You need **Java 21+**. Applying compiled edits also needs **pdflatex** and your
document's TeX packages. Merge may need BibTeX.

From a source checkout:

```sh
./mvnw clean verify
./scripts/install.sh
export PATH="$HOME/.local/bin:$PATH"
texsuite path/to/main.tex
```

Already installed? Run `texsuite path/to/main.tex`, or `texsuite` to choose a file.
Save editor changes first. Press Enter at optional AI setup to use manual review.

Follow [your first edit](docs/getting-started.md#make-your-first-edit) for a complete
walkthrough, or [installation help](docs/getting-started.md#install) for Java, TeX and PATH setup.

## Choose a task

| I want to… | Read |
| --- | --- |
| Rename a symbol using its meaning | [Rename](docs/user-guide.md#rename-a-mathematical-symbol) |
| Replace literal text or source values | [Replace text](docs/user-guide.md#replace-text) |
| Remove trailing spaces or change line endings | [Clean up whitespace](docs/user-guide.md#clean-up-whitespace) |
| Import content from another document | [Merge documents](docs/user-guide.md#merge-documents) |
| Undo or recover an interrupted edit | [Undo and recovery](docs/user-guide.md#undo-history-and-recovery) |
| Configure an editor or look up flags | [Settings and commands](docs/user-guide.md#settings-and-command-reference) |
| Enable optional AI assistance | [AI setup](docs/ai-setup.md) |
| Resolve an error or blocked operation | [Troubleshooting](docs/troubleshooting.md) |
| Check supported syntax and safety limits | [Safety reference](opinions.md) |
| Build, test or contribute | [Development](docs/development.md) |

## Before applying

- Review selected occurrences and the preview. Compilation cannot establish meaning.
- File scope is the default; project scope follows static includes, not every file in a folder.
- `back` returns to the menu without applying pending edits. Main-menu **6: Quit** ends the session.
- Main-menu **8: Help** explains tasks. Decision prompts marked `?=help` keep your place.
- AI requests show their exact payload and need separate consent before sending source.
- Keep `.tex-suite/` private: it contains source-bearing history, backups, plans and cached decisions.

The installer keeps the JAR and offline guides in `~/Applications/TexSuite/`,
with the launcher at `~/.local/bin/texsuite`. Run `texsuite --help` for command examples.
