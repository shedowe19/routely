# Entscheidung: GPS-Prognose auf gemeinsamem SEV-Restweg

## Datum und Status

07.10.2026 — Akzeptiert.

## Zweck und Kontext

Der erneute Nutzerbericht zeigt gültige Ersatzhalte und GPS-Haltfortschritt, aber die Diagnose `AMBIGUOUS_ROUTE`. Die vorhandene öffentliche OSRM-Fixture Mülheim → Essen enthält zwei Anfahrtswege mit rund 884 Metern Längenunterschied und identischem späterem Restweg. Auf öffentlichen Punkten dieses Restwegs unterscheiden sich die planbasierten ETA-Werte bei 23 Minuten Abschnittsfahrzeit nur um rund 38 Sekunden. Der bisherige Vergleich absoluter Chainage blockiert die Prognose trotzdem dauerhaft, wenn nach dem Zusammenlaufen noch kein früherer Weg gebunden war.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/GpsJourneyTimeEstimator.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingRouteGeometry.kt`
- `app/src/test/kotlin/de/traewelling/app/service/GpsRoadConsensusTest.kt`
- `app/src/test/kotlin/de/traewelling/app/service/TrackingRouteGeometryRemainingPathTest.kt`
- `app/src/test/resources/routing/muelheim-essen-osrm.json`

## Entscheidungen und Verhalten

1. Neben dem allgemeinen Korridor bestimmt der beste Querabstand eine relative Plausibilitätsgrenze von `max(10 m, 2 × Genauigkeit)`. Ein früher gebundener Weg muss diese Grenze ebenfalls weiter erfüllen; sonst verfallen Bindung und Prognose und der neue Kandidat benötigt eigene Bewegung. Mehrdeutigkeit innerhalb irgendeines verfügbaren Kandidaten verwirft die Prognose bereits vor der Prüfung einer früheren Bindung.
2. Der bisherige Fortschrittsvergleich bleibt erhalten. Bei unterschiedlichem vergangenem Fortschritt ist eine Ausnahme nur auf demselben geordneten Restlinienzug zulässig. Alle Kandidatenpaare werden ab ihrer tatsächlichen Projektion an der Vereinigung sämtlicher verbleibender normierter Bogenlängen-Stützpunkte verglichen. Höchstens ein Meter Formabweichung ist erlaubt. Gleiche Restlänge allein genügt nicht; zukünftige Abzweigungen, Schleifen, falsche Besuchsreihenfolge und parallele Straßen bleiben ausgeschlossen. Zusätzliche kollineare Stützpunkte sind zulässig.
3. Restlängen dürfen höchstens `max(10 m, 2 × Genauigkeit)` differieren. Das weiterhin gültige Planintervall muss 15 Sekunden bis 90 Minuten umfassen, die planbasierten ETA-Werte dürfen höchstens 60 Sekunden auseinanderliegen. Die zeitliche Grenze gilt auch bei Fortschrittsübereinstimmung innerhalb der bisherigen Metertoleranz. Maßgeblich ist die späteste Kandidaten-ETA. Dieser Grenzwert begrenzt Modellstreuung, nicht den realen Ankunftsfehler.
4. Jeder plausible Kandidat muss selbst die gerichteten Fixpaare, Sprunggrenze und mindestens drei frische Fixes über acht Sekunden mit `max(50 m, 3 × Genauigkeit)` Bewegung erfüllen. Kandidatenwechsel beginnen das Fenster neu. Eine kompatible frühere Prognose behält ausschließlich ihren ursprünglichen Ablauf; Replays liefern keine neue Bewegung oder Gültigkeit.
5. Ein gemeinsamer Restweg autorisiert keine eindeutige historische Route. Physische Halt-/Abfahrtsbeobachtung, Besuchscursor, TTS und Zielabschluss behalten ihre getrennten Belege. Gerätefixes und GPS-Historie werden weder für Routing übertragen noch als öffentliche Testdaten gespeichert.

## Abhängigkeiten und Alternativen

Die Änderung verwendet die bestehenden öffentlichen OSRM-Kandidaten, Kotlin und lokale Geometrie. Keine neue Bibliothek, API, Konfiguration oder Datenmigration. OSRM-Pkw-Wege sind weiterhin mögliche Straßenwege, keine offizielle SEV-Busführung; OSRM-Fahrtdauer wird nicht als Bus-ETA verwendet.

Eine größere pauschale Fortschrittstoleranz würde falsche Wege und Schleifen zulassen. Die erste Alternative ohne Beleg zu wählen würde eine historische Route vortäuschen. Ausschließlich auf eine frühere Bindung zu warten ließe Starts oder Neustarts auf bereits gemeinsamem Restweg dauerhaft ohne Prognose. Die eng begrenzte Restwegprüfung vermeidet diese Probleme.

## Offene Fragen

- TODO: Gemeinsamen Restweg, Neustart, GPS-Ausfall, nahe parallele Straßen, Umleitung und Prognosegüte auf einer tatsächlichen SEV-Fahrt prüfen. Automatisierte Regressionen belegen Codeverhalten, keine reale Zustellung oder ETA-Genauigkeit.
- Die ausgeführten Prüfungen und Grenzen stehen unter [Tests](../entwicklung/tests.md).

## Verwandte Seiten

- [GPS-Zeiten](../module/gps-zeiten.md)
- [TripTracking](../module/trip-tracking.md)
- [StatusDetail](../module/status-detail.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
- [Tests](../entwicklung/tests.md)
