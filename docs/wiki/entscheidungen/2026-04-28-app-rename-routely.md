# Entscheidung: App-Umbenennung zu "Routely"

## Zweck

Festlegung und Dokumentation der App-Umbenennung von "Träwelling" zu "Routely".

## Status

Akzeptiert

## Datum

2026-04-28

## Kontext

Die Android-App war zuvor direkt unter dem Namen "Träwelling" bzw. "Träwelling Android" bekannt. Um eine stärkere und eigenständigere Client-Identität zu etablieren und Verwechslungen mit der offiziellen Web-Plattform "Träwelling" zu vermeiden, wurde beschlossen, die App in "Routely" umzubenennen.

## Entscheidung

Wir haben den App-Namen in der Codebasis, den Manifest-Dateien, Gradle-Konfigurationen und der internen Dokumentation (Wiki, README) von "Träwelling Android" / "Träwelling" auf "Routely" geändert. Die zugrunde liegenden APIs und Plattformen bleiben jedoch "Träwelling". Zudem wurde das App-Icon aktualisiert.

## Wichtige Dateien

- `app/src/main/res/values/strings.xml` (App-Name)
- `app/src/main/res/values/themes.xml` (Umbenennung auf `Theme.Routely`)
- `app/src/main/AndroidManifest.xml` (Aktualisierung Theme)
- `settings.gradle.kts` (Root Project Name)
- UI-Dateien wie `FeedScreen.kt` und `SetupScreen.kt` (statische Strings)
- (Der Package-Name `de.traewelling.app` wurde bewusst nicht geändert)

## Verhalten

- Nutzer sehen nun auf dem Homescreen und beim Start der App den Namen "Routely" sowie das neue App-Icon (Züge und Busse formen ein "S" über Wellen).
- Entwickler nutzen fortan in UI-Komponenten und Gradle "Routely".

## Abhängigkeiten

- Backend: Die App interagiert nach wie vor mit der Träwelling-Plattform (API) unter `traewelling.de`.
- Keine Änderungen an Build-Abhängigkeiten oder Package-Ids.

## Konsequenzen

- Alle UI-Texte, die auf die App verweisen, zeigen nun "Routely".
- Der Theme-Name ist nun `Theme.Routely`.
- Keine Änderungen am Package-Namen (`de.traewelling.app`) oder den Modulen, um unnötige technische Komplexität und Breaking Changes für existierende Nutzer zu vermeiden.

## Offene Fragen

- Müssen in Zukunft OAuth-Client-Anmeldungen oder Redirect-URIs beim Backend bezüglich der neuen App-Identität angepasst werden? (Aktuell funktioniert alles mit den alten).
- Wird ein Domainwechsel für Kontakt oder App-Website folgen?

## Verwandte Seiten

- [Architektur Entscheidungen](../architektur/entscheidungen.md)
