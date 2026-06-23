# SchreibAI Transkription

Dieses Paket dockt die Java-Oberflaeche an den bestehenden SchreibAI/n8n-Stack an.

Zielpfad in der Java-App:

```text
http://<n8n-host>:5678/webhook/schreib-ai/transcribe
```

Die App leitet diesen Pfad automatisch aus `n8n.web.baseUrl` oder `n8n.host`/`n8n.web.port` ab, wenn `transcription.n8n.webhookUrl` leer ist.

## Inhalt

- `services/media-transcription-worker`: kleiner HTTP-Service fuer Audio-Transkription.
- `workflows/n8n_schreib_ai_transcription.json`: n8n Webhook-Workflow.
- `docker-compose.transcription.yml`: Compose-Erweiterung fuer den laufenden Stack.
- `scripts/install-transcription-in-vm.sh`: Installation im VM-Projektordner.

## Installation ueber Shared Auto-Installer

ZIP-Datei in den Shared-Ordner legen:

```text
F:\Virtuelle Maschinenen\Eigene AI\Shared\inbox
```

Der Auto-Installer in der VM entpackt das Paket und startet `install.sh`. Danach ist der Webhook aktiv:

```text
http://SERVER_IP:5678/webhook/schreib-ai/transcribe
```

Der Webhook erwartet Multipart-Felder:

- `file`: Audio-Datei.
- `language`: Enum-Code, zum Beispiel `auto`, `de`, `en`, `fr`, `es`, `it`, `pt`, `nl`, `pl`, `tr`, `ru`, `uk`, `ja`, `ko`, `zh`.
- `outputMode`: `plain` fuer reine Textausgabe oder `structured` fuer JSON mit Zusatzstruktur.
- optional `speakerName1`, `speakerName2`, `narratorVoice3` und weitere Sprecher-/Erzaehlstimmen-Felder.

Bei `structured` nutzt der Worker bzw. der n8n-Workflow die lokale
`mistral-rag`-KI ueber `ollama-gateway`. Die lesbare TXT-Ausgabe beginnt mit
einer kurzen Zusammenfassung und gliedert das Transkript danach nach Stimmen,
zum Beispiel `Stimme A: ...`, `Stimme B: ...` oder `Erzähler: ...`.
Eingetragene Sprecher- und Erzaehlstimmen-Namen werden dabei als Labels
uebernommen. Bei `plain` wird nur der reparierte UTF-8-Transkripttext
zurueckgegeben.

Manuell in der VM geht weiterhin:

```bash
bash install.sh /home/giulian/eigene-ai-rag
```
