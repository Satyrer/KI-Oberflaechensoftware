# Programmieragent in der Java-Oberfläche

Stand: 25. Oktober 2026, I-03 mit Qualitätsanzeige, Stufendiagnose, Aufgabenwarteliste und 15 normalen Aktionen mit getrennten UML-Budgets. Der I-04-Pilotharness bleibt separat.

Der Tab **Programmieragent** verbindet die Oberfläche mit dem vorhandenen
Agentendienst. Aufträge werden auf dem Server ausgeführt. Das Öffnen der
Anwendung oder des Tabs startet keinen Agentenauftrag.

## Einen Auftrag ausführen

1. Projekt-Zugangstoken in das verdeckte Feld eingeben und **Verbinden** wählen.
   Die Liste zeigt ausschließlich die damit freigegebenen Projekte.
2. Projekt auswählen, Aufgabe beschreiben und Modell wählen. Die automatische
   Wahl und die gezielte Auswahl verwenden den vorhandenen Modellkatalog für
   Programmieraufgaben. Eine nicht verfügbare ausdrückliche Wahl wird nicht
   still durch ein anderes Modell ersetzt.
3. Budget bei Bedarf ändern. Standard sind **1.800 Sekunden Agentenzeit**,
   **15 normale Modellversuche** und **zwei Reparaturrunden**. Neue Aufträge
   erhalten zusätzlich höchstens **drei UML-Versuche je gefordertem Diagramm**,
   maximal vier Diagramme. Erst die validierte Zuordnung eines Modellaufrufs
   zu einem solchen Diagramm belastet dessen UML-Budget; ungültige Antworten
   und Lektüreaufrufe belasten das normale Budget. Bei eingeschaltetem UML ohne
   Pflichtdiagramme stehen bis zu vier dynamisch gebundene Diagramm-IDs mit
   jeweils drei Versuchen bereit. Deren Zähler erscheinen nach der ersten
   validierten Zuordnung; UML ausgeschaltet lässt nur das normale Budget zu.
   Das gemeinsame Zeitbudget
   umfasst die verbrauchte Zeit der Agentenaufrufe und ihrer Verarbeitung
   einschließlich Reparaturen. Manuelle Wartezeit auf Tests und Prüfung zählt
   nicht dazu. Es gibt keine zusätzliche 90-Sekunden-Grenze für den Auftrag.
4. Optional geprüfte Dokumentfreigaben eintragen. Jede Zeile enthält
   `Kontext-ID Revision SHA-256`, getrennt durch Leerzeichen. Die Revision und
   Prüfsumme müssen zur tatsächlich freigegebenen Fassung gehören. Ungeprüfte
   Originaltexte oder versteckte KI-Anweisungen werden damit nicht freigegeben.
5. Wenn benötigt, **UML-Werkzeug für diesen Auftrag einschalten**. Verpflichtende
   Diagramme werden zeilenweise als `Kennung Typ` angegeben, beispielsweise
   `objektmodell object` oder `klassenmodell class`. Die Funktion muss für das
   Projekt serverseitig freigeschaltet sein.
6. **Auftrag starten**. Unter **Status** stehen Auftrags-ID, Budget und Aktionen.
   Die ID für eine spätere Wiederöffnung aufbewahren.

Nach einer fehlenden Startantwort bleibt der Auftrag im Speicher unverändert.
**Start erneut anfragen** sendet denselben Inhalt mit derselben
Wiederholungskennung; dadurch soll kein zweiter Auftrag entstehen. Erst
**Neuer Auftrag** verwirft diesen ungeklärten Start nach gesonderter Rückfrage.
Ein Programmneustart oder bewusstes Trennen löscht den lokalen Sitzungszustand.

## Aufgaben kontrolliert in der Warteliste bearbeiten

Unter **Auftrag** lässt sich der vollständig ausgefüllte Entwurf mit
**Zur Warteliste hinzufügen** einreihen. Dokumentfreigaben, Modellwahl,
UML-Anforderungen und Budgets werden genauso gebunden wie beim direkten Start.
Auch während eines laufenden Auftrags bleibt das Formular für weitere
Wartelistenaufgaben bearbeitbar. Dies ändert den bereits laufenden Auftrag nicht.

Der Tab **Warteliste** zeigt die zum Projektzugang gehörende Warteliste:

- **Fortsetzen** aktiviert die serielle Freigabe; **Pausieren** hält weitere
  Freigaben an. Neue Wartelisten beginnen pausiert. Ein aktiver Auftrag läuft
  beim Pausieren zu Ende.
- **Eine Aufgabe starten** gibt bei pausierter Warteliste genau den nächsten
  Auftrag frei. Die Pause bleibt bestehen. Ein noch aktiver oder nach einer
  Unterbrechung zu prüfender Auftrag blockiert die nächste Freigabe.
- **Wartende Auswahl entfernen** betrifft ausschließlich den ausgewählten
  wartenden Eintrag; **Alle Wartenden entfernen** leert nur diesen Teil der
  Warteliste. Bereits freigegebene Jobs, Prüfstände und Belege bleiben erhalten.
- **Auftragsstatus öffnen** öffnet einen freigegebenen Auftrag im vorhandenen
  Statusbereich. **Auftrag abbrechen** dort bleibt die getrennte Aktion für
  einen aktiven Job. Prüfstand und Übernahme behalten ihre bisherige Freigabe.

Scheitert die Freigabe beispielsweise an geänderten Quellen, pausiert der
Server die Warteliste und belässt die betroffene Aufgabe dort. Die Oberfläche
zeigt Fehlercode und Eintrags-ID. Nach einem Konflikt lädt sie diesen Stand
erneut. Nach Prüfung kann **Fortsetzen** die Freigabe erneut versuchen oder
der wartende Eintrag gezielt entfernt werden.

Bei einer fehlenden Antwort auf eine Änderung bleibt die konkrete Operation
mit ihrer Kennung im Speicher. **Aktion erneut anfragen** wiederholt exakt
diese Operation: Ein Hinzufügen darf keinen zweiten Eintrag erzeugen; eine
erneute Leerung darf keine später hinzugefügten Aufgaben entfernen; ein erneuter
Einzelstart darf keinen weiteren Auftrag freigeben. Ein eindeutig abgewiesener
HTTP-Aufruf gibt die Bedienung wieder frei. Vor Trennen oder Programmneustart
ungeklärte Aktionen möglichst so auflösen; diese beiden Aktionen verwerfen den
lokalen Wiederholungszustand. Erneutes Verbinden lädt den Serverstand.

Die Warteliste fragt bei offenen Einträgen alle zehn Sekunden ausschließlich
Statusdaten ab. Nach drei Fehlern pausiert diese Abfrage bis zur manuellen
Aktualisierung. Es entstehen keine Modellaufrufe durch die Anzeige. Angezeigt
werden wartende und aktuell freigegebene Aufgaben, keine vollständige Historie.
Die Auftrags-ID im Detailbereich für spätere Prüfungen aufbewahren. Frühere
Ergebnisse bleiben über **Status → Vorhandenen Auftrag öffnen** zugänglich.

Der Statusbereich trennt bei der neuen Budgetregel normale Modellversuche,
Gesamtaufrufe und UML-Versuche pro Diagramm. Historische Jobs behalten ihre
bisherige Budgetauslegung und werden entsprechend gekennzeichnet.

## Laufzeit, Unterbrechung und Wiederaufnahme

Die Oberfläche fragt aktive Aufträge etwa alle zwei Sekunden ab. Jede einzelne
Netzabfrage hat eine begrenzte Wartezeit; **es gibt keine zusätzliche Gesamtfrist
für das Statuspolling**. Langsame Modellaufrufe werden daher nicht nach 90
Sekunden beendet.

- **Auftrag abbrechen** fordert den serverseitigen Abbruch an und zeigt danach
  dessen bestätigten Zustand.
- **Wiederaufnehmen** ist ausschließlich beim Zustand `recovery_required`
  verfügbar und erfordert einen bewussten Klick.
- Hat die Dateiübernahme bereits begonnen oder wurde sie unterbrochen, bleiben
  Abbruch und Wiederaufnahme gesperrt. Der Hinweis fordert eine manuelle Prüfung
  des Übernahmejournals durch den Operator; der Auftrag wird nicht erneut abgespielt.
- Nach drei fehlgeschlagenen Statusabfragen pausiert nur die automatische
  Abfrage. **Status laden** versucht erneut, den tatsächlichen Zustand zu lesen.
- **Trennen** löscht den Zugang aus der Ansicht. Der Serverauftrag läuft weiter.
  Für die Rückkehr erneut verbinden, Projekt wählen und unter **Status** die
  Auftrags-ID öffnen.

## Ergebnis und Übernahme prüfen

Nach der Projektauswahl zeigt die Oberfläche ein serverseitig eingerichtetes
Aufgabenprüfprofil, beispielsweise `omp01-v1`. Es ergänzt die technischen
Build-/Testprüfungen. Fehlende Bestandteile und Platzhalter werden dadurch
gesondert bewertet. Die Detailkriterien bleiben im jeweiligen Profil und
Prüfbericht festgehalten.

Der Aufgabenstatus zeigt die Qualitätsprüfung auch dann, wenn kein D-Review
entstanden ist. Fehlgeschlagene Prüfkennungen sind sichtbar; sie bedeuten
**keine fachliche Abnahme**. Fehlt ein Qualitätsbericht, lautet die Anzeige
„noch keine Prüfung belegt“. Ein bestandenes Profil wird ausdrücklich auf
seinen festgelegten Prüfumfang begrenzt. Die fachliche Gesamtprüfung und die
allgemeine Schreibfreigabe Q-05 werden daraus nicht abgeleitet.

**Prüfstand laden** holt den vollständigen Gesamtdiff, Testresultate,
Dateiänderungen, Hinweise und die Diagrammliste. Die Ansicht stellt Texte
ausschließlich als Text dar. Diagramme werden als PNG nach Prüfung von
Dateigröße, Bildmaßen, Identität und SHA-256 geladen; es werden keine
HTML-Inhalte oder fremden Bildadressen geöffnet.

Der Bereich **Tests** ergänzt den Profilstatus, tatsächlichen Runnerstatus,
die Information, ob Verhaltenstests ausgeführt wurden, und den Hash des
gespeicherten Qualitätsberichts. Widersprüche zwischen dem Jobstatus und dem
Review sowie zwischen fehlgeschlagenen Prüfungen und behaupteter Abnahme
werden als ungültige Antwort abgewiesen.

Für eine Übernahme:

1. Unter **Prüfung** Gesamtdiff, Tests, Hinweise und benötigte Diagramme ansehen.
2. Unter **Übernahme** die Prüfung bestätigen, Prüfernamen und den gesonderten
   Operator-Zugang eingeben.
3. Den angezeigten Freigabetext für genau diesen Review eintragen.
4. **Geprüfte Änderungen übernehmen …** wählen und den konkreten Review im
   abschließenden Dialog bestätigen.

Die Schaltfläche bleibt ohne aktuelle, passende Freigabe gesperrt. Projektzugang
und Operatorzugang werden getrennt verwendet. Die letzte Bestätigung verändert
die freigegebenen Projektdateien; der Server prüft den Review und die
Dateikonflikte erneut. Nach erfolgreicher Übernahme zeigt die Oberfläche eine
Transaktions-ID. Der abgeschlossene Auftrag lässt sich erneut öffnen und
prüfen; eine zweite Übernahme wird nicht angeboten.

## Konfiguration und Grenzen

Die Agentenadresse entsteht aus der vorhandenen
`AppConfig.n8nSchreibAiBaseUrl` und `/webhook/schreib-ai/agent`.
Die Agentenansicht liest keine Zugangsdaten aus `local.properties` und schreibt
keine Token in Einstellungen, Chats, Dateien oder Protokolle. Beide Token
verbleiben ausschließlich im Speicher der Sitzung. Nur die Modellpräferenz
wird wie bei den anderen Modellfeldern gespeichert.

Die Anzeige übernimmt keine vom Server gelieferten Status- oder Review-URLs.
Sie verwendet feste Pfade auf derselben eingerichteten Adresse und folgt
keinen Weiterleitungen. Antworten werden in Größe und Struktur begrenzt;
mehrdeutiges JSON mit doppelten Schlüsseln wird abgelehnt. Ein veraltetes
Antwortergebnis darf nach Trennen oder Auftragswechsel keine Ansicht und keine
Freigabe wiederherstellen.

I-03 ist eine bedienbare erste Oberfläche, keine allgemeine Qualitätsfreigabe
des Agenten. Die fachliche Qualität, insbesondere bei OMP-Aufgaben, muss weiter
an konkreten Ergebnissen geprüft werden. Die umfassendere Abnahme Q-05 und die
Verbesserung der Agentenkompetenzen bleiben im Backlog.

## Entwicklerprüfung

Seit der Diagnoseergänzung vom 22. Oktober 2026 zeigt **Status → Belegte
Prüfstufen** acht getrennte Bereiche: Quellen und Modellkontext, Werkzeugaufruf
und Schema, Diagrammerzeugung, Änderungsvorschlag, Kompilierung, Verhaltenstests,
Aufgabenprüfung im festgelegten Profil und Übernahme ins Projekt. Benötigte,
gelesene und tatsächlich im Modellkontext belegte Quellen werden, sofern vom
Controller geliefert, gezählt. Die vollständigen SHA-256-Belege stehen im
aufklappbaren Bereich darunter.

Ein früher Werkzeugfehler lässt noch nicht gestartete Kompilierung und Tests
ausdrücklich auf **nicht ausgeführt / noch nicht belegt**. Erfolgreiches Rendern
belegt keine semantisch richtige Lösung. Ein gültiger Änderungsvorschlag und
eine bestandene Profilprüfung erteilen keine Übernahmefreigabe. Die bestehende
gesonderte Operatorfreigabe bleibt maßgeblich.

Ältere Aufträge ohne `diagnostics` bleiben lesbar und zeigen den Hinweis, dass
die Stufenübersicht nicht vorhanden ist. Die Oberfläche übernimmt nur bekannte
Statuswerte, begrenzte Zähler und formal gültige Hashwerte aus der separaten
Backend-Eigenschaft. Rohentwürfe, Modelltexte und zusätzliche unbekannte Felder
werden nicht in diese Ansicht übernommen. Die Backend-Verifikation der
zugrunde liegenden Belege bleibt erforderlich; die UI führt keine Tests aus.

Nach `Build-KI.ps1` führt `tests/Run-AgentTests.ps1` vier eigenständige
Regressionen aus: HTTP-Vertrag und JavaFX/FXML für die bisherige Jobansicht
sowie HTTP-Vertrag und JavaFX/FXML für Warteliste und Budgets, mit Screenshots.
Alle verwenden ausschließlich synthetische Daten und einen
Loopback-Testserver. Es werden keine Modelle gestartet und keine Nutzerprojekte
verändert.

Geprüft werden unter anderem striktes JSON, feste Pfade, Zugangstrennung,
begrenzte Antworten, Diff-/PNG-Identität, Übernahmebindung, unveränderte
Startwiederholung, langer Auftragsstatus, Wiederaufnahme, Abbruch,
Bedienbarkeit während einer verzögerten Antwort und das Verwerfen später
Antworten nach dem Trennen. Die vorhandenen Modell-/Story-/Budgettests bleiben
zusätzlich Bestandteil der Integrationsprüfung.

Die Wartelistentests prüfen insbesondere unveränderte Wiederholungskennungen,
separaten Einzelstart, Pause/Fortsetzen, Entfernen nur wartender Aufgaben,
Erhalt aktiver Jobs beim Leeren, Projekt-/Identitätsbindung, Nachkommastellen
der Unix-Zeitstempel, Gesamtbudgets über zehn Aufrufe sowie alte Statusantworten.
Der produktive Workflow `eigene-ai-rag/workflows/n8n_coding_agent.json` enthält
die festen Queue-Endpunkte. `eigene-ai-rag/tests/test_coding_agent_workflow.py`
prüft deren tatsächliche Code-Knoten offline mit Node.js; die Referenzkopie
unter `eigene-ai-rag/docs/workflows` ist keine eigene Produktionsquelle.

`tests/AgentLivePilot.md` beschreibt zusätzlich einen ausdrücklich gestarteten
Pilot mit echter Agentenadresse und echtem Modell. Sein Harness bedient die
produktive FXML-Ansicht automatisiert. Er ersetzt keinen menschlichen
Prüfklick, nimmt keine Dateien ins Originalprojekt auf und startet niemals
automatisch beim Kompilieren. Ein eigener privater Projektzugang wird nur
für diesen Operator-Test aus einer Datei gelesen; die normale Anwendung
ändert ihre Tokenbehandlung nicht.
