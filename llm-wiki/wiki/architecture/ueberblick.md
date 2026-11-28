# Architekturüberblick

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite erklärt die Schichten, die wichtigsten Bausteine und die Grenzen
zwischen ihnen. Sie ist der Einstieg, bevor man eine Datei ändert.

## Schichten

| Schicht | Paket | Verantwortung |
|---|---|---|
| UI | `ui/` | Compose-Screens, Komponenten, Navigation, Theme. Keine Netzwerk- oder Persistenzlogik. |
| Präsentation | `viewmodel/` | Feature-ViewModels plus reine Controller für Feed, Check-in, Statusdetail, Profile, Benachrichtigungen. |
| Anwendungsdienste | `service/` | Foreground-Services für Live-Tracking und Fahrterkennung, GPS-Zeitlogik, TTS, Notification-Aufbau. |
| Domäne und I/O | `data/` | Retrofit-API, Repositories, Modelle, Room, Mutation-Ledger, DBF, Routing, SEV. |
| Querschnitt | `util/` | Preferences/DataStore-Gesundheit, `AuthSession`, Formatierung, Storage-Recovery. |
| Einstieg | `MainActivity.kt`, `TraewellingApplication.kt` | Activity, Root-Gate, Service-Resume, Coil-Image-Loader. |

Die Richtung ist strikt nach innen: `ui` → `viewmodel` → `data`/`service`.
`data` kennt keine Compose-Typen. `service` kennt `ui` nur über Intents und
`MainActivity` als Notification-Ziel.

## Bausteine

- **Ein Activity, ein NavHost.** `MainActivity` hostet `MainNavigation`, ein
  `NavHost` trägt `main`, `userProfile/{username}`, `userSearch`,
  `statusDetail/{statusId}` und `settings`. Die vier Hauptbereiche Feed,
  Check-in, Meldungen und Profil sind Seiten eines `HorizontalPager`, keine
  eigenen Routen (`ui/navigation/AppNavigation.kt:23-30`, `:76-235`).
- **ViewModel + Controller.** ViewModels halten `StateFlow<UiState>` und
  delegieren Fachlogik an testbare Controller wie `FeedController` oder
  `CheckInSubmission`. Die Controller sind reine Kotlin-Klassen ohne Android-
  Abhängigkeit, soweit möglich.
- **Repositories ohne DI-Container.** Jedes Feature-ViewModel erzeugt sein
  eigenes `TraewellingRepository(application, prefs)`. Es gibt kein Hilt/Koin.
  Das ist bewusst einfach, kostet aber Client- und Cache-Wiederverwendung.
- **Prozessweiter Mutations-Ledger.** `StatusMutationEvents` serialisiert
  Schreibvorgänge pro Konto und Status über 64 Mutex-Streifen und verteilt
  Ereignisse als `SharedFlow`. Leser prüfen Revisionen, damit eine alte Antwort
  keinen neueren Schreibvorgang überschreibt.
- **Zwei Foreground-Services.** `TripTrackingService` begleitet genau eine
  aktive Fahrt; `RideRecognitionService` sucht nur auf ausdrücklichen
  Nutzerwunsch nach einer möglichen Fahrt. Beide sind nicht exportiert.
- **Passives Widget.** `TripWidgetProvider` pollt nicht (`updatePeriodMillis=0`)
  und wird ausschließlich vom Tracking-Service per Broadcast aktualisiert.

## Wie es zusammenspielt

1. `MainActivity` lädt die Session aus DataStore und zeigt die Oberfläche erst,
   wenn ein frisch bestätigter Storage-Snapshot vorliegt.
2. Screens sammeln Eingaben; ViewModels rufen Controller und Repositories auf.
3. Repositories validieren die Session vor und nach jedem Aufruf. Ein
   Kontowechsel bricht laufende Antworten mit `CancellationException` ab.
4. Schreibvorgänge laufen durch den Mutations-Ledger, invalidieren den
   Feed-Cache und werden als Ereignis an offene Screens verteilt.
5. Der Tracking-Service pollt Status und Haltestellen, reichert sie mit
   DBF/SEV an, verarbeitet GPS-Fixes und schreibt Fortschritt sowie Widget.

## Grenzen und bewusste Entscheidungen

- **Keine Schreiboperation ohne Serverbestätigung.** Ein lokaler Speicherfehler
  nach einem HTTP-Commit wird als „akzeptiert, nicht wiederholen“ gemeldet
  (`StatusWriteAcceptedException`, `CheckInAcceptedException`).
- **Keine erfundenen Echtzeitdaten.** Fehlende oder mehrdeutige Providerdaten
  führen zu „unbekannt“, nicht zu einer Prognose. Das gilt für DBF, OSRM,
  SEV und GPS.
- **Keine stillschweigende Datenlöschung.** Korrupte Preferences blockieren die
  App; ein Reset braucht die Bestätigung des aktuellen Fehlers.
- **Keine echte DI.** Neue Features konstruieren ihre Abhängigkeiten selbst.
  Wer Client-Wiederverwendung braucht, muss das bewusst einführen.

## Risiken

- Die fachliche Logik liegt teilweise in sehr großen Klassen
  (`TripTrackingService.kt` ≈ 1.900 Zeilen). Änderungen dort brauchen die
  Tests aus `app/src/test/kotlin/de/traewelling/app/service/`.
- Fehlermapping ist nicht einheitlich: teils `HttpException`, teils
  `IllegalStateException` mit Code in der deutschen Meldung.
- Es gibt keine Compile-Zeit-Kopplung zwischen DBF-, OSRM- und SEV-Verträgen;
  externe Änderungen fallen nur über Parser-Tests auf.

## Verwandte Seiten

- [Datenfluss](datenfluss.md)
- [Externe Abhängigkeiten](externe-abhaengigkeiten.md)
- [Datenzugriff](../modules/datenzugriff.md)
- [Index](../index.md)
