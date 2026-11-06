#!/usr/bin/env bash
#
# run-loquilis.sh: open Loquilis from this checkout. Works from any directory, and stays up until the
# window is closed.
#
# Usage:
#   scripts/run-loquilis.sh                  # the default library, where chats are kept
#   scripts/run-loquilis.sh --sample         # the bundled take
#   scripts/run-loquilis.sh <take folder>    # a folder holding conversation.json and raw.jsonl.gz
#   scripts/run-loquilis.sh <library.sqlite> # a library, opened as it is

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -gt 1 ]]; then
  echo "usage: run-loquilis.sh [--sample | take folder | library file]" >&2
  exit 2
fi

if [[ "${1:-}" == "--sample" ]]; then
  exec "$root/gradlew" -p "$root" :app:runWindow --args="--sample"
fi

if [[ $# -eq 1 ]]; then
  source="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
  exec "$root/gradlew" -p "$root" :app:runWindow --args="$source"
fi

exec "$root/gradlew" -p "$root" :app:runWindow
