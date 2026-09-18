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
#!/bin/sh
exec java -jar "$INSTALL_DIR/texsuite.jar" "\$@"
EOF
chmod 0755 "$BIN_DIR/texsuite"

printf 'Installed TexSuite JAR: %s\n' "$INSTALL_DIR/texsuite.jar"
printf 'Installed command: %s\n' "$BIN_DIR/texsuite"
printf 'Add %s to PATH if needed.\n' "$BIN_DIR"
