# Wiki-Pflege

## Zweck

Erklärt, wie das Wiki gepflegt werden soll, um dauerhaft nützlich zu bleiben.

## Kontext

Dieses Wiki ist ein zentrales Dokumentations-Tool und wird von Entwicklern und KI-Agenten gemeinsam gepflegt.

## Regeln

- **Wie neue Informationen ergänzt werden**: Wenn ein neues Modul, Feature oder Konzept auftaucht, das noch nicht dokumentiert ist, soll eine neue Seite angelegt oder eine bestehende passend erweitert werden.
- **Wann bestehende Seiten aktualisiert werden sollen**: Nach relevanten Code-Änderungen (z.B. neue APIs, Architekturänderungen, neue Config-Variablen).
- **Wie Unsicherheiten markiert werden**: Mit `TODO:`, `Unklar:` oder `Annahme:`.
- **Wie neue Seiten benannt werden**: Dateinamen klein schreiben, Wörter mit Bindestrich trennen, keine Leerzeichen, deutsche Namen wenn sinnvoll (z.B. `user-authentication.md`).
- **Wie Unterordner genutzt werden**: Seiten in passende Unterordner einsortieren, wie im `index.md` vorgegeben.
- **Wahrheit**: Produktiver Code darf nicht automatisch aus Wiki-Inhalten verändert werden. Code ist die Quelle der Wahrheit.
- **Prüfung**: Das Wiki muss bei jeder Agent-Ausführung geprüft werden.
- **Verlinkung**: Alle Links als relative Markdown-Links (`[Text](./pfad/datei.md)`), keine Wiki-Syntax (`[[Link]]`), da GitHub keine Wiki-Links unterstützt.

## Verwandte Seiten

- [Index](./index.md)
