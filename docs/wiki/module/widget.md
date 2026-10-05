# Modul: Widget

## Zweck

Das Trip-Widget zeigt den aktuellen Status einer aktiven Fahrt auf dem Homescreen. Es wird über einen Broadcast vom `TripTrackingService` aktualisiert.

## Kontext

Das Widget wird von `TripTrackingService` mit Daten versorgt:

- Linienname, nächster Halt, Ziel, Zeit, Gleis, Verspätung

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/widget/TripWidgetProvider.kt`

## Verhalten

Das Widget empfängt Broadcasts mit `ACTION_UPDATE_WIDGET` und aktualisiert seine Daten. Die Darstellung erfolgt als AppWidgetProvider mit einem RemoteView-Layout.

Der nächste Halt stammt aus der gemeinsamen Tracking-Engine des Service. Im Feld `nextStop` wird außerdem `GPS` oder `Fahrplan · ungefähr` angezeigt; bei unbestätigter Zielankunft nach vergangener/fehlender Zielzeit oder fehlendem aktuellen Halt zusätzlich `Fahrt manuell beenden`. Bei nutzbarem GPS bleibt der konkrete Halt auch nach einer vergangenen Planzeit aktiv; die angezeigte Uhrzeit stammt weiterhin aus den Echtzeit-/Planfeldern. Das Widget führt selbst keine Ortsabfragen durch.

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
