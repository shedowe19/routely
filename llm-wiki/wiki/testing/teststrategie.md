# Teststrategie

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite erklärt, was die Tests abdecken, was sie nicht abdecken und wie
man sie ausführt. Sie verhindert, dass ein grüner Lauf mit „auf Gerät
verifiziert“ verwechselt wird.

## Ausführung

```bash
./gradlew :app:testDebugUnitTest
```

CI führt zusätzlich `:app:lintDebug`, `:app:assembleDebug` und
`:app:assembleRelease` aus (`.github/workflows/api-compatibility.yml`).

## Art der Tests

- Ausschließlich JVM-Unit-Tests unter `app/src/test/kotlin/`.
- Kein `androidTest/`, keine Compose-UI-Tests, keine Instruments.
- Kein Robolectric, kein Mockito. Die Tests instanziieren reine Kotlin-Logik
  und Fakes.
- Netzwerk wird nicht aufgerufen; Fixtures liegen unter
  `app/src/test/resources/`.

## Abdeckungsschwerpunkte

| Bereich | Beispiele |
|---|---|
| API-Verträge | `ApiCompatibilityTest`, `TraewellingApiServiceTest` |
| Session/Revisionsschutz | `AuthSessionTest`, `AuthRepositoryTest` |
| Schreibreihenfolge | `StatusWriteOrderingTest`, `StatusMutationRepositoryTest` |
| Check-in | `CheckInSubmissionTest`, `CheckInStorageCommitTest` |
| Feed/Likes | `FeedLikeOrderingTest`, `FeedTabSwitchLikeTest` |
| Statusdetail | `StatusDetailReadTest`, `StatusEditRequestTest` |
| Tracking | `StationTrackingEngineTest`, `TunnelGpsRecoveryTest` |
| GPS-Zeiten | `GpsJourneyTimeEstimatorTest`, `JourneyTimeResolverTest` |
| Geometrie | `TransitRouteParserTest`, `GpsRoadConsensusTest` |
| DBF | `DbfMatcherTest`, `DbfRealtimeRepositoryTest` |
| SEV | `SevDirectionalValidityTest`, `BahnhofSevParserTest` |
| Storage | `PreferenceStorageTest`, `StorageRecoveryPresentationTest` |
| UI-Logik | `DepartureTimePresentationTest`, `StopTimelineProgressTest` |

## Fixtures

- `resources/dbf/` — echte DBF-v3-Antwort, anonym abgerufen.
- `resources/routing/` — OSRM-Antworten für öffentliche Halte.
- `resources/routing/transit/` — native Polylinien und Haltelisten.
- `resources/traewelling/` — Status-, Nutzer-, Fahrt- und Abfahrtsformen.

Jede Fixture hat eine README mit Herkunft, Datum und Lizenzhinweis. Die Daten
enthalten keine Tokens, Konten oder Gerätepositionen.

## Was die Tests beweisen

- Reine Fachlogik: Reihenfolgen, Fallbacks, Validierung, Parser-Grenzen,
  Revisionsschutz, Speicherfehler.
- Sie beweisen **nicht**: Service-Lebenszyklus, Foreground-Service-Timeouts,
  Notification-Rendering, Widget-Aktualisierung, Compose-Layout,
  Berechtigungsdialoge oder OEM-Verhalten.

## Bekannte Lücken

- Keine Geräte- oder Emulator-Tests.
- Keine UI-Tests für Screens und Navigation.
- Keine Tests für Android-15-`onTimeout`.
- Keine Tests für echte OAuth-Flows (nicht implementiert).
- Keine Last-/Akkutests für den 60-s-Poll und die Standortnutzung.

## Risiken

- Ein grüner Unit-Test-Lauf sagt nichts über das Verhalten auf einem echten
  Gerät aus.
- Fixtures frieren externe Formate ein; Änderungen bei DBF, OSRM oder
  bahnhof.de werden erst bei einem neuen Abruf sichtbar.
- Tests, die Android-Klassen berühren, sind bewusst als reine Logik
  extrahiert. Neue Features sollten diesem Muster folgen.

## Verwandte Seiten

- [Build und Release](../infrastructure/build-und-release.md)
- [Tracking-Service](../modules/tracking-service.md)
- [Offene Fragen](../questions/offene-fragen.md)
- [Index](../index.md)
