# Datenzugriff und Repositories

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite erklärt, wie Routely Serverdaten liest, schreibt und lokal hält.
Sie ist die erste Anlaufstelle für Änderungen an `data/repository/`.

## Bausteine

| Datei | Aufgabe |
|---|---|
| `data/repository/TraewellingRepository.kt` | zentraler Lese-/Schreibzugriff auf die Träwelling-API |
| `data/repository/AuthRepository.kt` | Login, Sessionvalidierung, OAuth-Austausch, Logout |
| `data/repository/AuthSessionStore.kt` | Persistenzgrenze für Token und Revision |
| `data/repository/StatusMutation.kt` | prozessweites Ledger, Mutex-Streifen, Ereignisstrom |
| `data/repository/ApiResult.kt` | Cancellation-sichere Ergebnis-Hülle |
| `data/local/` | Room-Datenbank `traewelling_database`, Tabelle `feed_statuses` |

## Lesen

- `getDashboard(page)` und `getGlobalFeed(page)` liefern `Status`-Listen.
- Nur Seite 1 wird in Room geschrieben. Der Cache-Typ ist
  `dashboard:<hash>` bzw. `global:<hash>`, wobei der Hash aus Server-URL und
  Access-Token gebildet wird.
- Ein Cache-Schreibvorgang prüft die Session vor und nach dem Schreiben.
- Offline-Fallback: nur Seite 1, nur `IOException`/408/429/5xx.
- `getStatusDetailSnapshot` liest Status und Stopovers konsistent und prüft
  die Mutationsrevision; bei Änderung wird einmal wiederholt.
- `getNearbyStations` verwendet eine feste Bounding-Box um die Koordinaten und
  ruft `api/v1/stations` auf. Der Retrofit-Endpunkt
  `api/v1/trains/station/nearby` ist ungenutzt.

## Schreiben

Ablauf für Like, Unlike, Bearbeiten und Löschen:

1. Session und Zielstatus bestimmen.
2. Mutex-Streifen für Konto + Status reservieren (abbrechbar).
3. HTTP-Aufruf absenden.
4. Nach Erfolg: Cache invalidieren, Tracking invalidieren, Ereignis
   veröffentlichen — alles in `NonCancellable`.
5. Bei Fehler vor dem Commit: normaler Fehler, kein Ereignis.
6. Bei Fehler nach dem Commit (z. B. Storage): typisierter „akzeptiert“-Fehler.

`StatusMutationEvents` ist ein heißer `SharedFlow` mit Puffer 32 und
`DROP_OLDEST`. Unter Last können Ereignisse fehlen; Leser müssen deshalb
zusätzlich den Serverstand prüfen.

## Revisionsschutz

`AuthSession(serverUrl, accessToken, revision)` ist die atomare
Sessionaufnahme. Jede Token- oder Kontenänderung erzeugt eine neue UUID-
Revision. Dadurch kann eine alte Antwort nicht:

- den Feed eines neuen Kontos füllen,
- einen Status eines neuen Kontos löschen,
- eine laufende Fahrt einem anderen Konto zuordnen.

`saveTokens` und `saveValidated` rotieren die Revision. Ein reiner
Benutzernamens-Update (`saveUsernameIfMatches`) rotiert sie bewusst nicht.

## Fehlerklassen

| Typ | Bedeutung |
|---|---|
| `CheckInConflictException` | HTTP 409 mit vorhandenen Status |
| `CheckInAcceptedException` | Check-in serverseitig angenommen, lokal unklar |
| `StatusWriteAcceptedException` | PUT/DELETE serverseitig angenommen, lokal unklar |
| `OAuthRefreshError` | streng validierter Refresh-Fehler |
| `PreferenceStorageException` | lokale Persistenz gestört |

Die ersten drei dürfen nie automatisch wiederholt werden.

## Bekannte Eigenheiten

- Jedes Feature-ViewModel erzeugt ein eigenes Repository und damit neue
  Retrofit-/OkHttp-Clients. Connection-Pooling zwischen Features findet nicht
  statt.
- Der Feed-Cache wird beim Logout nicht global gelöscht. Alte Partitionen
  bleiben unter ihrem Token-Hash liegen.
- Der Ledger hat 64 Mutex-Streifen und begrenzte Revisionsbücher. Bei Überlauf
  wird konservativ invalidiert; ein Überlauf kann Offline-Caching für den
  restlichen Prozess abschalten.
- `apiResult` reicht `CancellationException` weiter. „Fehler“ bedeutet daher
  nie „abgebrochen“.

## Tests

`app/src/test/kotlin/de/traewelling/app/data/repository/` enthält unter anderem:

- `TraewellingRepositoryTest` — Cache-Isolation, Offline-Fallback, Abbruch.
- `StatusMutationRepositoryTest` — Serialisierung, späte Commits, Überlauf.
- `StatusWriteOrderingTest` — Löschen wartet auf PUT, Likes invalidieren alte
  Feed-Antworten.
- `StorageCommitRecoveryRepositoryTest` — typisierte Teil-erfolge.
- `AuthRepositoryTest` — Sessionwechsel, Refresh-Single-Flight.

## Risiken

- Status- und Stopover-Lesen sind zwei HTTP-Aufrufe; die Revisionsprüfung ist
  die einzige Klammer.
- Fehlermapping ist historisch gewachsen und nicht typisiert.
- Der Cache enthält vollständige Status-JSONs, also auch private Feed-Inhalte,
  bis die Partition ersetzt oder die App-Daten gelöscht werden.

## Verwandte Seiten

- [Datenfluss](../architecture/datenfluss.md)
- [Lokale Speicherung](../data/lokale-speicherung.md)
- [Träwelling-API](../api/traewelling-api.md)
- [Index](../index.md)
