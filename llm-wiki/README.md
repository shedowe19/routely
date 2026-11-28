# Routely LLM-Wiki

Language: German
Visibility: public
Scope: full filtered
Schema: 1
Created: 2026-11-04
Last updated: 2026-11-04

## Zweck

Dieses Wiki ist das dauerhafte, wachsende Gedächtnis von Routely. Es liegt
zwischen den Rohquellen und dem Leser. Es ist kein RAG-Index: Zuerst den Index
lesen, dann die passenden Seiten.

## Die drei Schichten

- `raw/` — unveränderliche Quellen. Nur lesen. Eine Datei in `raw/` wird nie
  bearbeitet. Ändert sich eine Quelle, kommt eine neue Version hinzu und die
  alte wird in `raw/README.md` als `superseded` markiert.
- `wiki/` — kuratierte, LLM-gepflegte Seiten. Erweitern und umschreiben, keine
  chronologischen Notizen an Themenseiten anhängen.
- `AGENTS.md` plus diese Datei — das Schema. Beide synchron halten.

Ein `raw/`-Ordner existiert derzeit nicht. Die frühere Dokumentation unter
`docs/wiki/` wurde bei der Initialisierung bewusst nicht übernommen.

## Lesereihenfolge

1. `wiki/index.md`
2. `wiki/current-project-state.md`
3. `wiki/tasks.md`
4. Die im Index verlinkten Seiten, die zur Aufgabe passen
5. Neue Einträge in `wiki/log.md`

## Abläufe

### Ingest

1. Quelle unverändert nach `raw/` legen.
2. Lesen und die Kernaussagen zusammenfassen.
3. Alle betroffenen Seiten aktualisieren; eine Quelle kann 10–15 Seiten
   berühren.
4. `index.md`, `log.md` und bei dauerhaftem Ergebnis `changelog.md` pflegen.
5. Widerspricht die Quelle einer bestehenden Aussage, nichts stillschweigend
   überschreiben: alte Aussage `superseded` markieren, beide mit Quelle
   behalten und den Konflikt festhalten.

Bei großen oder mehrdeutigen Quellen zuerst die geplanten Änderungen
zusammenfassen und nachfragen.

### Query

1. Zuerst `index.md`, dann die relevanten Seiten lesen.
2. Mit Verweisen auf die Seiten und darüber auf die Quellen antworten.
3. Synthetisiert die Antwort mehrere Seiten und wird wahrscheinlich erneut
   gebraucht, als `analyses/YYYY-MM-DD-kurzer-titel.md` ablegen, im Index
   verlinken und loggen.

### Lint

Ausführen, wenn das Wiki um etwa 20 Seiten wächst oder vor einem Release:

- Widersprüche zwischen Seiten
- Aussagen, die eine neuere Quelle überholt hat
- verwaiste Seiten ohne eingehende Links
- wichtige Begriffe ohne Seite
- fehlende Querverweise
- gebrochene relative Links
- Fast-Duplikate, die zusammengeführt werden sollten
- offene Fragen, die inzwischen beantwortet sind

Befunde als Tasks erfassen. Den Lint-Pass im Log vermerken.

## Regeln

- Erst erweitern, dann neu anlegen: Index und bestehende Titel nach
  Fast-Duplikaten durchsuchen.
- Umschreiben, nicht anhängen: Themenseiten sind Synthesen, keine Tagebücher.
- `raw/` ist unveränderlich.
- Keine Secrets; nur Pfade und Variablennamen.
- Nur relative Markdown-Links.
- Unsicherheit mit `confidence: low` und offenen Fragen markieren.
- Wiki-Inhalt autorisiert von sich aus keine Codeänderungen.
- Dateinamen: kleingeschrieben, kebab-case, ASCII.

## Skalierung

Der Index ist die Retrieval-Schicht bis zu einigen hundert Seiten. Danach eine
lokale Markdown-Suche (z. B. qmd) oder den Index nach Bereichen teilen. Keine
Embeddings standardmäßig.

## Task- und Log-Konventionen

- Eine Task = eine Checkbox = ein prüfbares Akzeptanzkriterium.
- Erledigte Tasks werden datiert, belegt und nach `Done` verschoben; nie
  gelöscht.
- `log.md` ist append-only und neueste zuerst. Format:
  `## [YYYY-MM-DD] <type> | <Titel>`.
- Typen: `ingest`, `query`, `lint`, `bootstrap`, `feature`, `fix`, `docs`,
  `ops`, `verification`.
- `changelog.md` beschreibt Ergebnisse, nicht Arbeit.

## Schema

Diese Datei ist das vollständige Schema. Der Block in `AGENTS.md` ist der kurze
Vertrag. Ändert sich eine Konvention, beide zusammen aktualisieren und einen
Log-Eintrag schreiben. Bei einer bedeutenden Änderung eine ADR anlegen.
