# Architektur: Module

## Zweck

Übersicht über die internen App-Packages/Schichten.

## Wichtige Packages

- `de.traewelling.app.data`: Beinhaltet API (Retrofit), lokale DB (Room), Repositories und Models.
- `de.traewelling.app.data.sev`: Öffentlicher Bahnhofskartenabruf, HTML-/GeoJSON-Parser, Besuchs-/Datums-/Richtungsauflösung und begrenzte asynchrone Anreicherung ([SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)).
- `de.traewelling.app.data.routing`: Anonymer öffentlicher OSRM-Abruf, begrenzter RAM-Cache und Validierung möglicher Straßen-Geometrien zwischen öffentlichen SEV-Endpunkten. Die [GPS-Zeitauswertung](../module/gps-zeiten.md) verwendet sie nur für geeignete lokale Abschnittsprojektionen.
- `de.traewelling.app.ui`: Beinhaltet Compose Navigation, Screens und Theme/Components.
- `de.traewelling.app.viewmodel`: MVVM ViewModels für jeden Screen.
- `de.traewelling.app.service` & `widget`: Hintergrundservices (z.B. LocationTracking/TripTracking) und Homescreen Widgets.

## Verwandte Seiten

- [Module Übersicht](../module/README.md)
