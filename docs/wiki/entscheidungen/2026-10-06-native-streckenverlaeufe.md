# Entscheidung: Native Streckenverläufe für die GPS-Begleitung

## Datum

2026-10-06

## Status

Akzeptiert; automatisierter Build-/Testnachweis wird dem tatsächlich geprüften Commit unter [Tests](../entwicklung/tests.md) zugeordnet. Reale Gerätegüte bleibt gesondert offen.

## Zweck

Gerichteten GPS-Fortschritt und lokale Zeitprognosen für Bahn-/Tramfahrten auf geeignete Linienzüge stützen, damit Kurven außerhalb der geraden Haltverbindung nicht allein wegen dieses vereinfachten Modells abgelehnt werden.

## Kontext

Der bisherige Schätzer projizierte normale Fahrten auf eine Gerade zwischen zwei Halten. Haltbasierte Wiederverankerung nach einem Tunnel korrigiert Besuchsidentität, liefert aber keinen Streckenverlauf. Für SEV besteht bereits ein getrenntes approximatives OSRM-Straßenmodell zwischen belegten Ersatzhaltepunkten. Die neue Aufgabe braucht deshalb eine zusätzliche native Formquelle, ohne diese Halt-/Quellenbelege zu vermischen.

Träwelling liefert über `GET /api/v1/polyline/{statusId}` GeoJSON für den eigenen Check-in. Der Backend-Code kann gespeicherte Streckenabschnitte mit Stationssehnen mischen und liefert keine Herkunft je Teilabschnitt. Ein Download allein beweist deshalb weder echte Schienen-Geometrie noch die aktuell befahrene offizielle Gleisführung.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/data/routing/TransitRouteParser.kt`
- `app/src/main/kotlin/de/traewelling/app/data/routing/TransitRouteRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingRouteGeometry.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TransitRouteTracking.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`

## Entscheidung

Die App verwendet den nativen Endpunkt am konfigurierten Träwelling-Server mit der passenden Sitzung. Kein zusätzlicher Transitous-, Bahnexpert- oder fremder Schienenrouter wird angebunden. Die Quelle heißt `Träwelling-Streckenverlauf`. Gerätefixes und Bewegungshistorie werden für den Abruf nicht übertragen.

Ein begrenzter Parser bindet genau eine Statusform eindeutig und monoton an die vollständige geordnete Check-in-Besuchsfolge. Wiederholte Besuche bleiben getrennt; gestrichene Zwischenbesuche werden für die Prüfung erhalten. Reine beziehungsweise verdichtete Stationssehnen und mehrdeutige Formen werden nicht zu Schienenwegen erklärt. Jede ursprüngliche Teilpaarung eines verwendeten Abschnitts braucht eigenen Kurvenbeleg. Die exakten Größen-, Snap- und Qualitätsgrenzen stehen unter [GPS-Zeiten](../module/gps-zeiten.md).

Ein sessiongebundenes Repository lädt asynchron außerhalb der Tracking-Sperre und hält höchstens 15 Minuten gültige Formen ausschließlich im RAM. Fahrtgeneration und vollständige Besuchs-/Plan-/Koordinatenbasis schützen die Übernahme; Ablauf, Kontowechsel, GPS aus oder Service-Ende entfernen die Nutzung. Ein Netzabschluss zählt keinen bereits verbrauchten Fix erneut.

Eine gemeinsame lokale Projektion versorgt Zeitschätzer und gerichtete Fortschrittshilfen. Physische Ankunft, Zielaufenthalt, haltbasierte Wiederverankerung und gewöhnlicher Ansageradius behalten ihre eigenen GPS-/Entfernungsbelege. Ein tatsächlicher Quellen-/Formwechsel setzt Zukunftsprognose und Bewegung zurück, bewahrt aber bestätigte Ereignisse derselben Haltbasis. Der UI-Quellenhinweis beschreibt die tatsächlich verwendete Projektion, keinen bloßen Prefetch.

## Konsequenzen

Geeignete native Kurven können die bisherige räumliche Projektion ersetzen, ohne Providerzeiten, Bearbeitungswerte oder GPS-Qualitätsgrenzen zu verändern. Für normale Bahn/Tram bleibt ohne geeignete Form die konservative Geradenprojektion verfügbar; bei fehlendem GPS-Zeitbeleg folgen weiterhin manuelle Zeit, API-Echtzeit und Plan. SEV behält seine getrennte Straßenquelle und keinen Luftlinienersatz für fehlende Straße.

Die Kurvenprüfung kann tatsächlich gerade Gleise konservativ ablehnen. Auch eine akzeptierte Form zertifiziert keine Umleitung, Baustellenführung oder amtliche Gleislage. Ohne GPS im Tunnel entstehen keine Messungen oder rückwirkenden Istzeiten. Nach Neustart wird keine persistierte Form übernommen; die vorhandene Haltfolge dient einem neuen Abruf.

## Alternativen

- **Weiter ausschließlich gerade Haltverbindungen:** geringe Netzlast, aber bekannter ungeeigneter Korridor auf Kurven.
- **OSRM-Pkw-Profil auch für Schienenfahrten:** beschreibt Straßen statt Gleise und wird dafür nicht verwendet.
- **Zusätzlicher externer Transit-/Gleisprovider:** würde neue Identitäts-, Privacy- und Betriebsverträge verlangen; für den verfügbaren nativen Statusvertrag nicht nötig.
- **Backend-Polyline ohne Besuchs-/Formprüfung:** würde Stationssehnen und Schleifen uneingeschränkt als Fahrweg behandeln und wird verworfen.

## Offene Fragen

- TODO: Kurven, parallele Gleise, Umleitungen, Rundfahrten, Tunnel und Signalrückkehr mit zeitlich zugeordneten Fix-/Audioverläufen auf einem Gerät prüfen.
- TODO: Laufzeit, Speicher- und Netzwerkverbrauch langer Linienzüge sowie Quellenqualität auf weiteren Träwelling-Servern messen. Eine zukünftige Herkunft je RouteSegment könnte konservative Geradenablehnungen gezielter lösen.

## Verwandte Seiten

- [GPS-Zeiten](../module/gps-zeiten.md)
- [TripTracking](../module/trip-tracking.md)
- [Externe Schnittstellen](../api/externe-schnittstellen.md)
- [Datenmodell](../daten/datenmodell.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Tests](../entwicklung/tests.md)
