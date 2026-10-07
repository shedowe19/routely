# Architektur: Externe Abhängigkeiten

## Zweck

Dokumentation wichtiger 3rd-Party-Bibliotheken und Dienste.

## Abhängigkeiten

- **Jetpack Libraries**: Compose, Navigation, ViewModel, Room, Datastore.
- **AndroidX Fragment**: `androidx.fragment:fragment:1.9.1` wird explizit über `libs.androidx.fragment` eingebunden. Die ursprüngliche 1.7.1-Ergänzung behob den ActivityResult-/Release-Lint-Konflikt alter transitiver Play-Services-Abhängigkeiten; die direkte Abhängigkeit bleibt beim Update erhalten. Details: [Build](../entwicklung/build.md).
- **Network**: `com.squareup.retrofit2:retrofit`, `com.squareup.okhttp3:okhttp`.
- **JSON Parsing**: `com.google.code.gson:gson`
- **Image Loading**: `io.coil-kt.coil3:coil-compose`, explizites `coil-network-okhttp` und `coil-network-cache-control`; zentraler Loader über `SingletonImageLoader.Factory` mit User-Agent und `CacheControlCacheStrategy`.
- **Location Services**: `com.google.android.gms:play-services-location` (Standort für die Stationssuche und Standort-Callbacks im `TripTrackingService` sowie Opt-in-`RideRecognitionService`).
- **Coroutines**: `org.jetbrains.kotlinx:kotlinx-coroutines-android`; `kotlinx-coroutines-test` ausschließlich als Testabhängigkeit mit derselben Version 1.11.0 für kontrollierte Controller-Requestfolgen.

## Gewählter Versionsstand vom 06.10.2026

Quelle der tatsächlich angeforderten Werte ist `gradle/libs.versions.toml`. AndroidX-AAR-/BOM- und Toolchain-Metadaten wurden für compileSdk 36 geprüft. Ein Versionskatalog allein belegt keinen erfolgreichen Build; den passenden Nachweis enthält [Tests](../entwicklung/tests.md).

| Bereich | Version |
| --- | --- |
| AGP / Gradle | 8.13.2 / 8.14.5 |
| Kotlin / Compose-Compilerplugin | 2.3.21 / 2.3.21 |
| KSP | 2.3.12 |
| Core KTX / Activity Compose / Fragment | 1.18.0 / 1.13.0 / 1.9.1 |
| Lifecycle / Navigation Compose | 2.10.0 / 2.9.8 |
| Compose BOM | 2026.06.01 |
| Compose UI, Foundation, Runtime / Material 3 / Icons | 1.11.4 / 1.4.0 / 1.7.8 über die BOM |
| Room / DataStore | 2.8.5 / 1.2.1 |
| Retrofit / Gson-Konverter | 3.0.0 |
| OkHttp / Logging-Interceptor | 5.4.0 |
| Coroutines / Gson / Browser | 1.11.0 / 2.14.0 / 1.10.0 |
| Coil inklusive Netzwerk-/Cache-Control-Module | 3.4.0 |
| Play Services Location | 21.4.0 |

Höhere Releases werden nicht pauschal übernommen: Core 1.19.1, Lifecycle 2.11, Navigation 2.10 und neuere Compose-BOM-Stände verlangen API 37 beziehungsweise AGP 9.1 in den geprüften AAR-Metadaten. Auch die tatsächlich von Gradle gewählte Android-Variante von OkHttp 5.5.0 verlangt compileSdk 37; deshalb wird 5.4.0 mit API-36-Anforderung verwendet. Ein passendes JVM-POM allein reicht für Android-Kompatibilität nicht aus. Kotlin 2.4.20 verlangt neueres R8 als das zu AGP 8.13.2 passende 8.13.19. Coil 3.6.x verlangt API 37, 3.5.0 bringt eine Kotlin-2.4-Standardbibliothek mit; 3.4.0 passt zum gewählten Kotlin-2.3-Stand. Diese Auswahl erhält minSdk 26, targetSdk 34 und compileSdk 36, statt neben dem Bibliotheksupdate weitere Plattformverträge zu verändern.

Primärquellen: [AndroidX-Versionen](https://developer.android.com/jetpack/androidx/versions), [Compose BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping), [Android-Kotlin-/R8-Tabelle](https://developer.android.com/build/kotlin-support), [AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [KSP-Releases](https://github.com/google/ksp/releases), [Coil-3-Migration](https://coil-kt.github.io/coil/upgrading_to_coil3/) sowie Publisher-Artefakte aus Google Maven und Maven Central. Die AAR-Metadaten von [OkHttp Android 5.4.0](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-android/5.4.0/okhttp-android-5.4.0.aar) beziehungsweise [5.5.0](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-android/5.5.0/okhttp-android-5.5.0.aar) enthalten `minCompileSdk=36` beziehungsweise `37`. [Build](../entwicklung/build.md) erklärt Compilerplugin und API-Migration.

## Standortnutzung beim Stationsalarm

Der [GPS-Stationsalarm](../module/trip-tracking.md) verwendet `FusedLocationProviderClient` mit eigenen Callbacks. Der gewünschte Abstand beträgt 12 Sekunden unterwegs und 3 Sekunden bis 3 km zum aktuellen Halt; die Vorgaben garantieren keine tatsächliche Zustellrate. `StationTrackingEngine` wertet die Positionen als reine Kotlin-Logik aus und ist unabhängig von Android und Netzwerk getestet.

Das Manifest deklariert `location|dataSync` und `FOREGROUND_SERVICE_LOCATION`. Der Service aktiviert GPS erst nach einem Start aus der sichtbaren Activity mit präziser Runtimefreigabe; sonst läuft der Fahrplanmodus. Geofencing ist keine Hauptquelle: Hintergrundereignisse können mehrere Minuten verzögert sein.

Stationskoordinaten stammen grundsätzlich aus Träwelling-Stopovers. Für erkannte Ersatzverkehrsfahrten kann die [SEV-Zuordnung](../module/sev-haltestellen.md) einen eindeutigen aktuellen Punkt aus der öffentlichen bahnhof.de-Karte in die lokale Tracking-Projektion übernehmen. Bei fehlender oder mehrdeutiger Zuordnung bleibt die API-Koordinate erhalten. Die Annäherung wird lokal berechnet; Gerätepositionen werden nicht gespeichert oder an Träwelling beziehungsweise bahnhof.de gesendet. Eine bereits geladene Haltfolge und Besuchsfortschritt werden in DataStore gecacht und können unabhängig von weiteren API-Erfolgen verwendet werden.

## Öffentliche Ersatzhaltestellen

`BahnhofSevParser` verwendet vorhandenes Gson für JSON-Datensätze der Bahnhofskarten; der separate öffentliche Abruf verwendet vorhandenes OkHttp. Es wird weder eine PDF-/QR-Bibliothek noch ein Karten-, Routing- oder Geocoding-SDK ergänzt. Die Website-Quelle benötigt keinen Bearer-Token. Geänderte HTML-Daten sind ein möglicher Quellenfehler und dürfen das bestehende Tracking nicht blockieren. Details: [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md).

## Nativer Bahn-/Tram-Streckenverlauf

`TransitRouteRepository` und `TransitRouteParser` verwenden OkHttp/Gson für den vorhandenen Träwelling-Endpunkt `polyline/{statusId}`. Die Anmeldung gehört ausschließlich zum eigenen konfigurierten Server; es gibt keinen zusätzlichen Schienenprovider oder Routing-SDK. Geordnete Besuchsbindung, Größen-/Kurven-/Mehrdeutigkeitsprüfungen und höchstens 15 Minuten RAM-Gültigkeit begrenzen die Nutzung. Die Form kann Backend-Strecken und Stationssehnen mischen und ist keine garantiert aktuelle amtliche Gleisführung. Gerätefixes bleiben lokal. Schnittstelle und Entscheidung stehen unter [Externe Schnittstellen](../api/externe-schnittstellen.md) und [ADR](../entscheidungen/2026-10-06-native-streckenverlaeufe.md).

## Optionale Straßen-Geometrie

`RoadRouteRepository` verwendet vorhandenes OkHttp für den öffentlichen FOSSGIS-OSRM-Dienst `routing.openstreetmap.de/routed-car`; `RoadRouteParser` prüft GeoJSON mit vorhandenem Gson. Es kommt kein Routing-SDK hinzu, aber ein zusätzlicher externer HTTP-Dienst. Angefragt werden nur zwei eindeutig zugeordnete öffentliche SEV-Endpunkte, keine Gerätepositionen und keine Zugangsdaten.

Das Pkw-Profil liefert mögliche Straßenwege, keine offiziellen Ersatzbusfahrwege oder Bus-Echtzeit. Der Zeitschätzer verwendet nur geeignete Geometrien lokal; Stationsengine und Ansageradius bleiben getrennt. Eine gescheiterte oder unpassende Anfrage blockiert die Begleitung nicht und führt zum normalen Zeitquellenrückfall. RAM-Cache, Request-Limit, Attribution und Anbieterbedingungen stehen unter [Externe Schnittstellen](../api/externe-schnittstellen.md) und [GPS-Zeiten](../module/gps-zeiten.md).

## Fahrterkennung und Android-Fortschritt

Die [Fahrterkennung](../module/ride-recognition.md) nutzt dieselbe Träwelling-Schnittstelle und Play Services, hat aber einen getrennten sichtbaren Location-Service. Ihre Stationssuche überträgt aus dem aktuellen Fix berechnete Bounding-Box-Grenzen an `GET /api/v1/stations`; lokale Zuordnung und RAM-Historie liegen in der App. Die Boxmitte entspricht dem verwendeten Standort. Der aktive Stationsalarm selbst überträgt keine Gerätepositionen.

Der [Reisefortschritt](../module/trip-progress.md) nutzt ab API 36 die Framework-Notification-API und auf älteren Geräten AndroidX-Core 1.18.0. Der aktuelle SDK-/Toolchainstand steht oben; targetSdk 34/minSdk 26 bleiben bestehen. Für GPS-Geometrie, Fahrterkennung und Fortschritt wurde kein zusätzliches Routing-, Karten- oder KI-SDK eingeführt.

## Flutter-Migrationsstand vom 07.10.2026

Die plattformübergreifende Anwendung liegt unter `flutter/`; der bisherige Kotlin-/Compose-Quellstand unter `app/` bleibt eine Verhaltensreferenz. Aktuelle Schichten, Funktionsvergleich und Plattformgrenzen stehen in der [Flutter-Architektur](../architektur/flutter-migration.md), Werkzeugketten und Releasepfade unter [Flutter-Entwicklung](../entwicklung/flutter.md). Die übrigen Kotlin-Dateipfade auf dieser Seite beschreiben den erhaltenen Ausgangsstand.

## Verwandte Seiten

- [Architektur Überblick](./ueberblick.md)
- [TripTracking](../module/trip-tracking.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Reisefortschritt](../module/trip-progress.md)
- [Build](../entwicklung/build.md)
