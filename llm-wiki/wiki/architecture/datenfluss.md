# Datenfluss

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite zeigt die vier wichtigsten Abläufe: Lesen, Schreiben, Live-
Begleitung und Anreicherung mit öffentlichen Daten. Sie erklärt, wo Revisions-
prüfungen und Fallbacks sitzen.

## 1. Lesen (Feed und Statusdetail)

```
Screen → ViewModel/Controller → TraewellingRepository → Retrofit
                                      ↓
                               Room (nur Seite 1)
```

- `getDashboard(page)` und `getGlobalFeed(page)` schreiben ausschließlich
  `page == 1` in Room. Höhere Seiten sind reine Netzwerkantworten.
- Der Cache-Schlüssel ist `type + sha256(serverUrl + NUL + accessToken)`.
  Damit teilen zwei Konten auf derselben Instanz keinen Cache.
- Offline-Fallback gilt nur für Seite 1 und nur bei `IOException`, HTTP 408,
  429 oder 5xx. Bei 401/403, kaputtem JSON oder Storage-Fehler gibt es keinen
  Fallback (`data/repository/TraewellingRepository.kt:65-112`).
- Ein Mutationsereignis invalidiert die Cache-Partition des Kontos. Bis eine
  frische Seite-1-Antwort vorliegt, ist Offline-Lesen gesperrt.
- Das Statusdetail liest Status und Haltestellen in zwei Aufrufen und prüft
  danach die Mutationsrevision. Ändert sie sich, wird genau einmal neu gelesen;
  danach bricht der Aufruf ab (`viewmodel/StatusDetailRead.kt`).

## 2. Schreiben (Like, Check-in, Bearbeiten, Löschen)

```
ViewModel → Controller → Repository → Mutex-Streifen → HTTP
                                            ↓
                                  Cache-Invalidierung
                                  Tracking-Invalidierung
                                  SharedFlow-Ereignis
```

- Schreibvorgänge für dasselbe Konto und denselben Status teilen einen von 64
  Mutex-Streifen. Das Warten ist abbrechbar; nach dem Absenden laufen HTTP,
  Invalidierung und Ereignis in `NonCancellable`
  (`data/repository/TraewellingRepository.kt:194-230`).
- Ein Check-in sendet genau einen POST. Manuelle Zeiten werden anschließend
  über einen PUT nachgezogen, nie in den POST eingebettet
  (`viewmodel/CheckInSubmission.kt:20-66`).
- Ein erfolgreicher POST ohne verwertbare Antwort ist „akzeptiert“ und wird
  nicht wiederholt (`CheckInAcceptedException`). Dasselbe gilt für einen PUT
  ohne Body (`StatusWriteAcceptedException`).
- Ein Speicherfehler vor dem Absenden verhindert den HTTP-Aufruf. Ein
  Speicherfehler nach dem Commit macht die Operation nicht rückgängig.
- Löschen ist server-first. Die lokale Tracking-Bereinigung ist auf 15 s
  begrenzt und kann einen bestätigten Löschvorgang nicht in einen Fehler
  verwandeln (`viewmodel/DeletedStatusCompletion.kt`).

## 3. Live-Begleitung

```
MainActivity ──startForegroundService──▶ TripTrackingService
                                             │
                    ┌────────────────────────┼─────────────────────────┐
                    ▼                        ▼                         ▼
              Status/Stopovers          GPS-Fixes                 Notification
              (alle 60 s)               (3 s nah / 12 s fern)     + Widget + TTS
                    │                        │
                    ▼                        ▼
             DBF-/SEV-Anreicherung    StationTrackingEngine
                                      GpsJourneyTimeEstimator
```

1. Der Dienst startet nur aus einer sichtbaren Activity; ein Sticky-Restart
   ohne Extras aktiviert kein GPS.
2. `refreshTrip` holt Status und Haltestellen, reichert sie mit DBF und SEV an
   und übergibt sie an die Engine.
3. `StationTrackingEngine` führt einen geordneten Besuchs-Cursor. Ein Fix zählt
   nur bei Alter ≤ 30 s, Genauigkeit ≤ 100 m und plausibler Bewegung.
4. `GpsJourneyTimeEstimator` leitet lokale Ankunfts-/Abfahrtszeiten ab.
   `JourneyTimeResolver` wählt die Anzeigequelle in dieser Reihenfolge:
   GPS beobachtet → GPS geschätzt → manuell → API-Echtzeit → Fahrplan.
5. Jede Veröffentlichung ist an `generation`, `statusId`, `!stopping` und die
   Auth-Session-Revision gebunden (`TripTrackingService.kt:1422-1424`).
6. Bei einem Storage-Fehler pausiert der Dienst genau einmal und behält den
   gespeicherten Reisezustand.

## 4. Anreicherung mit öffentlichen Daten

- **DBF/IRIS:** nur für Bahn-Kategorien, höchstens zwei Stationen und sechs
  Besuche, mit 8-s-Gesamtdeadline. Jedes Feld behält sein eigenes Lesealter;
  eine reine Gleisänderung erneuert keine alte Zeit.
- **SEV:** nur Ersatzbusse (`Bus RE/RB`). Der Enricher bindet Start und Ziel
  eindeutig, verwirft Ausfälle und fragt höchstens 64 Haltestellen-Slugs mit
  drei parallelen Abrufen ab.
- **Streckengeometrie:** native Träwelling-Polylinie für Bahn/Tram, OSRM für
  Straßenabschnitte. Beide werden nur übernommen, wenn die Geometrie zur
  geordneten Haltfolge passt; sonst bleibt der Abschnitt ohne Kurvenbeleg.

## Invarianten

- Eine Antwort darf nie zu einer anderen Session gehören.
- Eine bestätigte Schreiboperation darf nie erneut gesendet werden.
- Ein GPS-Fix ohne ausreichende Qualität ist kein Fortschritt.
- Ohne eindeutige Providerdaten wird nichts geschätzt.

## Verwandte Seiten

- [Architekturüberblick](ueberblick.md)
- [Datenzugriff](../modules/datenzugriff.md)
- [Live-Datenquellen](../api/live-datenquellen.md)
- [Tracking-Service](../modules/tracking-service.md)
- [Index](../index.md)
