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
