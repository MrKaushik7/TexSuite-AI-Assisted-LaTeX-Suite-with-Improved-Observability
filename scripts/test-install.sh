#!/bin/sh
set -eu

if [ "${0##*/}" = java ]; then
    if [ "$3" = --clean ]; then
        printf '%s\n' 'clean stderr' >&2
        exit 0
    fi
    printf '%s\n' 'ordinary stderr before' >&2
    printf '%s\n' "2026-09-23 java[1:2] The class 'NSOpenPanel' overrides the method identifier.  This method is implemented by class 'NSWindow'" >&2
    printf '%s\n' 'ordinary stderr after' >&2
    printf 'stdout: %s\n' "$3"
    exit 23
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fixture_root=$(mktemp -d "${TMPDIR:-/tmp}/texsuite-launcher-test.XXXXXX")
trap 'rm -r "$fixture_root"' EXIT
mkdir -p "$fixture_root/bin"
ln -s "$script_dir/test-install.sh" "$fixture_root/bin/java"

TEXSUITE_INSTALL_DIR="$fixture_root/app" TEXSUITE_BIN_DIR="$fixture_root/launchers" \
    "$script_dir/install.sh" > "$fixture_root/install.out"

PATH="$fixture_root/bin:$PATH" TEXSUITE_LOG_DIR="$fixture_root/logs" \
    "$fixture_root/launchers/texsuite" --clean \
    > "$fixture_root/clean.stdout" 2> "$fixture_root/clean.stderr"
grep -Fxq 'clean stderr' "$fixture_root/clean.stderr"
if [ -e "$fixture_root/logs/picker.log" ]; then
    printf '%s\n' 'A diagnostic log was created without a picker warning.' >&2
    exit 1
fi

exit_code=0
PATH="$fixture_root/bin:$PATH" TEXSUITE_LOG_DIR="$fixture_root/logs" \
    "$fixture_root/launchers/texsuite" --probe \
    > "$fixture_root/stdout" 2> "$fixture_root/stderr" || exit_code=$?

if [ "$exit_code" -ne 23 ]; then
    printf 'Java exit code changed: %s\n' "$exit_code" >&2
    exit 1
fi
if grep -Fq "The class 'NSOpenPanel' overrides the method identifier" "$fixture_root/stderr"; then
    printf '%s\n' 'Known picker warning still reaches terminal stderr.' >&2
    exit 1
fi
grep -Fxq 'stdout: --probe' "$fixture_root/stdout"
grep -Fxq 'ordinary stderr before' "$fixture_root/stderr"
grep -Fxq 'ordinary stderr after' "$fixture_root/stderr"
grep -Fq "$fixture_root/logs/picker.log" "$fixture_root/stderr"
grep -Fq "The class 'NSOpenPanel' overrides the method identifier" "$fixture_root/logs/picker.log"
if grep -Fq 'ordinary stderr' "$fixture_root/logs/picker.log"; then
    printf '%s\n' 'Unrelated error was logged instead of forwarded.' >&2
    exit 1
fi

: > "$fixture_root/not-a-directory"
exit_code=0
PATH="$fixture_root/bin:$PATH" TEXSUITE_LOG_DIR="$fixture_root/not-a-directory" \
    "$fixture_root/launchers/texsuite" --probe \
    > "$fixture_root/fallback.stdout" 2> "$fixture_root/fallback.stderr" || exit_code=$?
test "$exit_code" -eq 23
grep -Fq "The class 'NSOpenPanel' overrides the method identifier" "$fixture_root/fallback.stderr"

"$fixture_root/launchers/texsuite" --help > "$fixture_root/help.stdout" 2> "$fixture_root/help.stderr"
grep -Fq 'Usage: texsuite' "$fixture_root/help.stdout"
test ! -s "$fixture_root/help.stderr"

printf '%s\n' 'Launcher warning filter passed.'
