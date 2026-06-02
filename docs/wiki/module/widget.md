# Modul: Widget

## Zweck

Das Trip-Widget zeigt den aktuellen Status einer aktiven Fahrt auf dem Homescreen. Es wird über einen Broadcast vom `TripTrackingService` aktualisiert.

## Kontext

Das Widget wird von `TripTrackingService` mit Daten versorgt:

- Linienname, nächster Halt, Ziel, Zeit, Gleis, Verspätung

Die offizielle Ankunftszeit bleibt die Widget-Zeit. GPS-basierte ETA wird aktuell nur in der laufenden TripTracking-Notification und für TTS-Entscheidungen genutzt.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/widget/TripWidgetProvider.kt`

## Verhalten

Das Widget empfängt Broadcasts mit `ACTION_UPDATE_WIDGET` und aktualisiert seine Daten. Die Darstellung erfolgt als AppWidgetProvider mit einem RemoteView-Layout.

## Widget-Layout (XML)

Im Ordner `res/layout/` befindet sich `trip_widget.xml` mit folgenden Views:

- `widget_root` - Gesamter Widget-Container (klickbar für App-Öffnung)
- `widget_line` - Linienname (z.B. "ICE 123")
- `widget_next_stop` - Nächster Halt oder "Nach: <Ziel>"
- `widget_time` - Ankunftszeit (wird bei Bedarf ein-/ausgeblendet)
- `widget_platform` - Gleis (wird bei Bedarf ein-/ausgeblendet)
- `widget_delay` - Verspätung in Minuten (wird nur bei > 0 angezeigt)

## Abhängigkeiten

- **TripTrackingService**: Sendet die Broadcasts mit den Widget-Daten

## Offene Fragen

Keine offenen Fragen aktuell.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
