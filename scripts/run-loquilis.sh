#!/usr/bin/env bash
#
# run-loquilis.sh: open the Loquilis review window from this checkout. Works from any directory, and
# stays up until the window is closed.
#
# Usage:
#   scripts/run-loquilis.sh                # the bundled take
#   scripts/run-loquilis.sh <take folder>  # a folder holding conversation.json and raw.jsonl.gz

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -gt 1 ]]; then
  echo "usage: run-loquilis.sh [take folder]" >&2
  exit 2
fi

if [[ $# -eq 1 ]]; then
  folder="$(cd "$1" && pwd)"
  exec "$root/gradlew" -p "$root" :app:run --args="$folder"
fi

exec "$root/gradlew" -p "$root" :app:run
