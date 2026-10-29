# Queue-Anzeige

Die gemeinsame Ansicht befindet sich im Haupttab **Queue**. Die Statuszeile
am unteren Fensterrand bleibt auch in Chat, Story, Medien und Agent sichtbar.

- **Diese Anwendung** zeigt Auftraege dieser Sitzung: Chatantworten, Chatimport,
  Story-Kontextaufbau und Fortsetzung, Medienkonvertierung und Transkription.
  Im verbundenen Agentenprojekt werden Warteliste und Auftragsstatus nach Job-ID
  zusammengefuehrt. Fuer Dateien eines Transkriptionsstapels gibt es einzelne Eintraege.
- **Server-Queue** liest die gemeldeten Serverquellen, auch wenn die Auftraege
  ausserhalb dieser Anwendung gestartet wurden. Eine Serie und ihre Unterauftraege
  werden dargestellt; die Zusammenfassung zaehlt nur die untersten Auftraege.
  Lokale Auftraege und Server-Teilschritte werden nicht addiert.

Beendete Auftraege verlassen die aktive Anzeige automatisch. Das gilt auch fuer
Fehlschlaege und fuer Serverauftraege, deren Bearbeitung beendet ist und deren
Ergebnis noch fachlich zu pruefen ist (`awaiting_review`). **Verlauf anzeigen**
holt diese Eintraege zurueck; beide Tabs haben einen eigenen Schalter. Die
Zusammenfassung trennt laufende/wartende Arbeit vom beendeten Verlauf. Ein
Wiederanlaufbedarf (`recovery_required`), ein unklarer Zustand oder ein veralteter
offener Auftrag bleibt dagegen sichtbar. Lokale offene Pruefungen bleiben
ebenfalls sichtbar. Keine Ergebnisdatei und kein Serverauftrag wird geloescht.

Statusfarben kennzeichnen laufende, wartende, zu pruefende, fehlgeschlagene und
abgeschlossene Aufgaben. Fortschrittsbalken erscheinen nur bei gemeldeten
Zaehlerwerten. Bei Bildserien sind dies versuchte Merkmalsgruppen, einschliesslich
Fehlern; die Fehlerzahl steht separat dabei. Ein beendeter Modelllauf ist keine
fachliche Freigabe. Queuepositionen gelten nur innerhalb des jeweiligen queueScope.

Die Anzeige loest keine Modellaufrufe aus und veraendert weder Reihenfolge noch
KI-Payloads. Pause, Abbruch und Freigabe bleiben bei der jeweiligen Verwaltung.
**Verlauf leeren** entfernt nur abgeschlossene lokale Anzeigeeintraege.
Der lokale Verlauf gilt fuer die aktuelle Anwendungssitzung; hoechstens 200
abgeschlossene Eintraege werden gehalten.

## Serverquellen

Alle fuenf Sekunden werden Dateien namens `monitor-snapshot.json` im Statusordner
und in dessen direkten Unterordnern gelesen. Standard:
`C:/KI Arbeitsbereich/Eigene AI/Shared`.
**Statusordner waehlen** aendert die Quelle fuer diese Sitzung. Fuer einen festen
anderen Standard kann Java mit `-Dki.queueMonitorDirectory=...` gestartet werden.

Der Vertrag ist `schreibai.work-monitor.v1`: `generatedAt`, `source`
(`id`, `scope`, `readOnly=true`) und `records`. Datensaetze enthalten stabile IDs,
optional `parentId`, Bereich, Titel, Status, Phase, Zeitstempel und optionalen
Fortschritt (`completed`, `total`, `unit`) sowie `queuePosition` und `queueScope`.
Die GUI liest maximal 64 Dateien mit je 2 MiB und 5000 Datensaetzen. Der Producer
soll den Snapshot atomar ersetzen; Tokens, Prompts, Bilder und Rohantworten
gehoeren nicht in diese Dateien.

Ohne Snapshot wird **kein Serverstand** angezeigt, nicht eine leere Queue.
Unlesbare oder verschwundene Quellen behalten ihren letzten Stand mit einer
Warnung. Offene Auftraege ohne Meldung seit 120 Sekunden gelten als veraltet.
Abgeschlossene Quellen benoetigen keinen dauernden Heartbeat.
Die Anzeige umfasst nur tatsaechlich angeschlossene Quellen. Noch nicht
angebundene Worker werden nicht als vollstaendig erfasst dargestellt.

## Pruefung

`Build-KI.ps1` baut das Desktop-Paket. `tests/Run-WorkQueueTests.ps1` prueft
Statuswechsel, parallele Rueckmeldungen, den Snapshot-Vertrag, Fehler/Veraltung,
Wiederverbindung und JavaFX-Ansichten in normaler und schmaler Groesse mit
synthetischen Dateien. Bestehende Story-, Chat- und Agententests pruefen die
unveraenderten Aufrufe und Arbeitsablaeufe.
