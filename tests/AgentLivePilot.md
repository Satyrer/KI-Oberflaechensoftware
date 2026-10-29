# Echter Agentenpilot über die Java-Oberfläche

`AgentLivePilot.java` lädt die produktive `agent_tab.fxml` samt `AgentPaneController`, setzt die sichtbaren Eingabefelder und betätigt die normalen Schaltflächen. Netzwerkzugriffe, Modellwahl, Start, Wiederöffnen, Review und PNG-Prüfung laufen durch den produktiven Client. Der Pilot verwendet weder `AgentFixture` noch einen Ersatz-Gateway. Die Bedienung ist automatisiert; sie ist kein Nachweis eines manuellen menschlichen Klicks.

Die Modi `start` und `inspect` sind ausdrücklich vom Operator auszuführen. Beim Erstellen oder Kompilieren des Harness läuft kein Modellauftrag. `validate` prüft ausschließlich die Eingabekonfiguration und führt keine Netzwerkanfrage aus. Das Skript startet nie eine Übernahme und benötigt keinen Operator-Token.

## Eingabekonfiguration

Eine UTF-8-JSON-Datei enthält genau diese Felder:

```json
{
  "baseUrl": "http://<konfigurierter-host>:5678",
  "projectId": "agent-omp01-i04",
  "modelSelection": "mistral-nemo",
  "tokenFile": "C:/KI Arbeitsbereich/<privater-ordner>/i04-project.token",
  "taskFile": "C:/KI Arbeitsbereich/<pilot-ordner>/task.txt",
  "documentReferences": [
    {"context_id": "<freigegebene-kennung>", "revision": 1, "sha256": "<64-hex-zeichen>"}
  ],
  "requiredDiagrams": [
    {"id": "klassenmodell", "type": "class"},
    {"id": "objektmodell", "type": "object"}
  ],
  "modelSeconds": 3600,
  "actions": 10,
  "rounds": 2
}
```

Das ist ein Schema-Beispiel, keine ausführbare Pilotkonfiguration. Der Projektzugang muss ausschließlich das freigegebene isolierte Pilotprojekt erreichen. Seine Datei liegt außerhalb von `Shared` und außerhalb der Nachweise; der Operator schützt sie auf Windows durch benutzerspezifische Zugriffsrechte. Der Harness liest ihren Inhalt nur in den Arbeitsspeicher, gibt ihn nicht aus und fotografiert erst nach dem Leeren des Tokenfeldes. Die Modellpräferenzdatei ist vom persönlichen Anwendungsprofil getrennt. Die normale Anwendung lädt weiterhin keine Tokens aus Dateien.

## Aufruf

`Run-AgentLivePilot.ps1` benötigt `-Mode`, `-ConfigurationFile` und einen noch nicht bestehenden `-EvidenceDirectory`. `inspect` benötigt zusätzlich `-JobId` mit der nach `start` gesicherten Auftragskennung. Jeder Aufruf erhält einen neuen Nachweisordner; vorhandene Nachweise werden nicht überschrieben. Den Starter aus dem aktiven Java-Projekt verwenden.

`start` lädt den tatsächlichen Modellkatalog, verlangt die ausdrücklich angegebene verfügbare Modell-ID, setzt Dokumentreferenz und Pflichtdiagramme und startet den Auftrag genau einmal. `submitted-request.json` erhält die genaue nicht geheime Anfrage einschließlich Wiederholungskennung. `job.json` sichert die angenommene Kennung und das bestätigte Budget; `client-build.json` bindet den Nachweis an das verwendete Desktop-JAR. Nach dem Trennen dieser UI arbeitet der dauerhafte Serverauftrag weiter. Es gibt keine 90-Sekunden-Grenze für dessen Modelllauf. Die kurzen Wartefristen im Harness betreffen ausschließlich das Verbinden sowie die Antwort auf Start-/Status-/Reviewanfragen.

`inspect` verbindet sich erneut, öffnet genau die übergebene Auftragskennung und speichert den Status. Liegt ein Review vor, lädt er den echten Gesamtdiff, die Prüfnachweise und jedes verfügbare Diagramm über die Oberfläche. `*-displayed.png` ist eine erneute PNG-Kodierung der tatsächlich angezeigten Pixel und daher kein bytegleicher Ersatz für das Original-Rendererartefakt; dessen Hash wird durch den produktiven Client geprüft.

Bei einem unklaren Start wird kein Ersatzauftrag automatisch angelegt. `failure.json` nennt einen begrenzten Fehlercode ohne Konfiguration, Header oder Zugangsdaten. Die tatsächliche Serverkennung muss zunächst geklärt werden, bevor ein weiterer Start freigegeben wird. Gespeicherte Aufgaben, Diffs, Status und Screenshots enthalten fachliche Pilotdaten und sind entsprechend zu behandeln.

Die Nachweise trennen technische UI-Bedienung, tatsächliches Modellresultat und fachliche Abnahme. Ein fehlgeschlagener oder unvollständiger Modelllauf wird durch den Harness nicht als gelöste Aufgabe gewertet. Er verändert keine Original-OMP-Quelldateien und kann keine D-Übernahme auslösen.
