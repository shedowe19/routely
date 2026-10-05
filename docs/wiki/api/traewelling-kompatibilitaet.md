# Träwelling-API-Kompatibilität

## Zweck

Dokumentiert die Prüfung der von Routely verwendeten Träwelling-Endpunkte und Antwortfelder sowie die Migration auf den API-Vertrag vom 05.10.2026.

## Kontext und Prüfstand

- Routely-Ausgangsstand: `main`, Commit `467ef1264c1e846c72fc51e4375b04b3feb2fee8`.
- Geprüfter Träwelling-Stand: `develop`, Commit `4d602796da8409017314cc771b1127d169155f02`.
- Quellen: Upstream `API_CHANGELOG.md`, `routes/api.php`, zugehörige API-Controller, Requests und Ressourcen. Der konkrete Routen- und Ressourcenvertrag wurde zusätzlich zum Changelog geprüft.
- Umfang: alle vorhandenen Retrofit-Endpunkte, Gson-Modelle und deren Verwendung in Repository, UI, ViewModels, Tracking, Notification und Widget.
- Der bereits vorhandene Transitous-Live-Map-PR #35 (`codex/transitous-live-map`) ist ein separater Arbeitsstand. Diese Migration basiert auf `main` und enthält dessen Änderungen nicht.

Die vorhandenen Endpunktpfade sind gültig. Die gefundenen Inkompatibilitäten betreffen vor allem Antwortfelder und die Unterscheidung zwischen Station und konkretem Halt innerhalb einer Fahrt.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/data/api/TraewellingApiService.kt`
- `app/src/main/kotlin/de/traewelling/app/data/model/Models.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/TraewellingRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/StatusDetailViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/CheckInScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/components/StatusCard.kt`
- `app/src/test/kotlin/de/traewelling/app/`
- `.github/workflows/api-compatibility.yml`

## Verhalten: verwendete Endpunkte

Alle folgenden Pfade haben das Präfix `/api/v1/`; OAuth verwendet `/oauth/token` außerhalb dieses Präfixes.

| Gruppe | Verwendete Pfade | Ergebnis |
| --- | --- | --- |
| Authentifizierung | `GET auth/user`, `POST auth/logout` | Weiterhin gültig; Login und Refresh über OAuth |
| Feeds | `GET dashboard`, `GET statuses` | Weiterhin gültig; Status enthält `user` und `checkin` |
| Einzelstatus | `GET/PUT/DELETE status/{id}`, `POST/DELETE status/{id}/like` | Weiterhin gültig; Status-ID bleibt numerisch |
| Stationssuche | `GET trains/station/autocomplete/{query}`, `GET trains/station/nearby`, `GET stations` | Weiterhin gültig; Stationskennungen in `identifiers` |
| Abfahrten | `GET station/{id}/departures` | Weiterhin gültig; interne numerische Station-ID, nicht IBNR |
| Fahrtdetails | `GET trains/trip` | Weiterhin gültig; Query `hafasTripId` und `lineName` |
| Fahrthalte | `GET stopovers/{tripId}` | Weiterhin gültig; `data` ist eine Map von numerischer Trip-ID zu Haltliste |
| Check-in | `POST trains/checkin` | Weiterhin gültig; Stationsreferenzen und Konfliktantwort migriert |
| Statistik | `GET statistics` | Weiterhin gültig; neue Statistikendpunkte sind zusätzliche Angebote |
| Nutzer | `GET user/{username}`, `GET user/{username}/statuses`, `GET user/search/{query}` | Weiterhin gültig |
| Folgen | `POST/DELETE user/{id}/follow` | Weiterhin gültig; numerische Nutzer-IDs weiterhin erlaubt |
| Benachrichtigungen | `GET notifications`, `GET notifications/unread/count`, `PUT notifications/read/{id}`, `PUT notifications/read/all` | Weiterhin gültig; UI verwendet Plain-Text-Felder |

## Verhalten: Modellmigration

| Bereich | Befund im Ausgangsstand | Aktueller Zugriff |
| --- | --- | --- |
| Status | `checkin` und `user` waren bereits korrekt | Keine Abhängigkeit von den am 27.09.2026 entfernten `train` und `userDetails` |
| Mastodon | Gleichnamige Felder in verschiedenen Ressourcen müssen getrennt beurteilt werden | `LightUserResource.mastodonUrl` wurde upstream entfernt und wird vom Status-Kurzmodell nicht benutzt. Das vollständige `User`-/Auth-Modell darf das weiterhin gültige `mastodonUrl` behalten; `mastodon.server` ist ebenfalls vorhanden |
| Stationkennungen | Alte `ibnr`, `rilIdentifier`, `evaIdentifier` wurden vorausgesetzt | `identifiers` mit `de_db_ibnr` und `de_db_ril100`; fehlende Kennungen sind erlaubt |
| Haltstation | Flache Stopover-`id` und `name` wurden als Stationdaten verwendet | `stationId` aus `station.id`, `stationName` aus `station.name`, Kennungen aus `station.identifiers` |
| Haltidentität | Wiederholte Stationen konnten über dieselbe Station-ID zusammenfallen | `matchesStopover`: UUID bevorzugt, sonst gleiche Station-ID und passende geplante Ankunft oder Abfahrt |
| Deduplizierung | Gleiche Planzeiten konnten auch unterschiedliche Stationen zusammenführen | Zusammenführen nur aufeinanderfolgender Halte derselben Station mit gleichen Planzeiten; unterschiedliche vorhandene Stopover-UUIDs bleiben getrennt |
| Haltzeiten | Abhängigkeit von `arrival` und `departure` | `effectiveArrival = arrivalReal ?: arrivalPlanned`, `effectiveDeparture = departureReal ?: departurePlanned` |
| Abfahrtsverspätung | Legacy-Feld `delay` | `delayMinutes` aus `when - plannedWhen`; Planzeit als Anzeige-Rückfall bei fehlender Echtzeit |
| Abfahrtsstation | Die gesuchte Station konnte von der tatsächlichen Abfahrtsstation abweichen | `DepartureTrip.station` ist maßgeblich für den Einstieg, wenn die Abfahrt eine eigene Station liefert |
| Betreiber der Abfahrt | Veraltetes `line.operator` | Entfernt; Betreiber aus `TripDetails.operator` beziehungsweise `CheckinInfo.operator` |
| Operator-ID | `id` als `Int?` konnte eine UUID nicht lesen | `String?` akzeptiert numerische Legacy-IDs und UUIDs; `uuid` ist die bevorzugte stabile Kennung |
| Check-in-Punkte | Veralteter Berechnungsname `bonus` | `calculation.reason`; `points.additional` wird nicht vorausgesetzt |
| HTTP-409-Konflikt | Rohes Fehler-JSON wurde als Fehlermeldung weitergereicht | `data.conflicts` als vollständige Status-Liste und `CheckInConflictException` |
| Zieländerung eines Status | Ein Ziel konnte ohne die zugehörige Planankunft gesendet werden | `destinationId` und `destinationArrivalPlanned` werden zusammen nur bei einem Zielwechsel übertragen; reine Text- und Zeitkorrekturen lassen beide weg |
| Fahrtenkennungen | Trip-UUID und Provider-Identifier waren nicht getrennt modelliert | `TripDetails.uuid`, `TripDetails.tripId`, `CheckinInfo.tripUuid`; Requests behalten den Provider-Identifier |

Die Stopover-Felder `id`, `name` und `identifiers` sind nur bis 30.11.2026 als bisheriger Vertrag angekündigt. Danach soll `id` den konkreten Halt bezeichnen. Routely liest daher niemals eine Station-ID aus Stopover-`id`.

Bei Statusänderungen verlangt der Upstream-Request `destinationId` und `destinationArrivalPlanned` gegenseitig (`required_with`). Die Zielauswahl speichert den konkreten Halt mit Planankunft, statt nur eine Station-ID zu übernehmen.

Für am 30.09.2026 ausgelaufene Felder gibt es keine weitere Kompatibilitätsgarantie, auch wenn einige davon im geprüften Upstream-Quellcode noch vorhanden sind. Die Migration hängt nicht von deren weiterer Ausgabe ab. Für die alten Check-in-Konfliktfelder läuft die angekündigte Garantie am 31.10.2026 aus; diese werden bereits nicht mehr verwendet.

## Abhängigkeiten und Cache

UI und Hintergrunddienst verwenden dieselben Stations-, Zeit- und Identitätshelfer aus `Models.kt`. Manuelle Zeitkorrekturen werden in `arrivalReal` beziehungsweise `departureReal` übernommen; Notification, TTS und Widget erhalten die daraus berechneten Daten.

Eine Room-Schemamigration ist nicht erforderlich. Alte `statusJson`-Cache-Einträge dürfen ihren früheren Namen noch anzeigen (`legacyName` als privater Rückfall), liefern aber keine Station-ID aus dem alten Stopover-`id`. Neue Netzwerkantworten aktualisieren den Cache mit der verschachtelten Stationsstruktur.

## Neue APIs ohne bisherige App-Nutzung

Diese Ergänzungen sind keine Pflichtmigration vorhandener Funktionen. Sie werden im aktuellen `main`-basierten Arbeitsstand nicht verwendet.

| Upstream-Erweiterung | Einordnung für Routely |
| --- | --- |
| Manuelle Fahrten: `POST /trips`, Verwaltung, Kopieren und Stopover-Bearbeitung | Kein manueller Fahrteditor vorhanden; vorhandener Check-in bleibt über `trains/checkin` |
| Tickets und Ticketzuordnung zu Status | Kein Ticketmodul; unbekannte optionale Antwortfelder werden von Gson ignoriert |
| Eventdetails mit `totalDistance` und `totalDuration` | Kein Abruf von `EventDetailsResource`; vorhandener `Status.event` enthält nur Kurzinfos |
| Webhooks mit verschachteltem `client` und `user` | Keine Webhookverwaltung |
| Meldungen: `POST /reports` statt `POST /report` | Keine Meldungsfunktion und kein alter Report-Endpunkt im Client |
| Datenschutzerklärung und Zustimmung | Keine entsprechenden Retrofit-Endpunkte; bei späterem Ausbau den tatsächlichen Vertrag beachten: `GET /privacy-policies/current`, `PUT /privacy-policies/{id}/acceptance` |
| `statistics/overview`, `statistics/history`, `statistics/favorites` | Zusätzliche Statistikangebote; `statistics` bleibt gültig. Die umbenannten Felder `longest_checkin_by_*` und `shortest_checkin_by_*` werden derzeit nicht gelesen |
| UUIDs für Nutzerpfade | Empfehlung für zukünftige Erweiterungen; numerische Nutzer-ID ist laut Changelog nicht deprecated |
| Home-Station löschen, Tagvorschläge, OAuth-Appverwaltung, Block-/Mute-Listen, Passwort-/Profil-/E-Mail-Einstellungen | Keine vorhandenen App-Aufrufe dieser Endpunkte |
| `GET /status` mit Datumsfenster | Nicht verwendet; Feed nutzt `dashboard` und `statuses`, Profile nutzen `user/{username}/statuses` |
| Abfahrts-Metadaten `availableTravelTypes` | Nicht ausgewertet; leere `data`-Listen werden weiterhin als leere Abfahrtsliste behandelt |
| Lokale Stationscodes `local_code` | Autocomplete bleibt serverseitig nutzbar; der generische Kennungstyp erlaubt zusätzliche Typen |
| `continuationTrip` | Optionale Erweiterung laut Changelog; im geprüften `TripResource` nicht enthalten, kein bestehender Consumer |

Der Changelog nennt für die Privacy-Zustimmung einen abweichenden Pfad. Maßgeblich für eine zukünftige Implementierung sind die geprüfte Upstream-Route und deren Requestvertrag, nicht allein dieser Changelog-Eintrag.

Die im separaten Transitous-PR #35 hinzugefügten Kotlin-Dateien wurden zusätzlich auf Träwelling-Verwendung geprüft: Sie verwenden weder dessen API-Service noch die betroffenen Modelle und benötigen deshalb keine eigene Träwelling-Migration.

## Validierung und offene Fragen

- Automatisierte Regressionstests und der Debug-Build sind unter [Tests](../entwicklung/tests.md) und [Build](../entwicklung/build.md) beschrieben.
- TODO: Ergebnis von `./gradlew :app:testDebugUnitTest :app:assembleDebug` für diese Migration nachtragen. Die lokale Ausgangsumgebung hat JDK 17, aber keinen installierten Android-SDK- oder Gradle-Abhängigkeitscache.
- Kein authentifizierter Live-Test gegen das Produktionskonto wurde durchgeführt. Ein Quellcode- und Fixture-Abgleich beweist keine Verfügbarkeit oder Korrektheit einer laufenden Serverinstanz.
- TODO: Regelmäßig neue Changelog-Einträge und den tatsächlich eingesetzten Upstream-Vertrag prüfen; dieser Audit ist eine Momentaufnahme.

## Verwandte Seiten

- [API Überblick](./ueberblick.md)
- [Interne Schnittstellen](./interne-schnittstellen.md)
- [Externe Schnittstellen](./externe-schnittstellen.md)
- [Datenmodell](../daten/datenmodell.md)
- [Migrationen](../daten/migrationen.md)
- [Check-in](../module/checkin.md)
- [StatusDetail](../module/status-detail.md)
- [TripTracking](../module/trip-tracking.md)
- [Tests](../entwicklung/tests.md)
