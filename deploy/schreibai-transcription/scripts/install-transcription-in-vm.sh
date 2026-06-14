#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="${1:-/home/giulian/eigene-ai-rag}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ ! -d "$SOURCE_DIR" ]]; then
  echo "Quelle fehlt: $SOURCE_DIR" >&2
  exit 1
fi

cd "$PROJECT_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$PROJECT_DIR/backups/schreibai-transcription-$STAMP"
mkdir -p "$BACKUP_DIR"

echo "Backup nach: $BACKUP_DIR"
cp -a docker-compose.yml "$BACKUP_DIR/docker-compose.yml"
cp -a workflows "$BACKUP_DIR/workflows"
cp -a scripts "$BACKUP_DIR/scripts"

echo "Kopiere Transkriptionsdateien..."
mkdir -p services/media-transcription-worker workflows scripts exports
cp -r "$SOURCE_DIR/services/media-transcription-worker/." services/media-transcription-worker/
cp -f "$SOURCE_DIR/workflows/n8n_schreib_ai_transcription.json" workflows/n8n_schreib_ai_transcription.json
cp -f "$SOURCE_DIR/scripts/import-transcription-workflow.sh" scripts/import-transcription-workflow.sh
chmod +x scripts/import-transcription-workflow.sh 2>/dev/null || true

if ! grep -q "media-transcription-worker:" docker-compose.yml; then
  echo "Ergaenze docker-compose.yml um media-transcription-worker..."
  python3 - <<'PY'
from pathlib import Path

compose = Path("docker-compose.yml")
text = compose.read_text(encoding="utf-8")
insert = """

  media-transcription-worker:
    build:
      context: ./services/media-transcription-worker
    container_name: eigene-ai-media-transcription-worker
    restart: unless-stopped
    ports:
      - "8091:8091"
    environment:
      - PORT=8091
      - ASR_MODEL=${ASR_MODEL:-small}
      - ASR_DEVICE=${ASR_DEVICE:-cpu}
      - ASR_COMPUTE_TYPE=${ASR_COMPUTE_TYPE:-int8}
      - ASR_DEFAULT_LANGUAGE=${ASR_DEFAULT_LANGUAGE:-de}
    volumes:
      - transcription_models:/models
      - ./exports:/exports
"""
marker = "\nvolumes:\n"
if marker not in text:
    raise SystemExit("docker-compose.yml enthaelt keinen volumes:-Block")
text = text.replace(marker, insert + marker, 1)
if "  transcription_models:\n" not in text:
    text = text.rstrip() + "\n  transcription_models:\n"
compose.write_text(text, encoding="utf-8", newline="\n")
PY
fi

touch .env
grep -q '^ASR_MODEL=' .env || echo 'ASR_MODEL=small' >> .env
grep -q '^ASR_DEVICE=' .env || echo 'ASR_DEVICE=cpu' >> .env
grep -q '^ASR_COMPUTE_TYPE=' .env || echo 'ASR_COMPUTE_TYPE=int8' >> .env
grep -q '^ASR_DEFAULT_LANGUAGE=' .env || echo 'ASR_DEFAULT_LANGUAGE=de' >> .env

echo "Pruefe Worker-Syntax..."
python3 - <<'PY'
from pathlib import Path
p = Path("services/media-transcription-worker/media_transcription_worker.py")
compile(p.read_text(encoding="utf-8"), str(p), "exec")
print("syntax-ok")
PY

echo "Baue Transkriptions-Worker und starte n8n..."
docker compose up -d --build media-transcription-worker n8n
bash scripts/import-transcription-workflow.sh

SERVER_IP="$(grep '^SERVER_IP=' .env | cut -d= -f2-)"
SERVER_IP="${SERVER_IP:-127.0.0.1}"

echo "Fertig."
echo "Transkriptions-Webhook: http://${SERVER_IP}:5678/webhook/schreib-ai/transcribe"
