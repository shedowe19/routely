# Modul: Widget

## Zweck

Das Trip-Widget zeigt den aktuellen Status einer aktiven Fahrt auf dem Homescreen. Es wird über einen Broadcast vom `TripTrackingService` aktualisiert.

## Kontext

Das Widget wird von `TripTrackingService` mit Daten versorgt:

- Linienname, nächster Halt, Ziel, Zeit samt Quelle, Gleis, Verspätung oder Verfrühung

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/widget/TripWidgetProvider.kt`

## Verhalten

Das Widget empfängt Broadcasts mit `ACTION_UPDATE_WIDGET` und aktualisiert seine Daten. Die Darstellung erfolgt als AppWidgetProvider mit einem RemoteView-Layout.

Der nächste Halt stammt aus der gemeinsamen Tracking-Engine des Service. Bei nutzbarem GPS bleibt der konkrete Besuch auch nach einer vergangenen Planzeit aktiv. Die Uhrzeit erhält der Service aus `JourneyTimeResolver`: frische zugeordnete GPS-Zeit, sonst manuelle Zeit, parsebare API-Echtzeit oder Fahrplan. `timeSource` wird separat als `GPS beobachtet`, `GPS-Schätzung`, `Manuell`, `API-Echtzeit` oder `Fahrplan` angezeigt. Ohne auflösbare Zeit kann stattdessen der Fortschritts-/Beendenhinweis erscheinen. Die begrenzte Stabilisierung eines bereits belegten GPS-Werts kommt aus demselben Service-Ergebnis wie im Fahrtdetail; sie verlängert dessen Gültigkeit nicht. Ein GPS-Cursor setzt daher nicht automatisch eine GPS-Uhrzeit voraus. Das Widget führt selbst keine Ortsabfragen oder Prognoseberechnungen durch.

Bei Fahrtwechsel zeigt das Widget einen Ladezustand ohne frühere Zeit-/Quellenwerte. Beim Service-Ende oder Zerstören des Services werden Zeit, Zeitquelle, Abweichung und Gleis entfernt; der Hinweis wartet wieder auf einen Check-in. Eine alte Fahrtprognose bleibt dadurch nicht als laufende GPS-Zeit stehen.

## Widget-Layout (XML)

Im Ordner `res/layout/` befindet sich `trip_widget.xml` mit folgenden Views:

- `widget_root` - Gesamter Widget-Container (klickbar für App-Öffnung)
- `widget_line` - Linienname (z.B. "ICE 123")
- `widget_next_stop` - Nächster Halt oder "Nach: <Ziel>"
- `widget_time` - Ankunftszeit (wird bei Bedarf ein-/ausgeblendet)
- `widget_time_source` - Quelle der angezeigten Zeit
- `widget_platform` - Gleis (wird bei Bedarf ein-/ausgeblendet)
- `widget_delay` - Abweichung in Minuten: positiv rot, negativ grün, bei null oder fehlender Planzeit ausgeblendet

## Abhängigkeiten

- **TripTrackingService**: Sendet die Broadcasts mit den Widget-Daten

## Offene Fragen

- TODO: Quellenwechsel und negative Zeitabweichungen bei großer Schrift sowie nach GPS-Ausfall auf einem Gerät prüfen.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
- [GPS-Zeiten](./gps-zeiten.md)
- [Reisefortschritt](./trip-progress.md)
