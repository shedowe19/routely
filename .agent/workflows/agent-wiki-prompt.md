Du bist ein erfahrener Coding Agent und sollst in diesem bestehenden Projekt ein internes LLM-Wiki einrichten und dauerhaft pflegen.

Ziel:
Erstelle und pflege eine dauerhafte, gut strukturierte Markdown-Wissensbasis für dieses Projekt. Das Wiki soll es später einem LLM, Entwickler oder neuen Teammitglied ermöglichen, das Projekt schnell zu verstehen, wichtige Entscheidungen nachzuvollziehen und wiederkehrende Informationen leicht zu finden.

Wichtig:
- Arbeite im bestehenden Projekt.
- Verändere keinen produktiven Code, außer es ist ausdrücklich notwendig.
- Überschreibe keine bestehenden Dokumente ohne vorher eine Sicherung oder klare Begründung.
- Wenn bereits ein Wiki, ein `docs`-Ordner oder Projekt-Dokumentation existiert, integriere dich dort sinnvoll.
- Falls keine Struktur vorhanden ist, erstelle eine neue unter `docs/wiki/`.
- Schreibe alle Wiki-Inhalte auf Deutsch.
- Nutze Markdown.
- Verwende interne Wiki-Links im Stil `[[Seitenname]]`, wo sinnvoll.
- Halte die Inhalte sachlich, präzise und wartbar.
- Markiere Unsicherheiten klar mit `TODO:`, `Unklar:` oder `Annahme:`.
- Dokumentiere keine erfundenen Details.
- Kopiere niemals echte Secrets, Tokens, Passwörter oder private Schlüssel ins Wiki.

---

# Automatische Wiki-Pflege bei jeder Agent-Ausführung

Dieses Projekt enthält ein internes LLM-Wiki. Der Agent ist verpflichtet, das Wiki bei jeder neuen Arbeitssitzung aktiv mitzupflegen.

## Grundregel

Jedes Mal, wenn diese `AGENTS.md` / `agent.md` geladen wird, muss der Agent prüfen:

1. Welche Aufgabe wird gerade bearbeitet?
2. Welche Dateien, Module, Features oder Entscheidungen sind davon betroffen?
3. Ist dieses Wissen bereits im Wiki dokumentiert?
4. Ist die bestehende Wiki-Dokumentation noch korrekt?
5. Muss eine bestehende Seite aktualisiert oder eine neue Unterseite angelegt werden?

Das Wiki ist kein einmaliges Dokumentationspaket, sondern eine dauerhaft gepflegte Wissensbasis.

---

# Wiki-Pflichtprüfung vor jeder Arbeit

Bevor produktiver Code geändert wird, muss der Agent prüfen, ob die aktuelle Aufgabe Wiki-relevant ist.

Wiki-relevant ist eine Aufgabe, wenn sie mindestens eines betrifft:

- neue Features
- Architekturänderungen
- neue Module oder Services
- Änderungen an bestehenden Modulen
- neue APIs, Endpunkte, Events oder Schnittstellen
- neue Datenmodelle, Tabellen, Schemas oder Migrationen
- neue Konfigurationen oder Umgebungsvariablen
- Build-, Setup-, Test- oder Deployment-Änderungen
- wichtige technische Entscheidungen
- neue Abhängigkeiten
- entfernte oder ersetzte Funktionen
- bekannte Einschränkungen, Risiken oder offene Fragen

Wenn die Aufgabe Wiki-relevant ist, muss das Wiki aktualisiert werden.

---

# Wiki-Pflichtprüfung nach jeder Arbeit

Nach Abschluss einer Änderung muss der Agent erneut prüfen:

- Wurde durch die Arbeit neues Wissen erzeugt?
- Ist eine bestehende Wiki-Seite veraltet?
- Gibt es neue Zusammenhänge zwischen Modulen?
- Müssen neue Seiten oder Unterseiten erstellt werden?
- Müssen `[[Wiki-Links]]` ergänzt werden?
- Müssen offene Fragen dokumentiert werden?

Wenn ja, aktualisiere das Wiki im selben Arbeitsdurchlauf.

---

# Projekt analysieren

Untersuche die vorhandene Projektstruktur.

Lies wichtige Dateien wie:

- `README.md`
- `package.json`
- `pyproject.toml`
- `Cargo.toml`
- `composer.json`
- `pom.xml`
- Konfigurationsdateien
- vorhandene Dokumentation
- zentrale Quellcode-Ordner

Erkenne:

- Frameworks
- Programmiersprachen
- Architektur
- wichtige Module
- externe Abhängigkeiten
- Build-, Test- und Deployment-Prozesse

---

# Wiki-Struktur

Das Wiki soll nicht nur aus flachen Einzelseiten bestehen. Es muss sinnvoll in Unterordner und Unterseiten gegliedert werden.

Wenn noch kein Wiki vorhanden ist, erstelle folgende Struktur:

```text
docs/wiki/
├── index.md
├── projekt/
│   ├── ueberblick.md
│   ├── ziele.md
│   └── begriffe.md
├── architektur/
│   ├── ueberblick.md
│   ├── datenfluss.md
│   ├── module.md
│   ├── entscheidungen.md
│   └── externe-abhaengigkeiten.md
├── entwicklung/
│   ├── setup.md
│   ├── lokale-entwicklung.md
│   ├── tests.md
│   ├── build.md
│   └── deployment.md
├── module/
│   └── README.md
├── api/
│   ├── ueberblick.md
│   ├── interne-schnittstellen.md
│   └── externe-schnittstellen.md
├── daten/
│   ├── datenmodell.md
│   ├── datenbank.md
│   ├── schemas.md
│   └── migrationen.md
├── konfiguration/
│   ├── umgebungsvariablen.md
│   ├── config-dateien.md
│   └── secrets-und-sicherheit.md
├── entscheidungen/
│   ├── README.md
│   └── adr-template.md
├── features/
│   └── README.md
├── offene-fragen.md
├── glossar.md
└── wiki-pflege.md
```

Der Agent darf zusätzliche Unterordner anlegen, wenn das Projekt dadurch besser dokumentiert wird.

Beispiele:

```text
docs/wiki/module/authentication.md
docs/wiki/module/billing.md
docs/wiki/features/user-login.md
docs/wiki/features/export.md
docs/wiki/api/rest-endpunkte.md
docs/wiki/api/webhooks.md
docs/wiki/daten/user-model.md
docs/wiki/entscheidungen/2026-04-26-auth-strategie.md
```

---

# Inhalte der Hauptseiten

## `docs/wiki/index.md`

Enthalten soll:

- kurzer Einstieg in das Wiki
- Links zu allen wichtigen Bereichen
- Erklärung, wie das Wiki gepflegt werden soll
- Hinweis, dass Code die Quelle der Wahrheit ist

Beispielstruktur:

```markdown
# Projekt-Wiki

Dieses Wiki ist das Langzeitgedächtnis des Projekts. Es erklärt Architektur, Module, Features, Schnittstellen, Datenmodelle, Konfiguration und technische Entscheidungen.

Code ist die Quelle der Wahrheit. Das Wiki erklärt die Quelle der Wahrheit.

## Projekt

- [[projekt/ueberblick]]
- [[projekt/ziele]]
- [[projekt/begriffe]]

## Architektur

- [[architektur/ueberblick]]
- [[architektur/datenfluss]]
- [[architektur/module]]
- [[architektur/entscheidungen]]
- [[architektur/externe-abhaengigkeiten]]

## Entwicklung

- [[entwicklung/setup]]
- [[entwicklung/lokale-entwicklung]]
- [[entwicklung/tests]]
- [[entwicklung/build]]
- [[entwicklung/deployment]]

## Module

- [[module/README]]

## Features

- [[features/README]]

## API und Schnittstellen

- [[api/ueberblick]]
- [[api/interne-schnittstellen]]
- [[api/externe-schnittstellen]]

## Daten

- [[daten/datenmodell]]
- [[daten/datenbank]]
- [[daten/schemas]]
- [[daten/migrationen]]

## Konfiguration

- [[konfiguration/umgebungsvariablen]]
- [[konfiguration/config-dateien]]
- [[konfiguration/secrets-und-sicherheit]]

## Entscheidungen

- [[entscheidungen/README]]
- [[entscheidungen/adr-template]]

## Weitere Seiten

- [[glossar]]
- [[offene-fragen]]
- [[wiki-pflege]]
```

## `docs/wiki/projekt/ueberblick.md`

Dokumentiere:

- Was macht das Projekt?
- Für wen ist es gedacht?
- Welche Hauptfunktionen gibt es?
- Welche Technologien werden verwendet?

## `docs/wiki/architektur/ueberblick.md`

Dokumentiere:

- grobe Systemarchitektur
- wichtige Schichten, Services oder Module
- Datenfluss, soweit erkennbar
- externe Abhängigkeiten

## `docs/wiki/entwicklung/setup.md`

Dokumentiere:

- lokales Setup
- Installation
- Entwicklungsbefehle
- benötigte Tools
- Testbefehle
- Build- oder Deploy-Hinweise, falls erkennbar

## `docs/wiki/module/README.md`

Dokumentiere:

- wichtige Ordner und Dateien
- zentrale Komponenten
- Verantwortlichkeiten der Module
- Links auf einzelne Modul-Unterseiten

## `docs/wiki/daten/datenmodell.md`

Dokumentiere:

- wichtige Datenstrukturen
- Datenbanktabellen
- Models
- Schemas
- Beziehungen, falls erkennbar
- Unsicherheiten

## `docs/wiki/api/ueberblick.md`

Dokumentiere:

- interne und externe APIs
- Routen
- Endpunkte
- Events
- Hooks
- Services
- Eingaben und Ausgaben grob, sofern erkennbar

## `docs/wiki/konfiguration/umgebungsvariablen.md`

Dokumentiere:

- relevante Umgebungsvariablen
- Zweck der Variable
- ob sie optional oder erforderlich ist, falls erkennbar
- niemals echte Secret-Werte

Beispiel:

```markdown
| Variable | Zweck | Erforderlich | Hinweis |
|---|---|---|---|
| `DATABASE_URL` | Verbindungsstring zur Datenbank | Unklar | Wert nicht dokumentieren |
| `API_KEY` | Secret für externen Dienst | Unklar | Wert nicht dokumentieren |
```

## `docs/wiki/entscheidungen/README.md`

Dokumentiere:

- Übersicht technischer Entscheidungen
- Links zu ADR-Dateien
- kurze Zusammenfassung wichtiger Entscheidungen

## `docs/wiki/glossar.md`

Dokumentiere:

- projektspezifische Begriffe
- Abkürzungen
- Fachbegriffe mit Wiki-Links

## `docs/wiki/offene-fragen.md`

Dokumentiere:

- unklare Punkte
- fehlende Dokumentation
- Risiken
- Annahmen
- Punkte, die ein Mensch prüfen sollte

---

# Regeln für neue Wiki-Seiten

Wenn ein neues Feature, Modul oder Konzept auftaucht, das noch nicht dokumentiert ist, soll der Agent eine eigene Seite dafür anlegen.

## Namensregeln

- Dateinamen klein schreiben
- Wörter mit Bindestrich trennen
- keine Leerzeichen
- deutsche Namen verwenden, wenn sinnvoll
- technische Eigennamen beibehalten
- Seiten in passende Unterordner einsortieren

Beispiele:

```text
user-authentication.md
payment-provider.md
datenimport.md
rollen-und-rechte.md
```

## Vorlage für neue Seiten

Jede neue Seite soll mindestens enthalten:

```markdown
# Seitentitel

## Zweck

Kurze Erklärung, wofür dieses Modul, Feature oder Konzept da ist.

## Kontext

Wo wird es im Projekt verwendet?

## Wichtige Dateien

- `pfad/zur/datei.ts`
- `pfad/zum/modul.py`

## Verhalten

Was macht dieser Teil des Systems?

## Abhängigkeiten

Welche anderen Module, Services oder APIs werden verwendet?

## Offene Fragen

- TODO: ...

## Verwandte Seiten

- [[architektur/ueberblick]]
- [[api/ueberblick]]
```

---

# Automatische Aktualisierung bestehender Seiten

Wenn eine passende Wiki-Seite bereits existiert, soll der Agent sie aktualisieren statt eine doppelte Seite zu erstellen.

Vor dem Erstellen einer neuen Seite immer prüfen:

- Gibt es bereits eine Seite zum selben Modul?
- Gibt es eine übergeordnete Seite, die erweitert werden sollte?
- Ist eine Unterseite sinnvoller als ein neuer Hauptartikel?
- Müssen Links im `index.md` ergänzt werden?
- Müssen bestehende verwandte Seiten ergänzt werden?

Doppelte Dokumentation vermeiden.

---

# Index-Pflege

Der Agent muss `docs/wiki/index.md` aktuell halten.

Wenn neue wichtige Seiten oder Unterordner entstehen, müssen sie im Index verlinkt werden.

Der Index soll als Einstiegspunkt dienen und die Wiki-Struktur erklären.

---

# Verlinkungsregeln

Das Wiki soll stark vernetzt sein.

Der Agent soll passende interne Links setzen:

```markdown
Siehe auch [[architektur/ueberblick]].
Die Authentifizierung ist in [[module/authentication]] beschrieben.
Die API-Endpunkte stehen unter [[api/rest-endpunkte]].
```

Jede Wiki-Seite soll am Ende einen Abschnitt enthalten:

```markdown
## Verwandte Seiten

- [[...]]
```

Wenn keine verwandten Seiten bekannt sind:

```markdown
## Verwandte Seiten

- TODO: Verwandte Seiten ergänzen.
```

---

# Entscheidungsdokumentation

Wenn während der Arbeit eine technische Entscheidung getroffen oder sichtbar wird, muss sie dokumentiert werden.

Für größere Entscheidungen eine eigene ADR-Datei anlegen:

```text
docs/wiki/entscheidungen/YYYY-MM-DD-kurzer-titel.md
```

Vorlage:

```markdown
# Entscheidung: Kurzer Titel

## Datum

YYYY-MM-DD

## Status

Vorgeschlagen | Akzeptiert | Verworfen | Ersetzt

## Kontext

Warum war die Entscheidung nötig?

## Entscheidung

Was wurde entschieden?

## Konsequenzen

Welche Auswirkungen hat das?

## Alternativen

Welche Optionen wurden betrachtet?

## Verwandte Seiten

- [[architektur/ueberblick]]
```

Kleinere Entscheidungen können zusätzlich in `docs/wiki/architektur/entscheidungen.md` zusammengefasst werden.

---

# Offene Fragen und Unsicherheiten

Wenn der Agent etwas nicht sicher aus dem Code ableiten kann, darf er es nicht erfinden.

Stattdessen muss er es dokumentieren:

```markdown
Unklar: ...
TODO: ...
Annahme: ...
```

Offene Punkte zusätzlich in `docs/wiki/offene-fragen.md` sammeln.

---

# Keine Secrets dokumentieren

Der Agent darf niemals echte geheime Werte ins Wiki schreiben.

Nicht dokumentieren:

- API Keys
- Tokens
- Passwörter
- Private Keys
- echte Zugangsdaten
- geheime URLs, sofern sicherheitskritisch

Erlaubt ist nur die Beschreibung der benötigten Variable:

```markdown
- `DATABASE_URL`: Verbindungsstring zur Datenbank. Wert nicht dokumentieren.
- `API_KEY`: Secret für externen Dienst. Wert nicht dokumentieren.
```

---

# Verhalten bei kleinen Änderungen

Nicht jede kleine Änderung braucht eine neue Wiki-Seite.

Aber der Agent soll prüfen, ob eine bestehende Seite angepasst werden muss.

Beispiele:

- Kleine Bugfixes ohne Architekturänderung: meistens keine Wiki-Änderung nötig.
- Neuer API-Endpunkt: Wiki aktualisieren.
- Neue Config-Variable: Wiki aktualisieren.
- Neues Modul: Wiki aktualisieren.
- Änderung am Setup: Wiki aktualisieren.
- Neuer Testbefehl: Wiki aktualisieren.
- Neue Abhängigkeit: Wiki aktualisieren.
- Entferntes Feature: Wiki aktualisieren.

---

# README ergänzen

Falls passend, ergänze im Haupt-`README.md` einen kurzen Abschnitt:

```markdown
## Projekt-Wiki

Die interne Projektdokumentation befindet sich unter:

- [Projekt-Wiki](docs/wiki/index.md)
```

Falls das README sehr speziell oder automatisch generiert wirkt, erstelle stattdessen eine kurze Notiz in `docs/wiki/index.md` und ändere das README nicht.

---

# Wiki-Pflege-Seite

Erstelle oder aktualisiere zusätzlich:

```text
docs/wiki/wiki-pflege.md
```

Inhalt:

- Wie neue Informationen ergänzt werden
- Wann bestehende Seiten aktualisiert werden sollen
- Wie Unsicherheiten markiert werden
- Wie neue Seiten benannt werden
- Wie Unterordner genutzt werden
- Dass produktiver Code nicht automatisch aus Wiki-Inhalten verändert werden darf
- Dass Code die Quelle der Wahrheit ist
- Dass das Wiki bei jeder Agent-Ausführung geprüft werden muss

---

# Abschluss jeder Aufgabe

Am Ende jeder Aufgabe muss der Agent im Abschlussbericht angeben:

```text
Wiki-Prüfung:
- Geprüfte Wiki-Seiten:
- Aktualisierte Wiki-Seiten:
- Neu erstellte Wiki-Seiten:
- Keine Wiki-Änderung nötig, weil:
- Offene Wiki-TODOs:
```

Wenn keine Wiki-Änderung nötig war, muss der Grund genannt werden.

---

# Qualität

- Schreibe keine erfundenen Details.
- Wenn etwas nicht eindeutig aus dem Projekt ableitbar ist, schreibe `Unklar: ...`.
- Keine geheimen Werte, Tokens, Passwörter oder privaten Schlüssel dokumentieren.
- Dokumentiere nur Struktur, Zweck und erkennbare Zusammenhänge.
- Halte jede Seite lesbar und nicht unnötig lang.
- Nutze Tabellen nur, wenn sie wirklich helfen.
- Vermeide doppelte Dokumentation.
- Halte Links aktuell.
- Nutze klare deutsche Sprache.

---

# Arbeitsprinzip

Der Agent soll das Wiki so behandeln, als wäre es das Langzeitgedächtnis des Projekts.

Code ist die Quelle der Wahrheit.
Das Wiki erklärt die Quelle der Wahrheit.

Bei jeder relevanten Änderung müssen Code und Wiki gemeinsam aktuell gehalten werden.

Beginne jetzt mit der Analyse des Projekts und richte das Wiki entsprechend ein.
