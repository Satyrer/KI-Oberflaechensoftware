#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$SCRIPT_DIR/scripts/install-transcription-in-vm.sh" "${1:-/home/giulian/eigene-ai-rag}"

