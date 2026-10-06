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

Am 06.10.2026 wurden Changelog, Routen, Stopover-Modell/-Resource sowie Status-, Statistik- und Dashboard-Controller auf `develop` erneut als Primärquellen gelesen. Dieser Abruf lieferte keinen sicheren neuen Commit-SHA; er wird deshalb nicht mit dem gepinnten Prüfstand vom 05.10. gleichgesetzt. Die erneute Prüfung bestätigt weiterhin die verwendeten Routen. Das Dashboard nutzt numerische Pagination mit 15 Einträgen, `statuses` ist unpaginiert, und Gleisstrings werden ohne unbelegte Präfixkürzung übernommen. Die späteren [Auth-/Cache-Korrekturen](../module/auth.md) ändern keine Träwelling-Endpunkte.

Bei der anschließenden Streckenverlauf-Recherche am 06.10. bestätigte ein GitHub-Commitvergleich `develop` als identisch mit `4d602796da8409017314cc771b1127d169155f02`. Zusätzlich wurden `polyline/{parameters}`, der Status-/Location-Controller und die GeoJSON-DTOs gegen diesen gepinnten Stand geprüft. Dieser spätere Nachweis ergänzt den vorherigen Abruf ohne SHA. Der native [Polyline-Vertrag](./externe-schnittstellen.md) enthält mögliche Stationssehnen und darf deshalb keine ungeprüfte amtliche Gleisführung versprechen.

Beim Nachreview von Routely-Main `f406bad` am 06.10.2026 wurde der aktuelle Upstream-Branch erneut ausdrücklich als `4d602796da8409017314cc771b1127d169155f02` gelesen. Der vollständig geprüfte Changelog hat Blob-SHA `151ba6f1c9e012f9a665eef2f733ee1114d73015`; verwendete Retrofitpfade und Antwortressourcen wurden mit diesem aktuellen Stand verglichen. Kein neuer inkompatibler Consumervertrag wurde gefunden. Der optionale Visibility-PUT-Vertrag und der vollständige Status-Antwortsnapshot belegen jedoch zwei Client-Reihenfolgefehler D4/D8 im [Nachreview](../entwicklung/main-review-2026-10-06.md), keine neue Upstream-Endpunktänderung.

Die Korrektur des Nachreviews lässt unveränderte Text-/Sichtbarkeitsfelder im PUT aus und ordnet konkurrierende Statusschreibaufträge pro Credential/Status. Diese Clientkorrekturen verändern keine Upstream-Endpunkte. Die erneute Quellprüfung während der Umsetzung bestätigt weiterhin denselben Upstream-SHA; keine schreibende Live-API-Prüfung wurde ausgeführt.

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
| Authentifizierung | `GET auth/user`, `POST auth/logout` | Weiterhin gültig; erreichbarer Login per validiertem manuellem Token, OAuth-Helfer noch nicht angebunden |
| Feeds | `GET dashboard`, `GET statuses` | Weiterhin gültig; Status enthält `user` und `checkin` |
| Einzelstatus | `GET/PUT/DELETE status/{id}`, `POST/DELETE status/{id}/like` | Weiterhin gültig; Status-ID bleibt numerisch |
| Stationssuche | `GET trains/station/autocomplete/{query}`, `GET stations`; zusätzlich deklarierter `GET trains/station/nearby` | Weiterhin gültig; Standortsuche im Check-in und in der Fahrterkennung verwendet `stations` mit Bounding-Box; Stationskennungen in `identifiers` |
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
| Status | `checkin` und `user` waren bereits korrekt | Keine Abhängigkeit von den laut Changelog am 27.09.2026 in Upstream entfernten `train` und `userDetails`; die am 05.10.2026 geprüfte Produktion lieferte beide noch |
| Mastodon | Gleichnamige Felder in verschiedenen Ressourcen müssen getrennt beurteilt werden | `LightUserResource.mastodonUrl` wurde upstream entfernt und wird vom Status-Kurzmodell nicht benutzt. Das vollständige `User`-/Auth-Modell darf das weiterhin gültige `mastodonUrl` behalten; Light-User-Antworten unterstützen `mastodon.server` |
| Stationkennungen | Alte `ibnr`, `rilIdentifier`, `evaIdentifier` wurden vorausgesetzt | `identifiers` mit `de_db_ibnr` und `de_db_ril100`; fehlende Kennungen sind erlaubt |
| Haltstation | Flache Stopover-`id` und `name` wurden als Stationdaten verwendet | `stationId` aus `station.id`, `stationName` aus `station.name`, Kennungen aus `station.identifiers` |
| Haltidentität | Wiederholte Stationen konnten über dieselbe Station-ID zusammenfallen | `matchesStopover`: UUID bevorzugt, sonst gleiche Station-ID und passende geplante Ankunft oder Abfahrt |
| Deduplizierung | Gleiche Planzeiten konnten auch unterschiedliche Stationen zusammenführen | Zusammenführen nur aufeinanderfolgender Halte derselben Station mit gleichen Planzeiten; unterschiedliche vorhandene Stopover-UUIDs bleiben getrennt |
| Haltzeiten | Abhängigkeit von `arrival` und `departure` | `effectiveArrival = arrivalReal ?: arrivalPlanned`, `effectiveDeparture = departureReal ?: departurePlanned` |
| Abfahrtsverspätung | Legacy-Feld `delay` | `delayMinutes` aus `when - plannedWhen`; Planzeit als Anzeige-Rückfall bei fehlender Echtzeit |
| Abfahrtsstation | Die gesuchte Station konnte von der tatsächlichen Abfahrtsstation abweichen | `DepartureTrip.station` ist maßgeblich für den Einstieg, wenn die Abfahrt eine eigene Station liefert |
| Betreiber der Abfahrt | Veraltetes `line.operator` | Entfernt; Betreiber aus `TripDetails.operator` beziehungsweise `CheckinInfo.operator` |
| Operator-ID | `id` als `Int?` konnte eine UUID nicht lesen | `String?` akzeptiert numerische Legacy-IDs und UUIDs; `uuid` ist die bevorzugte stabile Kennung |
| Check-in-Punkte | Nicht zum geprüften Vertrag passendes Berechnungsfeld `bonus` | `calculation.reason`; `points.additional` wird nicht vorausgesetzt |
| HTTP-409-Konflikt | Rohes Fehler-JSON wurde als Fehlermeldung weitergereicht | `data.conflicts` als vollständige Status-Liste und `CheckInConflictException` |
| Zieländerung eines Status | Ein Ziel konnte ohne die zugehörige Planankunft gesendet werden | `destinationId` und `destinationArrivalPlanned` werden zusammen nur bei einem Zielwechsel übertragen; reine Text- und Zeitkorrekturen lassen beide weg |
| Fahrtenkennungen | Trip-UUID und Provider-Identifier waren nicht getrennt modelliert | `TripDetails.uuid`, `TripDetails.tripId`, `CheckinInfo.tripUuid`; Requests behalten den Provider-Identifier |

Die Stopover-Felder `id`, `name` und `identifiers` sind nur bis 30.11.2026 als bisheriger Vertrag angekündigt. Danach soll `id` den konkreten Halt bezeichnen. Routely liest daher niemals eine Station-ID aus Stopover-`id`.

Bei Statusänderungen verlangt der Upstream-Request `destinationId` und `destinationArrivalPlanned` gegenseitig (`required_with`). Die Zielauswahl speichert den konkreten Halt mit Planankunft, statt nur eine Station-ID zu übernehmen.

Für am 30.09.2026 ausgelaufene Felder gibt es keine weitere Kompatibilitätsgarantie, auch wenn einige davon im geprüften Upstream-Quellcode noch vorhanden sind. Die Migration hängt nicht von deren weiterer Ausgabe ab. Für die alten Check-in-Konfliktfelder läuft die angekündigte Garantie am 31.10.2026 aus; diese werden bereits nicht mehr verwendet.

## Abhängigkeiten und Cache

UI und Hintergrunddienst verwenden dieselben Stations- und Identitätshelfer aus `Models.kt`. Der Tracking-Service berücksichtigt manuelle Zeiten in seiner internen Route. Die aktuelle aktive Reiseanzeige löst Zeiten zusätzlich über `JourneyTimeResolver` auf: lokale GPS-Zeit, manuelle Zeit, parsebare API-Echtzeit, Plan. Das Fahrtdetail hält Providerwerte und manuelle Zeiten getrennt; GPS-Prognosen ersetzen keine API-Felder und erfordern keine neue API. Details: [GPS-Zeiten](../module/gps-zeiten.md).

Die API-Modellmigration vom 05.10. erforderte keine Room-Schemaänderung. Alte `statusJson`-Cache-Einträge dürfen ihren früheren Namen noch anzeigen (`legacyName` als privater Rückfall), liefern aber keine Station-ID aus dem alten Stopover-`id`. Die getrennte Feedcache-Korrektur vom 06.10. hebt Room dagegen auf Version 2 und baut dessen Cache neu auf; sie ist unter [Migrationen](../daten/migrationen.md) dokumentiert. Neue Netzwerkantworten verwenden die verschachtelte Stationsstruktur.

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
- GitHub Actions hat am 05.10.2026 für Commit `4ed79c781e5ca38005885fb585277fee56c1cfa4` alle 28 Unit-Tests und den vollständigen Debug-Build erfolgreich ausgeführt (`./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace`). JSON-Fixtures, Wiki-Dateilinks und `git diff --check` sind ebenfalls geprüft.
- Am 05.10.2026 wurden außerdem 16 authentifizierte GET-Anfragen gegen `https://traewelling.de` geprüft: alle HTTP 200, alle geprüften Antwortstrukturen passend. Verschachtelte Stationen, Stopover-UUIDs, Plan-/Echtzeit und die Stopovers-Map wurden bestätigt; Einstieg und Ziel eines Status waren über dieselben UUIDs in der Haltliste auffindbar. Die vollständige Endpunktliste steht unter [Tests](../entwicklung/tests.md).
- Die geprüfte Produktion lieferte noch deprecated Felder und numerische Operator-IDs neben UUIDs. Der Live-Server entspricht daher nicht vollständig dem geprüften `develop`-Stand; die Migration stützt sich weiterhin auf die neuen Felder.
- Die Live-Prüfung war ausschließlich lesend. Check-in 201/409 und Status-PUT sind durch Quellvertrag und Fixtures abgesichert, wurden aber nicht live ausgelöst. Tokens und persönliche Antwortdaten wurden nicht ins Repository oder Wiki übernommen.
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
- [GPS-Zeiten](../module/gps-zeiten.md)
- [Tests](../entwicklung/tests.md)
