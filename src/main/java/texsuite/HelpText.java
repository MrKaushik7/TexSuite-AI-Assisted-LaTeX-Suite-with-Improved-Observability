package texsuite;

import java.io.PrintWriter;

/** Task guidance only; help never selects occurrences or authorizes an action. */
final class HelpText {
    static final String SCOPE = "File (Enter/1) edits only the selected file. Project (2) follows its static includes, "
            + "not every file in the folder. Context does not expand edit scope.";
    static final String REGION = "Document text (Enter/1) searches prose. Source (2) also permits eligible preamble/math values. "
            + "Line range (3) limits the selected file. Protected syntax stays unavailable in every region.";
    static final String MATCHING = "Whole words (Enter/1): hi matches hi. but not high. Substrings (2) permit embedded matches. "
            + "Search and replacement whitespace stay exact.";
    static final String TRAILING = "Enter selects no trailing fixes. Use all or listed numbers separated by spaces. "
            + "Selection is only a proposal; an exact preview and y/N approval follow.";
    static final String ENDINGS = "Enter keeps line endings. lf or crlf requests a complete eligible conversion. "
            + "Protected terminators block conversion; blank lines and final-newline presence stay unchanged.";
    static final String DONOR = "Enter selects the whole donor body. A listed section number selects that section. "
            + "Use lines START END for a balanced line range. The donor file stays unchanged.";
    static final String INSERTION = "Enter inserts at the end of the master body. Use before NUMBER or after NUMBER "
            + "for a listed section. Review the exact insertion in the final diff.";
    static final String CONFLICT = "Choose a listed number or name explicitly; Enter cancels. "
            + "Using master meaning can change donor behavior. Rename/mapping must preserve compatible arguments. "
            + "Compilation cannot prove intended meaning.";

    private HelpText() { }

    static void overview(PrintWriter out) {
        out.println("""

                Help — choose a task from the main menu

                  1. Rename: literal math source, meaning, replacement, then file/project scope.
                  2. Replace: literal text, replacement, scope, region and matching mode.
                  3. Clean up whitespace: offline fixes; Enter selects none or keeps endings.
                  4. Merge: donor content into the master; donor unchanged, compilation required.
                  5. Settings: editor (2–5), AI (8/10), history (9), recovery (7).
                  7. Undo: restore the whole last change if outputs/backups still match.

                First edit: choose 2, enter search/replacement, accept scope/region/matching
                defaults, review occurrences with y/N, inspect the diff, then type apply.
                AI is optional; every remote payload requires separate consent.

                Navigation: back cancels a flow. Main-menu 6 (Quit) ends the session. Enter uses the shown default.
                At decision prompts marked ?=help, ? or help repeats the question with guidance.
                Literal source/replacement and definition inputs keep ? and help as text.

                Offline guides beside the JAR: docs/getting-started.md, docs/user-guide.md,
                docs/ai-setup.md, docs/troubleshooting.md. Exact limits: opinions.md.
                """);
    }
}
