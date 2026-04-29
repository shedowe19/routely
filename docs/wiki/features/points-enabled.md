# Points-System und 0-Punkte Anzeige

## Zweck

Dieses Dokument beschreibt das Verhalten des Punkte-Systems in Träwelling, insbesondere wenn Nutzer das Punkte-System deaktiviert haben (`pointsEnabled = false`), sowie den Umgang mit Fahrten, die 0 Punkte einbringen.

## Kontext

Die App zeigt Punkte für Fahrten und Benutzerprofile an. Einige Nutzer haben das Sammeln von Punkten in den Einstellungen deaktiviert.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/data/model/Models.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/ProfileScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/UserProfileScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/CheckInScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/components/StatusCard.kt`

## Verhalten

- Wenn ein Benutzer `pointsEnabled = false` gesetzt hat, sollen in seinem Profil keine Punkte angezeigt werden.
- Wenn eine spezifische Fahrt 0 Punkte ergibt (oft als Nebeneffekt von deaktivierten Punkten oder bestimmten Fahrttypen), wird das "Punkte"-Badge im Feed, in der Detailansicht und im Check-in-Bildschirm ausgeblendet, anstatt "0 Pkt" anzuzeigen.

## Abhängigkeiten

- Träwelling API (liefert `pointsEnabled` und `points` zurück)
- Android Jetpack Compose (für UI-Rendering)

## Offene Fragen

- TODO: Prüfen, ob das Ausblenden in Zukunft durch eine durchgestrichene Anzeige ersetzt werden soll, oder ob Ausblenden die beste Lösung bleibt.

## Verwandte Seiten

- [Datenmodell](../daten/datenmodell.md)
