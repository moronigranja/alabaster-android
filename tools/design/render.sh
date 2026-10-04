#!/usr/bin/env bash
# Render the port's design previews (vectors, launcher masks, palette, screen mock-ups).
# Output lands in tools/design/out/ (gitignored). See tools/design/README.md.
set -euo pipefail
cd "$(dirname "$0")"
exec python3 preview.py "$@"
