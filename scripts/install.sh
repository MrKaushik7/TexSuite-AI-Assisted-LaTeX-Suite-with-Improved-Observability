#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
SOURCE_JAR="$PROJECT_DIR/target/texsuite.jar"
INSTALL_DIR=${TEXSUITE_INSTALL_DIR:-"$HOME/Applications/TexSuite"}
BIN_DIR=${TEXSUITE_BIN_DIR:-"$HOME/.local/bin"}

if [ ! -f "$SOURCE_JAR" ]; then
    printf '%s\n' "Missing $SOURCE_JAR. Run ./mvnw verify first." >&2
    exit 1
fi

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
install -m 0644 "$SOURCE_JAR" "$INSTALL_DIR/texsuite.jar"

cat > "$BIN_DIR/texsuite" <<EOF
#!/bin/bash
warning_log_dir=\${TEXSUITE_LOG_DIR:-"\$HOME/Library/Logs/TexSuite"}
warning_log="\$warning_log_dir/picker.log"

filter_picker_stderr() {
    local line notice_shown=false
    umask 077
    while IFS= read -r line || [ -n "\$line" ]; do
        case "\$line" in
            *"The class 'NSOpenPanel' overrides the method identifier.  This method is implemented by class 'NSWindow'"*)
                if mkdir -p -m 700 "\$warning_log_dir" && printf '%s\\n' "\$line" >> "\$warning_log"; then
                    if [ "\$notice_shown" = false ]; then
                        printf 'TexSuite: macOS picker warning logged at %s\\n' "\$warning_log" >&2
                        notice_shown=true
                    fi
                else
                    printf '%s\\n' "\$line" >&2
                fi
                ;;
            *)
                printf '%s\\n' "\$line" >&2
                ;;
        esac
    done
}

{
    java -jar "$INSTALL_DIR/texsuite.jar" "\$@" 2>&1 1>&3 | filter_picker_stderr
    java_status=\${PIPESTATUS[0]}
} 3>&1
exit "\$java_status"
EOF
chmod 0755 "$BIN_DIR/texsuite"

printf 'Installed TexSuite JAR: %s\n' "$INSTALL_DIR/texsuite.jar"
printf 'Installed command: %s\n' "$BIN_DIR/texsuite"
printf 'Add %s to PATH if needed.\n' "$BIN_DIR"

find_compiler() (
    # Match the application's discovery order without changing the caller's IFS.
    set -f
    IFS=:
    compiler_search="${PATH-}:/Library/TeX/texbin"
    for compiler_dir in $compiler_search; do
        case "$compiler_dir" in
            /*)
                if [ -f "$compiler_dir/pdflatex" ] && [ -x "$compiler_dir/pdflatex" ]; then
                    printf '%s\n' "$compiler_dir/pdflatex"
                    return 0
                fi
                ;;
        esac
    done
    return 1
)

if compiler_path=$(find_compiler); then
    printf 'TeX compiler found: %s\n' "$compiler_path"
else
    tex_download_url=https://www.tug.org/mactex/morepackages.html
    printf '\n%s\n' 'pdflatex was not found. Compiled edits require a TeX installation.'
    printf 'Install BasicTeX (recommended): %s\n' "$tex_download_url"
    printf '%s\n' 'Download BasicTeX and complete its macOS installer, then rerun this script to check.'
    printf '%s\n' 'Some documents need extra TeX packages; add missing packages with tlmgr.'
    printf '%s\n' 'Optional full MacTeX for broader package coverage: https://www.tug.org/mactex/mactex-download.html'
    printf '%s\n' 'For an explicitly uncompiled edit, start TexSuite with --allow-no-compile.'

    if [ -t 0 ]; then
        printf 'Open the official BasicTeX download page? [y/N]: '
        if IFS= read -r setup_answer; then
            case "$setup_answer" in
                y|Y|yes|YES|Yes)
                    if ! open "$tex_download_url"; then
                        printf '%s\n' 'Could not open the browser. Use the download link above.'
                    fi
                    printf '%s\n' 'Complete the BasicTeX installer before checking again.'
                    printf 'Press Enter to check for pdflatex, or type skip to finish: '
                    if IFS= read -r recheck_answer && [ -z "$recheck_answer" ]; then
                        if compiler_path=$(find_compiler); then
                            printf 'TeX compiler found: %s\n' "$compiler_path"
                        else
                            printf '%s\n' 'pdflatex is still unavailable. Rerun this script after installing BasicTeX.'
                        fi
                    fi
                    ;;
            esac
        fi
    fi
fi
