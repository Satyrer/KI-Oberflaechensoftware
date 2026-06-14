# KI-VM-Status

Ursprung: Statusnotiz vom 2026-06-02 kurz vor dem erzwungenen Neustart. Die VM wurde zu diesem Zeitpunkt nicht gestartet.

## Lokale Oberflaeche

- Projektstand ist auf `main` mit letztem Commit `7869fc0 Ergaenze Kontextmenue fuer lokale Chats`.
- JavaFX-Oberflaeche kompiliert lokal mit `mvn -q compile`.
- Lokale Runtime-Konfiguration liegt in `config/local.properties` und ist per `.gitignore` ausgeschlossen.
- Aktuelle UI-Verkabelung nutzt:
  - Ollama-Gateway: `http://192.168.178.41:11435/api/chat`
  - Modell: `mistral-rag:latest`
  - n8n-WebUI/API: `http://192.168.178.41:5678`

## Letzter belastbarer VM-Stack-Stand

Die Diagnose direkt in der VM zeigte:

- Gast-IP: `192.168.178.41`
- Docker-Container liefen:
  - `eigene-ai-open-webui` auf `3000`
  - `eigene-ai-ollama-gateway` auf `11435`
  - `eigene-ai-n8n` auf `5678`
  - `eigene-ai-postgres` healthy
  - `eigene-ai-qdrant` auf `6333`
  - `eigene-ai-minio` auf `9001` und `9100`
  - `eigene-ai-ollama` auf `11434`
- `ufw` war inaktiv.
- Docker publishte die erwarteten Ports auf `0.0.0.0`.
- Innerhalb der VM waren erreichbar:
  - `127.0.0.1:11435/api/tags`
  - `127.0.0.1:11434/api/tags`

## Modelle

Der Ollama-/Gateway-Tags-Check meldete:

- `mistral-rag:latest`
- `nomic-embed-text:latest`
- `mistral:latest`

Damit ist die Oberflaeche auf das richtige Modell vorbereitet.

## Windows-zu-VM-Erreichbarkeit

Erste Tests ohne Freigabe wirkten fehlerhaft, weil die Codex-Sandbox Netzwerkzugriffe blockierte. Mit freigegebenem Netzwerkzugriff waren die VM-Ports erreichbar:

- `192.168.178.41:11435` TCP erfolgreich
- `GET /api/tags` am Ollama-Gateway erfolgreich

## Offener Punkt Vor Neustart

Ein echter Chat-Request an `POST /api/chat` mit `mistral-rag:latest` lief in einen Timeout. Ursache ist noch offen:

- moeglicher kalter Modellstart
- moeglicher Modell-/Ollama-Haenger
- moegliche Last- oder I/O-Probleme in der VM kurz vor dem Neustart

Da die VM aktuell aus bleiben soll, ist dieser Punkt fuer den naechsten geplanten Start vorzumerken.

## VM-Auffaelligkeiten Kurz Vor Neustart

Das VMware-Log zeigte vor dem erzwungenen Neustart deutliche Stabilitaetsprobleme:

- wiederholte `GuestRpcSendTimedOut`
- wiederholte Tools-Heartbeat-Aussetzer
- mehrfach sehr lange Disk-I/O-Zeiten, teils ca. 190 Sekunden
- Versuch eines Soft-Poweroff schlug fehl
- VMware wechselte anschliessend auf Hard-Poweroff

Diese Hinweise passen eher zu einer VM-/Host-/I/O-Blockade als zu einer falschen Java-Oberflaechenverkabelung.

## Aktueller Arbeitsauftrag Aus KI-Projekt

In `F:\Virtuelle Maschinenen\Eigene AI\Aktueller Befehl.txt` steht als naechster fachlicher Auftrag:

- Ein vorschaltbares Modul soll Prompts und Dokumente vor der Weiterleitung an das Modell pruefen.
- Es soll nach ungewoehnlichen Systembefehlen oder System-Rollen-Wechseln suchen.
- Bei Auffaelligkeiten soll das betreffende Dokument isoliert und dem Nutzer mit Fundhinweis angezeigt werden.
- Danach soll der Nutzer entscheiden koennen, ob fortgefahren wird.
- Referenz: Hermes Agent Dokumentation.

Fuer die Java-Oberflaeche bedeutet das perspektivisch:

- lokale Chatoberflaeche bleibt an `ollama-gateway`/`n8n` anschlussfaehig
- spaeter kann ein Vorschaltstatus oder Bestaetigungsdialog eingebaut werden
- Dokument-/Prompt-Pruefung sollte nicht in Git mit geheimen Regeln oder Zugangsdaten vermischt werden

## Naechste Pruefung Beim Geplanten VM-Start

Ohne VM-Start aktuell offen lassen. Beim naechsten geplanten Start:

1. VM-IP pruefen.
2. `GET http://SERVER_IP:11435/api/tags`.
3. Kurzer `POST /api/chat` mit `stream=false`.
4. Falls Chat wieder timed out: Ollama- und Gateway-Logs lesen, bevor die Java-Oberflaeche geaendert wird.

## Nachpruefung 2026-06-03

Die VM wurde spaeter wieder gestartet und vorsichtig erneut geprueft:

- VMware meldete die laufende VM `n8n Server.vmx`.
- Gast-IP blieb `192.168.178.41`.
- `11435` fuer den Ollama-Gateway war von Windows erreichbar.
- `GET /api/tags` am Gateway lieferte weiterhin `mistral-rag:latest`, `nomic-embed-text:latest` und `mistral:latest`.
- Ein vorsichtiger `POST /api/chat` mit kurzer Antwortbegrenzung lieferte `OK`.
- Der direkte Java-Clienttest mit `HttpAiClient` und lokaler `AppConfig` lieferte ebenfalls `OK`.

Die Oberflaeche ist damit fuer den aktuellen VM-Stand korrekt verkabelt. Der KI-Timeout ist konfigurierbar und steht standardmaessig auf `300` Sekunden, damit normale Antworten bis zu fuenf Minuten laufen duerfen und haengende Generierungen danach sauber abbrechen.
