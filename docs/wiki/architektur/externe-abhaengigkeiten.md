# Architektur: Externe Abhängigkeiten

## Zweck

Dokumentation wichtiger 3rd-Party-Bibliotheken und Dienste.

## Abhängigkeiten

- **Jetpack Libraries**: Compose, Navigation, ViewModel, Room, Datastore.
- **Network**: `com.squareup.retrofit2:retrofit`, `com.squareup.okhttp3:okhttp`.
- **JSON Parsing**: `com.google.code.gson:gson`
- **Image Loading**: `io.coil-kt:coil-compose`
- **Location Services**: `com.google.android.gms:play-services-location` (Für Nearby Stations und Tracking).
- **Coroutines**: `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- **Kartenrendering**: MapLibre Native für die interaktive Android-Karte; Kartenstil und Kacheln von [OpenFreeMap](https://openfreemap.org/) mit Daten von OpenMapTiles/OpenStreetMap.
- **Verkehrsdaten der Live-Karte**: [Transitous / MOTIS 2](https://transitous.org/api/) als von Träwelling unabhängige Schnittstelle.

## Verwandte Seiten

- [Architektur Überblick](./ueberblick.md)
- [Transitous Live-Karte](../features/transitous-live-karte.md)
