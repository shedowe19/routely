# Entscheidung: App-Umbenennung zu "Routely"

## Status
Akzeptiert

## Datum
2026-04-28

## Kontext
Die Android-App war zuvor direkt unter dem Namen "Träwelling" bzw. "Träwelling Android" bekannt. Um eine stärkere und eigenständigere Client-Identität zu etablieren und Verwechslungen mit der offiziellen Web-Plattform "Träwelling" zu vermeiden, wurde beschlossen, die App in "Routely" umzubenennen.

## Entscheidung
Wir haben den App-Namen in der Codebasis, den Manifest-Dateien, Gradle-Konfigurationen und der internen Dokumentation (Wiki, README) von "Träwelling Android" / "Träwelling" auf "Routely" geändert. Die zugrunde liegenden APIs und Plattformen bleiben jedoch "Träwelling". Zudem wurde das App-Icon aktualisiert.

## Konsequenzen
- Alle UI-Texte, die auf die App verweisen, zeigen nun "Routely".
- Der Theme-Name ist nun `Theme.Routely`.
- Keine Änderungen am Package-Namen (`de.traewelling.app`) oder den Modulen, um unnötige technische Komplexität und Breaking Changes für existierende Nutzer zu vermeiden.
