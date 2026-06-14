#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

docker compose up -d media-transcription-worker n8n
docker exec eigene-ai-n8n n8n import:workflow --input=/workflows/n8n_schreib_ai_transcription.json
docker exec eigene-ai-n8n n8n update:workflow --id=SchreibAiTranscription001 --active=true || true
docker exec eigene-ai-n8n n8n publish:workflow --id=SchreibAiTranscription001 || true
docker compose restart n8n

echo "Transkriptionsworkflow importiert."
echo "Webhook: http://SERVER_IP:5678/webhook/schreib-ai/transcribe"
