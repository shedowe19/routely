# Architektur: Externe Abhängigkeiten

## Zweck

Dokumentation wichtiger 3rd-Party-Bibliotheken und Dienste.

## Abhängigkeiten

- **Jetpack Libraries**: Compose, Navigation, ViewModel, Room, Datastore.
- **AndroidX Fragment**: `androidx.fragment:fragment:1.7.1` wird explizit über `libs.androidx.fragment` eingebunden. Play Services 21.2.0 fordert alte Fragment-Versionen transitiv an; die direkte Abhängigkeit erfüllt die ActivityResult-Mindestversion 1.3.0 und behebt den Release-Lint-Konflikt. Details: [Build](../entwicklung/build.md).
- **Network**: `com.squareup.retrofit2:retrofit`, `com.squareup.okhttp3:okhttp`.
- **JSON Parsing**: `com.google.code.gson:gson`
- **Image Loading**: `io.coil-kt:coil-compose`
- **Location Services**: `com.google.android.gms:play-services-location` (Nearby Stations und laufende Standort-Callbacks im `TripTrackingService`).
- **Coroutines**: `org.jetbrains.kotlinx:kotlinx-coroutines-android`

## Standortnutzung beim Stationsalarm

Der [GPS-Stationsalarm](../module/trip-tracking.md) verwendet `FusedLocationProviderClient` mit eigenen Callbacks. Der gewünschte Abstand beträgt 12 Sekunden unterwegs und 3 Sekunden bis 3 km zum aktuellen Halt; die Vorgaben garantieren keine tatsächliche Zustellrate. `StationTrackingEngine` wertet die Positionen als reine Kotlin-Logik aus und ist unabhängig von Android und Netzwerk getestet.

Das Manifest deklariert `location|dataSync` und `FOREGROUND_SERVICE_LOCATION`. Der Service aktiviert GPS erst nach einem Start aus der sichtbaren Activity mit präziser Runtimefreigabe; sonst läuft der Fahrplanmodus. Geofencing ist keine Hauptquelle: Hintergrundereignisse können mehrere Minuten verzögert sein.

Stationskoordinaten stammen aus Träwelling-Stopovers. Die Annäherung wird lokal berechnet; Positionen werden nicht gespeichert oder an Träwelling gesendet. Eine bereits geladene Haltfolge und Besuchsfortschritt werden in DataStore gecacht und können unabhängig von weiteren API-Erfolgen verwendet werden.

## Verwandte Seiten

- [Architektur Überblick](./ueberblick.md)
- [TripTracking](../module/trip-tracking.md)
