# Media- und Transkriptionsfunktionen

Die Oberflaeche hat zwei zusaetzliche Tabs:

- `Video zu Sound`: extrahiert Audio aus Videodateien mit `ffmpeg`.
- `Sound zu TXT`: transkribiert Audiodateien direkt oder erzeugt aus einem Videofile zuerst eine temporaere WAV-Datei und daraus ein TXT-Transkript.

## Voraussetzungen

- `media.ffmpeg.path` muss auf `ffmpeg` im PATH oder auf eine absolute `ffmpeg.exe` zeigen.
- Fuer Transkription gibt es zwei Wege:
  - `transcription.n8n.webhookUrl`: Die App sendet die Audiodatei als Multipart-Upload an n8n. Wenn dieser Wert leer bleibt, nutzt die App automatisch `n8n.web.baseUrl` bzw. `n8n.host:n8n.web.port` mit `/webhook/schreib-ai/transcribe`.
  - `transcription.command`: Lokales Kommando mit Platzhaltern `{input}`, `{output}`, `{outputDir}` und `{language}`.

Wenn weder n8n-Webhook noch Kommando gesetzt ist, versucht die App `whisper` aus dem PATH.

## n8n-Workflow und Deploy-Paket

`deploy/schreibai-transcription` ist das passende Deploy-Paket fuer den vorhandenen SchreibAI/n8n-Stack unter `F:\Virtuelle Maschinenen\SchreibAI`.

Fuer die laufende VM wird das Paket als ZIP in den bestehenden Shared-Auto-Installer gelegt:

```text
F:\Virtuelle Maschinenen\Eigene AI\Shared\inbox\schreib-ai-transcription-v2.zip
```

Der Auto-Installer entpackt das ZIP in der VM und startet die enthaltene `install.sh`.

`docs/workflows/n8n_schreib_ai_transcription.json` enthaelt eine dokumentierte Kopie des n8n-Workflows aus dem Deploy-Paket. Der Workflow nimmt die Datei am Webhook entgegen und reicht sie an den Service `media-transcription-worker` weiter.

Das Deploy-Paket unter `deploy/schreibai-transcription` bleibt die Quelle fuer Installation und VM-Import. Nach dem Import:

1. `media-transcription-worker` im Docker-Compose-Stack starten.
2. Workflow importieren und aktivieren.
3. Die Production-URL muss nur dann in `config/local.properties` als `transcription.n8n.webhookUrl` eingetragen werden, wenn sie vom Standard `/webhook/schreib-ai/transcribe` abweicht.
4. Falls der Webhook geschuetzt wird, den Headerwert in `transcription.n8n.authorization` eintragen.
