# Benutzerhandbuch KI-Oberflaechensoftware

Dieses Handbuch beschreibt die Nutzung der Java-Oberflaeche fuer die lokale KI-VM. Die Anwendung ist als Chatoberflaeche im Codex-aehnlichen Design aufgebaut und ersetzt perspektivisch die bisherige Web-/n8n-Ansicht fuer den normalen Chatbetrieb.

## 1. Zweck Der Anwendung

Die KI-Oberflaechensoftware dient als lokales Desktop-Interface fuer die vorhandene KI-VM.

Die Oberflaeche bietet:

- lokale Chatverwaltung mit mehreren Chats
- Chatansicht mit Nutzer- und KI-Nachrichten
- automatische Speicherung lokaler Chats als TXT-Dateien
- Export und Loeschen lokaler Chats per Kontextmenue
- Anbindung an den Ollama-Gateway der KI-VM
- Zugriff auf n8n-WebUI-Chats aus `/home/chat`
- Export und Loeschen alter n8n-Chat-Ausfuehrungen
- Import von n8n-WebUI-Chats aus `/home/chat`
- lokaler Import von Markdown-Anhaengen in den jeweiligen Chatordner
- finales Loeschen eines einzeln ausgewaehlten n8n-WebUI-Chats
- Programmieragent mit Projektzugang, Auftragsstatus, UML-Diagrammen, Gesamtdiff und gesondert bestätigter Übernahme; siehe [Agentenansicht](agentenansicht.md)

Die Anwendung speichert Zugangsdaten und lokale Pfade nicht im Git-Repository. Diese Werte liegen in der lokalen Datei `config/local.properties`.

Ausnahme für den Programmieragenten: Projekt- und Operator-Token werden im
jeweiligen verdeckten Eingabefeld erfasst und ausschließlich im Speicher der
Sitzung gehalten. Sie werden nicht in der lokalen Konfiguration gespeichert.

## 2. Voraussetzungen

Fuer die normale Nutzung brauchst du:

- IntelliJ IDEA mit geoeffnetem Projekt `KI Oberflaechensoftware`
- Java 21
- Maven-Projektimport in IntelliJ
- eine konfigurierte lokale Datei `config/local.properties`
- fuer Live-KI-Antworten: laufende KI-VM mit Docker-Stack

Die KI-VM muss nicht fuer lokale Chatverwaltung, Export oder Oberflaechenarbeit laufen. Sie wird nur fuer KI-Antworten und den Zugriff auf n8n-WebUI-Chats benoetigt.

## 3. Projekt Starten

### Start In IntelliJ IDEA

1. In IntelliJ IDEA den aktiven Ordner
   `C:\KI Arbeitsbereich\Eigene AI\Projekt\KI Oberflaechensoftware` oeffnen.
2. Oben rechts die Run Configuration `KI Chat starten` auswaehlen.
3. Auf Start klicken.

Die Konfiguration startet:

```text
kioberflaeche.Main
```

Falls die Run Configuration nicht sichtbar ist:

1. In IntelliJ `Run > Edit Configurations...` oeffnen.
2. Eine neue `Application`-Konfiguration anlegen.
3. Main class setzen auf `kioberflaeche.Main`.
4. Working directory auf das Projektverzeichnis setzen.

Der fruehere Ordner unter `C:\Users\User\IdeaProjects` ist seit dem
8. Oktober 2026 nur noch der erhaltene Rueckfallstand. Die laufende IDE/Anwendung
wurde nicht beendet; beim naechsten normalen Start den neuen Ordner verwenden.
Der automatische Konfigurations-Rueckgriff auf den alten IdeaProjects-Ordner ist
im neuen Stand entfernt. Die mitkopierte lokale Konfiguration des neuen Projekts
und ausdruecklich gesetzte `ki.config`-/`KI_CONFIG`-Pfade bleiben unterstuetzt.

## 4. Lokale Konfiguration

Die echte lokale Konfiguration liegt hier:

```text
config/local.properties
```

Diese Datei wird von Git ignoriert und darf nicht committed werden.

Eine sichere Vorlage liegt hier:

```text
config/local.properties.example
```

Wichtige Felder:

```properties
ki.endpoint=
ki.provider=ollama
ki.host=192.168.178.41
ki.port=11435
ki.path=/api/chat
ki.apiKey=
ki.model=mistral-rag:latest
ki.timeoutSeconds=300

n8n.host=192.168.178.41
n8n.web.port=5678
n8n.web.baseUrl=
n8n.web.email=
n8n.web.password=
n8n.chatWebhook.url=
n8n.chatWebhook.authorization=

chat.directory=chats
```

### KI-Endpunkt

`ki.provider` steuert, wie Antworten erzeugt werden:

- `ollama`: direkte Anfrage an den Ollama-/Mistral-Gateway
- `n8n`: Anfrage an einen n8n Chat Trigger Webhook

Fuer die n8n-Variante muss im Chat Trigger des Workflows `Make Chat Publicly Available` aktiv sein. Danach die im Node angezeigte `Chat URL` in `config/local.properties` setzen:

```properties
ki.provider=n8n
n8n.chatWebhook.url=http://SERVER_IP:5678/webhook/DEINE-CHAT-URL
n8n.chatWebhook.authorization=
```

Wenn der Chat Trigger Authentifizierung verwendet, kann `n8n.chatWebhook.authorization` einen kompletten Authorization-Header-Wert enthalten, zum Beispiel `Basic ...` oder `Bearer ...`.

Wenn `ki.endpoint` leer ist, baut die Anwendung den Endpunkt aus Host, Port und Pfad:

```text
http://ki.host:ki.port/ki.path
```

Aktuell vorgesehen:

```text
http://192.168.178.41:11435/api/chat
```

### Timeout

`ki.timeoutSeconds=300` bedeutet:

- Die KI darf bis zu 5 Minuten an einer Antwort arbeiten.
- Wenn nach 5 Minuten keine Antwort kommt, bricht die Anfrage ab.
- Die Oberflaeche zeigt dann eine Fehlermeldung im Chat.

Da die Anwendung aktuell nicht streamt, kann sie waehrend einer Antwort nicht sicher unterscheiden, ob das Modell noch arbeitet oder haengt. Die 5-Minuten-Grenze ist deshalb die definierte Abbruchlogik.

### Chat-Speicherordner

Lokale Chats werden standardmaessig hier gespeichert:

```text
chats/
```

Der Ordner wird von Git ignoriert.

## 5. Aufbau Der Oberflaeche

Die Oberflaeche besteht aus drei Hauptbereichen.

### Linke Seitenleiste

Die linke Seitenleiste zeigt lokale Chats.

Elemente:

- `Chats`: Ueberschrift der lokalen Chatliste
- `Neuer Chat`: erstellt einen neuen lokalen Chat
- Chatliste: alle lokal gespeicherten Chats

### Mittlerer Chatbereich

Der mittlere Bereich zeigt den aktuell ausgewaehlten Chat.

Elemente:

- Kopfzeile mit Chattitel und Status
- Nachrichtenbereich
- Eingabefeld
- Button `Senden`

Nutzer-Nachrichten und KI-Nachrichten werden als getrennte Chat-Bubbles angezeigt.

### Rechter Bereich: n8n WebUI Chats

Der rechte Bereich liest die n8n-WebUI-Chats aus der Ansicht `/home/chat`.

Elemente:

- `WebUI-Chats laden`
- Liste der WebUI-Chats
- WebUI-Vorschau
- `Lokal importieren`
- `Final loeschen`
- Statusanzeige

Diese Funktionen verwenden die normale n8n-WebUI auf Port `5678`. Dafuer muessen `n8n.web.email` und `n8n.web.password` in `config/local.properties` gesetzt sein.

## 6. Lokale Chats Nutzen

### Neuen Chat Erstellen

1. Links auf `Neuer Chat` klicken.
2. Ein leerer Chat wird erstellt.
3. Sobald du die erste Nachricht sendest, wird der Chat automatisch betitelt.

### Nachricht Senden

Es gibt zwei Moeglichkeiten:

- Text eingeben und auf `Senden` klicken.
- Text eingeben und `Enter` druecken.

Fuer Zeilenumbrueche im Eingabefeld:

```text
Shift + Enter
```

Nach dem Senden:

1. Deine Nachricht wird lokal gespeichert.
2. Die Oberflaeche sendet die Anfrage an den KI-Endpunkt.
3. Der Status zeigt `KI antwortet...`.
4. Die KI-Antwort wird im Chat gespeichert.

### Wenn Die KI Nicht Erreichbar Ist

Wenn die VM aus ist oder der Endpunkt nicht erreichbar ist:

- Die Nutzer-Nachricht bleibt lokal gespeichert.
- Im Chat erscheint eine Systemmeldung mit dem Fehler.
- Du kannst weiterhin lokale Chats verwalten.

## 7. Kontextmenue Fuer Lokale Chats

Wenn du in der linken Chatliste auf einen Chat rechtsklickst, oeffnet sich ein Kontextmenue.

Aktuelle Funktionen:

- `Chat exportieren`
- `Chat loeschen`

### Lokalen Chat Exportieren

1. Rechtsklick auf einen Chat in der linken Liste.
2. `Chat exportieren` auswaehlen.
3. Es oeffnet sich ein kleines Exportfenster.
4. Auf `Ordner waehlen` klicken.
5. Zielordner auswaehlen.
6. Auf `Exportieren` klicken.

Der Chat wird als TXT-Datei im Zielordner gespeichert.

Wenn bereits eine Datei mit gleichem Namen existiert, erzeugt die Anwendung automatisch einen eindeutigen Namen, zum Beispiel:

```text
Mein Chat.txt
Mein Chat-2.txt
Mein Chat-3.txt
```

### Lokalen Chat Loeschen

1. Rechtsklick auf einen Chat in der linken Liste.
2. `Chat loeschen` auswaehlen.
3. Es oeffnet sich ein Bestaetigungsdialog.
4. Erst nach Bestaetigung wird der Chat geloescht.

Das Loeschen entfernt die lokale TXT-Datei aus dem Chat-Speicherordner.

Wenn der letzte Chat geloescht wird, legt die Anwendung automatisch einen neuen leeren Chat an.

## 8. n8n WebUI Chats Importieren Und Loeschen

Die n8n-WebUI-Chats sind die Chats aus:

```text
http://SERVER_IP:5678/home/chat
```

Sie werden ueber die authentifizierte n8n-WebUI-API geladen.

### Zugangsdaten Setzen

In `config/local.properties` muessen lokal gesetzt sein:

```properties
n8n.web.port=5678
n8n.web.baseUrl=
n8n.web.email=DEINE_N8N_EMAIL
n8n.web.password=DEIN_N8N_PASSWORT
```

Wenn `n8n.web.baseUrl` leer bleibt, nutzt die Anwendung `n8n.host` und `n8n.web.port`.

### WebUI-Chats Laden

1. Sicherstellen, dass die KI-VM und n8n laufen.
2. Rechts in der WebUI-Sektion auf `WebUI-Chats laden` klicken.
3. Die Anwendung meldet die Anzahl gefundener Chats.

### WebUI-Chat Lokal Importieren

1. Einen WebUI-Chat auswaehlen.
2. Auf `Lokal importieren` klicken.
3. Der Chat erscheint links in der lokalen Chatliste.

Der n8n-Chat bleibt beim Import in n8n erhalten.

Markdown-Anhaenge werden, sofern n8n sie ueber die Chat-API bereitstellt, im lokalen Chatordner abgelegt:

```text
chats/CHAT-ID/
chats/CHAT-ID/chat.txt
chats/CHAT-ID/ANHANG.md
```

### WebUI-Chat Final Loeschen

1. Einen WebUI-Chat auswaehlen.
2. Auf `Final loeschen` klicken.
3. Den Bestaetigungsdialog bestaetigen.

Geloescht wird nur der aktuell ausgewaehlte WebUI-Chat. Bereits lokal importierte Chatordner bleiben erhalten.

## 9. Speicherformat Lokaler Chats

Lokale Chats werden als TXT-Dateien im Ordner `chats/` gespeichert.

Das Format enthaelt:

- Chat-ID
- Titel
- Nachrichtenbloecke
- Senderrolle
- Zeitstempel
- Nachrichtentext

Beispielstruktur:

```text
CHAT_ID: ...
TITLE: ...

<<<USER|2026-06-03T12:00:00>>>
Nachricht
<<<END>>>

<<<ASSISTANT|2026-06-03T12:00:05>>>
Antwort
<<<END>>>
```

Der Ordner `chats/` ist in `.gitignore` eingetragen.

Importierte n8n-WebUI-Chats koennen als eigener Ordner unter `chats/` liegen. Die Anwendung liest sowohl alte flache TXT-Dateien als auch diese Chatordner.

## 10. Sicherheit Und Git

Folgende Daten duerfen nicht ins Git:

- `config/local.properties`
- `chats/`
- Tokens
- API-Keys
- lokale Chatverlaeufe
- `target/`
- IntelliJ-Arbeitszustand wie `.idea/workspace.xml`

Die Anwendung maskiert Tokens in der Konfigurationsausgabe. Trotzdem sollte die lokale Konfigurationsdatei nicht geteilt werden.

## 11. Typische Fehlerbilder

### Fehler: KI antwortet nicht

Moegliche Ursachen:

- VM ist aus.
- Docker-Stack in der VM laeuft nicht.
- IP der VM hat sich geaendert.
- Port `11435` ist nicht erreichbar.
- Modell startet kalt und braucht laenger.
- Modell haengt und erreicht den 5-Minuten-Timeout.

Pruefen:

```text
http://SERVER_IP:11435/api/tags
```

Wenn dieser Endpunkt nicht erreichbar ist, liegt das Problem vor der Java-Oberflaeche.

### Fehler: Chat wird nicht exportiert

Moegliche Ursachen:

- Kein Zielordner ausgewaehlt.
- Zielordner ist nicht beschreibbar.
- Dateisystemrechte verhindern das Schreiben.

### Fehler: Lokaler Chat taucht nach Loeschen wieder auf

Die Anwendung verhindert, dass eine spaet eintreffende KI-Antwort einen bereits geloeschten Chat erneut speichert. Falls ein Chat trotzdem wieder auftaucht, pruefen:

- ob die Datei im `chats/`-Ordner manuell noch vorhanden ist
- ob mehrere Instanzen der Anwendung parallel laufen

## 12. Empfohlener Ablauf Beim Arbeiten

### Ohne KI-VM

1. Anwendung starten.
2. Lokale Chats ansehen, exportieren oder loeschen.
3. UI und Dokumentation bearbeiten.
4. Keine Live-KI-Antworten erwarten.

### Mit KI-VM

1. VM starten.
2. Warten, bis Docker-Container bereit sind.
3. Anwendung starten.
4. Kurze Testnachricht senden.
5. Erst danach laengere Prompts verwenden.

Empfohlene erste Testnachricht:

```text
Antworte exakt mit OK.
```

## 13. Aktuelle Bekannte Grenzen

- Die Chatantworten werden nicht gestreamt.
- Die Oberflaeche erkennt waehrend einer laufenden Antwort nicht, ob das Modell noch aktiv arbeitet.
- Der Timeout ist deshalb die technische Grenze fuer haengende Antworten.
- n8n-WebUI-Chats werden bei Bedarf lokal importiert; die Originale bleiben bis zum finalen Loeschen in n8n.
- Video-zu-Sound benoetigt `ffmpeg` lokal oder einen konfigurierten `media.ffmpeg.path`.
- Sound-zu-TXT benoetigt entweder einen n8n-Webhook oder ein lokales Whisper-kompatibles Kommando.
- Die geplante Vorschaltpruefung fuer Dokumente und Systemrollen-Wechsel ist noch nicht in der Java-Oberflaeche umgesetzt.

## 14. Media und Transkription

Der Tab `Video zu Sound` wandelt Videodateien wie MP4, MOV, MKV oder WEBM in Sounddateien um. Quelle, Zielformat, Zielordner und Ausgabedatei sind frei waehlbar. Die Umwandlung laeuft ueber `ffmpeg`.

Der Tab `Sound zu TXT` kann Audiodateien direkt in eine TXT-Datei transkribieren. Zusaetzlich kann ein Videofile direkt ausgewaehlt werden; die Oberflaeche extrahiert dann temporaer Audio und gibt dieses an die Transkription weiter.

Fuer die Transkription gibt es zwei Betriebsarten:

1. `transcription.n8n.webhookUrl` ist gesetzt: Die Datei wird an n8n in der VM gesendet.
2. Kein Webhook gesetzt: Die App nutzt `transcription.command` oder als Standard `whisper` aus dem PATH.

Weitere Details stehen in [Media- und Transkriptionsfunktionen](media-transkription.md).

## 15. Fuer Entwickler

Wichtige Dateien:

```text
src/main/java/kioberflaeche/Main.java
src/main/java/kioberflaeche/ui/MainApplication.java
src/main/java/kioberflaeche/controller/ChatController.java
src/main/resources/fxml/chat_window.fxml
src/main/resources/css/chat.css
src/main/java/kioberflaeche/ai/HttpAiClient.java
src/main/java/kioberflaeche/config/AppConfig.java
src/main/java/kioberflaeche/storage/ChatStore.java
src/main/java/kioberflaeche/controller/MediaConversionController.java
src/main/java/kioberflaeche/controller/TranscriptionController.java
src/main/java/kioberflaeche/media/MediaConverter.java
src/main/java/kioberflaeche/media/TranscriptionService.java
```

Build pruefen:

```bash
mvn -q compile
```

Konfigurationsreihenfolge:

1. `src/main/resources/application.properties`
2. `config/local.properties`
3. System Properties
4. Environment Variables

Wichtige Environment Variables:

```text
KI_ENDPOINT
KI_HOST
KI_PORT
KI_PATH
KI_MODEL
KI_TIMEOUT_SECONDS
KI_CHAT_DIRECTORY
N8N_HOST
MEDIA_FFMPEG_PATH
TRANSCRIPTION_COMMAND
TRANSCRIPTION_N8N_WEBHOOK_URL
TRANSCRIPTION_N8N_AUTHORIZATION
TRANSCRIPTION_DEFAULT_LANGUAGE
KI_CONFIG
```

## 16. Kurzfassung

1. IntelliJ starten.
2. Run Configuration `KI Chat starten` ausfuehren.
3. Links Chat waehlen oder `Neuer Chat`.
4. Nachricht unten eingeben.
5. Mit `Enter` oder `Senden` abschicken.
6. Rechtsklick auf lokale Chats fuer Export oder Loeschen.
7. Rechte n8n-WebUI-Verwaltung nur verwenden, wenn die KI-VM laeuft.
8. `Video zu Sound` fuer MP4/MOV/MKV zu MP3/WAV/M4A nutzen.
9. `Sound zu TXT` fuer Audio- oder Video-Transkripte nutzen.


## 17. Story-Arbeitsbereich (8. Oktober 2026)

Der Tab `Schreib-AI` fuehrt Projekt/Quellen, konkreten Auftrag und sichtbare
Vorschlagspruefung zusammen. Details, Vorher-/Nachher-Zuordnung und Fehlerbehandlung
stehen in der [Dokumentation zum Story-Arbeitsbereich](story-arbeitsbereich.txt).

1. Ein bestehendes Projekt waehlen oder ein neues Projekt mit Namen und freiem
   Briefing anlegen. Das Briefing bleibt als `sources/story-briefing.md` erhalten.
2. `Kontext aus Quellen aufbauen / aktualisieren` oder `Szene schreiben / fortsetzen`
   auswaehlen. Alle regulaeren Textquellen und vorhandenen Kontextdateien werden
   vollstaendig an den Auftrag uebergeben; bekannte automatische Kopien und
   interne Journale werden ausgeschlossen.
3. Sprache und kurzen Auftrag festlegen. Der Auftrag darf inklusive optionalem
   Dateiverweis 1200 UTF-8-Bytes enthalten. Lange Inhalte gehoeren in die Quellen.
4. Bei Szenen ein Ziel unter `manuscript/` mit `.md` angeben. Die UI schlaegt eine
   neue freie Datei vor. Ein vorhandenes Ziel wird erst nach Pruefung vollstaendig
   ersetzt; es gibt kein automatisches Anhaengen.
5. Im Ergebnis Original und editierbaren Vorschlag vergleichen, dann einzelne
   oder alle offenen Vorschlaege uebernehmen. Zwischenzeitlich anderweitig
   geaenderte Dateien werden als Konflikt erhalten.

Die frueheren Einzelangaben `Kapitel`, `Timeline-Zeitpunkt` und `Schritte` sowie
getrennte Entwurfs-/Analyse-/Fortsetzungsformulare entfallen im Hauptablauf.
Sachangaben gehoeren in Quellen/Kontext; Arbeitsziele in den gemeinsamen Auftrag.

Fehlgeschlagene Auftraege und ihre empfangenen Analysenachweise bleiben als eigene
Zwischenstaende erhalten. Die Liste bietet neueste Auftraege zuerst; `Laden`
oeffnet genau den gewaehlten Stand. Erfolgreich angenommene Dateien erhalten
gezielte Backups. Unterbrochene Uebernahmen lassen sich anhand des gesicherten
Schreibinhalts abschliessen. Die ausfuehrliche Dokumentation beschreibt Grenzen
bei Netzwerkabbruch und laufenden Modellauftraegen.

Eine formal gueltige negative Modellbewertung kennzeichnet den Vorschlag zur
menschlichen Pruefung (`needs_human_review`). Sie fuehrt weder zu automatischer
Umschreibung noch allein zum Abbruch. Der Hinweis bleibt auch nach dem Laden
des Zwischenstands sichtbar. Technische Fehler, zum Beispiel ungueltige Antworten
oder Zeitueberschreitungen, verhindern weiterhin ein uebernehmbares Ergebnis.
Jeder Vorschlag muss bewusst anhand von Original und Entwurf uebernommen werden.

Standardarbeitsplatz: `C:\KI Arbeitsbereich\SchreibAI\Arbeitsprojekte`.
Lokales Vorbereiten und Pruefen ist ohne VM moeglich; fuer KI-Auftraege muss die VM
laufen. Umfangreiche CPU-Auswertungen koennen lange dauern. Englisch bleibt die
anfängliche Ausgabesprache, eine bewusste Auswahl wird pro Projekt gespeichert.

## 18. Modelle gezielt auswaehlen oder automatisch zuordnen

Chat, Story-Auftrag und strukturierte Transkription bieten ab 16. Oktober 2026
eine Modellliste aus dem laufenden System. `Automatisch (nach Aufgabe)` erlaubt
die serverseitige Zuordnung; eine ausdrueckliche Modellwahl wird verbindlich
angefordert und bei fehlender Verfuegbarkeit nicht still ersetzt. `Liste laden`
aktualisiert nur die Auswahl, ohne Modelle in den Arbeitsspeicher zu laden.

Im Chat kann die Aufgabenart automatisch erkannt oder als Gespraech,
Programmierung/Fehlersuche bzw. Guard-Untersuchung vorgegeben werden. Vorhandene
Guard-Regeln bleiben eine separate Einstellung. Bei Storys sind unterschiedliche
Modelle je Auswertungs-/Schreib-/Pruefphase im automatischen Modus moeglich.
Das tatsaechlich verwendete Modell und seine Begruendung erscheinen am Ergebnis.

Die bewusste Wahl bleibt nach Neustart erhalten; Storyprojekte haben jeweils
ihre eigene Modellwahl. Die Spracherkennung bleibt bei Whisper small/large-v3;
die neue Textmodellwahl betrifft nur die optionale strukturierte Ausgabe.
Details, Fehlerverhalten, Starter und Tests: [Modellwahl](modellwahl-20261016.txt).
