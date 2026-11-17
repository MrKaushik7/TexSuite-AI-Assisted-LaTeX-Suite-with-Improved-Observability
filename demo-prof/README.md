# Professor demo: full research papers

These are working copies of real manuscripts, with the original chapter structure,
macros, local styles, bibliographies and referenced figure assets. Use them to show
TexSuite operating on substantial mathematical documents. Downloads originals are
untouched; no artificial occurrences or padding have been added.

| Folder | Origin | Size of included TeX sources | Compilation main |
| --- | --- | --- | --- |
| `01-current` | `~/Downloads/Current` | 22 files, 3,613 lines, 275 KB | `main.tex` |
| `02-current-variant` | `~/Downloads/Current 2` | 22 files, 3,612 lines, 275 KB | `main.tex` |
| `03-piano` | `~/Downloads/piano-improved` | 18 files, 7,152 lines, 357 KB | `pir-main-new.tex` |

Totals include alternate/inactive chapters. Current and Current 2 are variants of
the same paper, not independent corpora. `provenance.json` records source paths,
file sizes and SHA-256 hashes. Nested historical versions, `.git`, `.tex-suite`,
editor settings, old compiled papers and generated auxiliary files were omitted.
Piano's `fig` and `figs` assets are retained.

## Prepare once

Run commands from the workspace root. Java 21+, `pdflatex`, BibTeX and the paper's
TeX packages are needed. Use the workspace JAR so the demo matches this checkout.
Create a fresh rehearsal copy; the destination must not already exist:

```sh
cp -R demo-prof/03-piano demo-prof/piano-rehearsal-1
java -jar texsuite/target/texsuite.jar \
  --compile-main demo-prof/piano-rehearsal-1/pir-main-new.tex \
  demo-prof/piano-rehearsal-1/definition.tex
```

Use the same pattern for Current and its variant. The commands below open the
packaged fixtures directly; substitute your rehearsal path when presenting.
`--compile-main` supplies the containing document for validation, while **file
scope** limits the edit to the selected chapter. It does not add the parent
preamble to AI rename context. Always inspect the displayed compilation target.

AI is optional. Configure it using the [AI guide](../texsuite/docs/ai-setup.md);
approve the displayed payload only when you intend to send those excerpts.
Manual selection provides an offline fallback. Read each prompt instead of
pasting a blind keystroke sequence. Main-menu 6 quits; `back` cancels a workflow.

## Suggested 10–15 minute showcase

### 1. Real mathematics: database size versus query count

```sh
java -jar texsuite/target/texsuite.jar \
  --compile-main demo-prof/03-piano/pir-main-new.tex \
  demo-prof/03-piano/definition.tex
```

Open the chapter beside the terminal. It defines correctness and privacy using
`n` for database length, `q` for query count, and simulator/adversary parameters.
The short chapter belongs to a much larger paper; the entire main is compiled
when applying an edit.

Choose **1: Rename**, then:

| Prompt | Value |
| --- | --- |
| Source | `n` |
| Meaning | `The number of bits in the PIR database, including n in database lengths, index bounds and public simulator/adversary parameters.` |
| Replacement | `N_{\mathrm{db}}` |
| Scope | File |
| Selection | AI, or manual for offline presentation |

Review the occurrences in database lengths, bounds and simulator arguments.
The earlier acceptance fixture has 14 eligible occurrences for this exact chapter;
use the current scanner's displayed count as authoritative. Command names,
comments and unrelated symbols must stay unchanged. Show `diff`, review the
selection, then approve `apply`. Show the successful compilation check and changed
source. This demonstrates a chapter edit, not a globally consistent notation
migration: other chapters still use `n`.

Choose **7: Undo**, confirm, and show restoration. Compilation establishes TeX
validity, not mathematical correctness; the presenter still reviews meaning.

### 2. A meaning that is absent must produce no edits

On the restored `definition.tex`, repeat rename with:

- Source: `n`
- Meaning: `Only n denoting the number of online queries made by a client. Keep n denoting database length or its index bounds unchanged; the number of queries is q here.`
- Replacement: `Q`
- Scope: file

Expected semantic result: keep every `n`. Point to `q` in the correctness and
privacy paragraphs. If the model selects anything incorrectly, explain and
cancel/correct it; do not present a wrong proposal as a pass. No selection should
mean no source write.

### 3. A larger chapter with competing index meanings

```sh
java -jar texsuite/target/texsuite.jar \
  --compile-main demo-prof/01-current/main.tex \
  demo-prof/01-current/app-encode-decode.tex
```

This approximately 31 KB appendix supplies an encoding/decoding argument. Choose
rename, file scope:

- Source: `n`
- Meaning: `The single query index n in the encoding algorithm. Rename bare n used as one query position. Keep the paired collision-query indices n_1 and n_2 unchanged.`
- Replacement: `j_{\mathrm{query}}`

Show the different mathematical roles in the occurrence previews. The prior
fixture has 10 eligible candidates, including KEEP cases; this is not an instruction
to replace every match. Review the diff and cancel unless the whole selection is
correct. For a short presentation, preview alone is sufficient here.

### 4. Exact-request cache, without changing the source

On either rename example, successfully classify with AI and **cancel before
applying**. Repeat the identical source, meaning, replacement, scope and selection
at the **same file path**, choosing ordinary AI selection rather than regenerate.
Look for the explicit cache-hit/no-remote-call message. Review is still required.
Do not claim a hit if the tool reports a new request. Editing the source or moving
the project can invalidate this demonstration; copying to another folder is not a
cache demo. End by cancelling.

## Extra examples for questions or a longer presentation

| Target | What to showcase | Inputs / expectation |
| --- | --- | --- |
| `02-current-variant/abstract.tex` | Quick entry into the second manuscript variant | Compile `02-current-variant/main.tex`; rename `N` → `H`, meaning “The parameter N in the hash-function domain and range, including N in complexity bounds for that same hash-function parameter.” File scope. Earlier fixture: 6 eligible candidates. |
| `03-piano/construction-polylog.tex` | Larger mathematical context, equations and algorithms (about 28 KB) | Compile `03-piano/pir-main-new.tex`; rename `n` → `N_{\mathrm{db}}`, meaning “The universe size / PIR database length n, including derived square-root bounds, algorithms parameterized by this n, and asymptotic cost expressions in n.” Earlier fixture: 34 candidates. Review carefully; allow extra time for batches. |
| `02-current-variant/tradeoff.tex` | Long proof chapter (about 75 KB) | Compile `02-current-variant/main.tex`; preview `N` → `H` for the shared hash domain/range parameter. Earlier fixture: 75 candidates. Optional stress demonstration; no blanket semantic acceptance. |
| `03-piano/1server-new.tex` | Long algorithm chapter (about 31 KB) | Compile `03-piano/pir-main-new.tex`; preview database-length `n` → `N_{\mathrm{db}}`. Earlier fixture: 100 candidates. Optional stress demonstration; not a timed live-AI default. |
| `03-piano/intro.tex` | Literal replacement inside a real paper | Compile `03-piano/pir-main-new.tex`; menu 2, `scheme` → `construction`, file scope, document text, whole words, blank meaning and manual review. Select one appropriate occurrence, show the diff, apply and undo. Plural `schemes` should remain unchanged. This is an editing demonstration, not a proposed manuscript revision. |
| `03-piano/definition.tex` | Conservative whitespace cleanup | Same compilation main; menu 3. Inspect the report and eligible trailing whitespace. Apply only offered safe fixes, then undo. Commands, math and comments must stay protected; do not promise every trailing space is fixable. |

For any table row, launch with the same `java -jar … --compile-main MAIN CHAPTER`
pattern above. The larger chapters are included in full; no extraction is needed.

Keep edits file-scoped for the live presentation. Previous large-paper runs found
whole-project Piano `n` rename exceeds the 200-occurrence review limit, while
Current's whole-project context can require structural review. Treat a refusal or
manual-review requirement as a supported limit, not a reason to bypass the guard.

For a predictable merge demonstration, use the existing
[merge-reuse fixture](../demo/05-merge-reuse/) and
[merge-dependency fixture](../demo/07-merge-dependencies/). These manuscripts have
complex preambles and conditionals; a whole-paper merge is not asserted to work.

## Evidence and rehearsal discipline

`verification.json` records fresh baseline compilation and source-hash checks for
these copies. All three mains compiled successfully: Current and Current 2 produced
45 pages each; Piano produced 46 pages. All copied files match their Downloads
originals byte-for-byte. Existing duplicate-label/font/hyperref warnings in Current
and caption/theorem/font/oversized-float warnings in Piano remain in the manuscripts. Compilation uses a disposable directory: `pdflatex`, `bibtex`, then
`pdflatex` twice. Existing manuscript warnings are recorded separately from exit
status. No generated output is needed in the fixture directories.

The occurrence counts above come from the earlier
[large-paper acceptance cases](../08-11-2026-00-19/ai-stress/README.md), not a fresh
interactive run on this folder. This delivery does not claim a live AI, apply,
cache-hit or undo rehearsal. Provider failures should be reported as incomplete;
use manual review or cancel rather than treating a partial response as success.

Use undo for applied operations. For a fresh independent rehearsal, copy a
pristine fixture to a new destination. Do not overwrite edited source underneath
old `.tex-suite` history. Keep the packaged examples pristine so this remains easy.
