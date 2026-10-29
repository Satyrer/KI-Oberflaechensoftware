# Dokumentation

Dieser Ordner enthaelt die projektnahe Dokumentation zur KI-Oberflaechensoftware.

Aktiver Projektstand seit 8. Oktober 2026:
`C:\KI Arbeitsbereich\Eigene AI\Projekt\KI Oberflaechensoftware`.
In IntelliJ diesen Ordner oeffnen und `KI Chat starten` verwenden.
Die kurze Startanleitung steht in [HIER-STARTEN.txt](../HIER-STARTEN.txt).
Der fruehere IdeaProjects-Ordner bleibt ausschliesslich als Rueckfall erhalten.

## Einstieg

- [Queue-Anzeige](queue-anzeige.md): laufende und wartende Arbeiten im gemeinsamen Anwendungstab, Statusquellen und Abdeckungsgrenzen.
- [Dolphin ohne Speicherung](dolphin-anzeige-ohne-speichern.txt): getrennte Anzeige ohne Chatverlauf sowie die bewusst waehlbare gespeicherte Variante.

- [Programmieragent](agentenansicht.md): Auftrag, lange Laufzeiten, Dokumentfreigaben, UML, vollständiger Review und bewusst bestätigte Übernahme (Phase I-03).

- [Modellwahl ab 16. Oktober](modellwahl-20261016.txt): Automatik und gezielte Auswahl in Chat, Story und Transkriptstrukturierung, gespeicherte Auswahl, Modellnachweise, Desktop-Starter und Tests.
- [Benutzerhandbuch](benutzerhandbuch.md): Bedienung, lokale Konfiguration, Chatfunktionen, Media- und Transkriptionsfunktionen.
- [Media- und Transkriptionsfunktionen](media-transkription.md): Detailbeschreibung fuer Video-zu-Sound, Sound-zu-TXT und n8n-Transkription.

- [Story-Arbeitsbereich](story-arbeitsbereich.txt): Projektbriefing, Kontextaufbau, Szenen, sichtbare Vorschlagspruefung, Wiederaufnahme und Aenderungsnachweise (8. Oktober 2026).

Modellkritik kann einen Vorschlag als menschlich zu pruefen kennzeichnen.
Solche Hinweise bleiben sichtbar und gespeichert; sie loesen kein automatisches
Umschreiben aus. Technisch fehlerhafte Auftraege liefern keine uebernehmbaren
Aenderungen. Jede Uebernahme erfolgt bewusst im sichtbaren Vergleich.

## Betrieb

- [KI-VM-Status](betrieb/ki-vm-status.md): letzter bekannter VM-Stand, offene Pruefpunkte und Nachpruefung.

## Workflows

- [Workflow-Kopien](workflows/README.md): Ablage der n8n-Workflow-Exporte, die zur Java-Oberflaeche gehoeren.

## Ablageregeln

- Bedienungs- und Entwicklerhinweise bleiben im Benutzerhandbuch.
- Fachliche Detailseiten liegen direkt unter `docs/`.
- Betriebsnotizen liegen unter `docs/betrieb/`.
- n8n-Workflow-Exporte liegen gesammelt unter `docs/workflows/`.
- Deploy-Pakete und Installationsskripte bleiben unter `deploy/`.
