#!/usr/bin/env bash
# Installs the controller fix into an Alabaster Dawn installation.
#
#   ./install.sh                          # auto-detect the Steam install
#   ./install.sh --game-dir "/path/to/Alabaster Dawn"
#   ./install.sh --mapping standard       # leave Chromium's gamepads untouched
#   ./install.sh --mapping dinput         # always re-decode raw DirectInput pads
#   ./install.sh --revert                 # undo, restores the original index.html
#
# Works for both the Linux build and the Windows build (Wine: GameNative,
# Winlator, Proton) - the two ship the same terra/index.html + terra/bundle.js.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAME_DIR=""
MODE="auto"
REVERT=0
BACKUP_NAME="index.html.gpf-backup"
TAG='<script src="gamepad-fix.js"></script>'
DEFAULT_DIR="$HOME/.local/share/Steam/steamapps/common/Alabaster Dawn"

while [ $# -gt 0 ]; do
    case "$1" in
        --game-dir) GAME_DIR="${2:-}"; shift 2 ;;
        --mapping)  MODE="${2:-}"; shift 2 ;;
        --revert)   REVERT=1; shift ;;
        -h|--help)  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$MODE" in auto|standard|dinput) ;; *) echo "--mapping must be auto|standard|dinput" >&2; exit 2 ;; esac

if [ -z "$GAME_DIR" ]; then
    if [ -f "$DEFAULT_DIR/terra/index.html" ]; then
        GAME_DIR="$DEFAULT_DIR"
    elif [ -f "./terra/index.html" ]; then
        GAME_DIR="$(pwd)"
    else
        echo "Could not find the game. Pass --game-dir \"/path/to/Alabaster Dawn\"." >&2
        exit 1
    fi
fi

HTML="$GAME_DIR/terra/index.html"
[ -f "$HTML" ] || { echo "not found: $HTML" >&2; exit 1; }

if [ "$REVERT" = "1" ]; then
    if [ -f "$GAME_DIR/terra/$BACKUP_NAME" ]; then
        mv -f "$GAME_DIR/terra/$BACKUP_NAME" "$HTML"
        echo "reverted: $HTML"
    else
        echo "no backup found next to $HTML" >&2
        exit 1
    fi
    rm -f "$GAME_DIR/terra/gamepad-fix.js" "$GAME_DIR/terra/gamepad-fix.json"
    exit 0
fi

grep -q 'src="gamepad-fix.js"' "$HTML" && { echo "already installed: $HTML"; exit 0; }
grep -q 'dist/bundle\.js' "$HTML" || { echo "unexpected index.html (no bundle.js tag), refusing to patch: $HTML" >&2; exit 1; }

cp -f "$SCRIPT_DIR/gamepad-fix.js" "$GAME_DIR/terra/gamepad-fix.js"
if [ ! -f "$GAME_DIR/terra/gamepad-fix.json" ]; then
    printf '{\n  "mapping": "%s",\n  "padIndex": null,\n  "debug": false\n}\n' "$MODE" > "$GAME_DIR/terra/gamepad-fix.json"
else
    echo "keeping existing terra/gamepad-fix.json"
fi

[ -f "$GAME_DIR/terra/$BACKUP_NAME" ] || cp -f "$HTML" "$GAME_DIR/terra/$BACKUP_NAME"

awk -v tag="$TAG" '
    { if (!done && /dist\/bundle\.js/) { print tag; done = 1 } print }
    END { exit(done ? 0 : 1) }
' "$GAME_DIR/terra/$BACKUP_NAME" > "$HTML.tmp"
mv -f "$HTML.tmp" "$HTML"

grep -q 'src="gamepad-fix.js"' "$HTML" || { echo "patch failed" >&2; exit 1; }

echo "installed into $GAME_DIR"
echo "  terra/gamepad-fix.js    (normalization shim, loaded before bundle.js)"
echo "  terra/gamepad-fix.json  (mapping: $MODE)"
echo "  backup: terra/$BACKUP_NAME"
echo
echo "Set \"debug\": true in terra/gamepad-fix.json to log detected pads,"
echo "or type window.__gamepadFix.raw() in the NW.js devtools console."
echo "Verify files with Steam afterwards if you want the original back"
echo "(Steam -> properties -> installed files -> verify) - that removes this patch."
