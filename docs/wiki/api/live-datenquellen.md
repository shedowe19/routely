# Live-Datenquellen und Aktualität

## Zweck

Zusätzliche Bahn-Livedaten für Gleisänderungen, Verspätungen und Haltausfälle ohne Registrierung oder API-Schlüssel. Umsetzung und Quellenprüfung: 10.10.2026, auf Routely `main` bei `8ecd87b1727d7a95d6fb09d40bb8541ece0c4695`.

## Kontext

Träwelling bleibt für Konto, Check-ins, Fahrtidentität und vollständige Haltfolge zuständig. Routely ergänzt aktuelle eindeutig zuordenbare Bahn-Halte über die öffentliche [DBF-Abfahrtstafel](https://dbf.finalrewind.org/). Der Dienst verwendet IRIS und besitzt [offenen Quellcode](https://github.com/derf/db-fakedisplay). Die App benötigt dafür keinen DB-Marketplace-Zugang, API-Key oder eigenen Proxy.

Die ursprüngliche Empfehlung für den offiziellen DB-Timetables-Zugang wurde nach der Nutzerpräzisierung verworfen: Dieser Zugang benötigt Registrierung und Anwendungsschlüssel. RIS::Journeys/Boards benötigen zusätzlich Freigabe und Vertrag. Der anonyme Dienst `v6.db.transport.rest` antwortete im Entwicklungsabruf mit HTTP 503; seine aktuellen DB-Vendo-Daten liefern in Bahnhofstafeln außerdem keine durchgehend belastbare operative Zugnummer für den hier benötigten Abgleich. Diese Dienste werden nicht eingebaut.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/data/dbf/DbfRealtimeRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/model/StopRealtimeInfo.kt`
- `app/src/main/kotlin/de/traewelling/app/data/model/Models.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/TraewellingRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/service/JourneyTimeResolver.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/RealtimePresentation.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/StatusDetailViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`

## Warum eine zusätzliche Quelle?

Träwelling bezieht Fahrplandaten selbst von Transitous. Die am 10.10.2026 untersuchte veröffentlichte Backend-Revision `4d602796da8409017314cc771b1127d169155f02` liest im Stopovers-Endpunkt gespeicherte Werte. Sein minütlicher Scheduler wählt eingecheckte aktive Fahrten; der Default-Mindestabstand für die nächste Transitous-Aktualisierung ist zwei Minuten. Eine HTTP-Anfrage an Träwelling erzwingt keine neue Providerabfrage. Eingesetzte Produktionsrevision, Konfiguration und Queue-Laufzeiten wurden nicht beobachtet; die Codekonfiguration ist kein gemessenes Produktionsintervall.

Ein anderer Zugang zu demselben Transitous-Feed erweitert dessen Echtzeitabdeckung nicht. DBF ergänzt dagegen eine IRIS-Bahnhofstafel. Der Entwicklungsabruf am 10.10.2026 lieferte anonym HTTP 200 für Hannover Hbf mit Minutenverspätungen und abweichendem geplantem/aktuellem Gleis. Dies belegt die Erreichbarkeit und die konkreten gelieferten Felder zu diesem Zeitpunkt, keine garantierte höhere Aktualität jeder Fahrt.

## Vertrag und Abgleich

```text
GET https://dbf.finalrewind.org/<EVA>.json?version=3&no_related=1&past=1
```

Die dokumentierte JSON-Version 3 ist stabil. `no_related=1` ist zwingend: Ohne diese Einschränkung kann DBF Halte verwandter Bahnhöfe aufnehmen, während die JSON-Zeile selbst keine Stationskennung enthält. Die EVA stammt aus `station.identifiers.de_db_ibnr`; interne Träwelling-Stations-IDs sind keine EVA.

`past=1` nimmt auch die vergangene Stunde auf; der Standard-Vorblick beträgt 180 Minuten. Bei verspäteten Fahrten wird der Fensterabgleich am belegten verzögerten Ereignis geprüft, sodass eine vor mehr als 20 Minuten geplante, jetzt aktuelle Fahrt nicht allein wegen ihrer Verspätung entfällt. DBF hält IRIS-Echtzeit laut geprüftem Quellcode selbst etwa 70 Sekunden im Cache. Der zusätzliche Clientcache kann deshalb keinen sekundengenauen Providerstand garantieren.

Die Zuordnung verwendet positive operative `CheckinInfo.journeyNumber`, EVA und vorhandene Ankunfts-/Abfahrts-Sollzeiten. Linienbezeichnungen wie `RE1`, Namen oder Koordinaten ersetzen diese Identitäten nicht. Nur eindeutige Treffer aus dem aktuellen Bahnhofstafel-Zeitfenster werden übernommen. Die API liefert Sollzeiten lediglich als `HH:mm`: Das Fahrtdatum stammt deshalb aus dem vorhandenen konkreten App-Ereignis und wird gegen den Abrufhorizont geprüft. Mehrdeutige Tage, widersprüchliche Zeiten und nicht eindeutig auflösbare Sommerzeitstunden bleiben ohne Zusatzdaten.

Die Tafel enthält nullable Minutenverspätungen, `platform`, `scheduledPlatform`, `missingRealtime` und ein zusammengefasstes `isCancelled`. Fehlende Daten bleiben unbekannt. Ein synthetischer Nullverspätungswert beziehungsweise ein mit dem Plan identisches Gleis belegt nicht selbstständig neue Echtzeit. Version 3 liefert keine getrennten Ankunfts-/Abfahrtsausfälle; daraus wird kein erfundenes Teilausfallmodell erzeugt. Ein bestätigter kompletter Haltausfall kann ergänzt werden; ein bloßes `isCancelled=false` beweist keine vollständige Rücknahme eines möglichen Teilausfalls.

## Verhalten und Grenzen

- Die Quelle ist standardmäßig aktiv und über **Zusätzliche Bahn-Livedaten** abschaltbar. Es wird ausschließlich die benötigte öffentliche Bahnhofstafel angefragt; Bearer-Token, Kontoinformationen und Gerätepositionen gehen nicht an DBF.
- Der Prozess teilt Cache und laufende Abrufe zwischen Detailansicht und Begleitungsservice. Höchstens zehn Request-Starts in 60 Sekunden und einer je EVA in 60 Sekunden entsprechen der aktuellen Dienstvorgabe; auch fehlgeschlagene Starts zählen. Die Quote gilt je App-Prozess, keine flottenweite Kapazitätszusage.
- Erfolgreiche Tafeln werden mindestens 60 Sekunden wiederverwendet. Fehlversuche und `Retry-After` begrenzen erneute Anfragen. Wenige zeitnahe Bahnhöfe und eine kurze Gesamtfrist begrenzen den Zusatzabruf; historische Fahrten, andere Verkehrsmittel, fehlende Kennungen und uneindeutige Treffer behalten Träwelling-Werte.
- Zeiten, Gleise und Ausfälle erhalten getrennte Quell-/Abrufinformationen, ausschließlich wenn das jeweilige Feld übernommen wurde. Ein erfolgreicher HTTP-Abruf ohne passenden Wert erneuert keine beibehaltenen Fremdwerte.
- Zusatzdaten werden nur lokal angezeigt und für die bestehende Begleitung verwendet. Sie lösen keinen Check-in-PUT aus. Manuelle Korrekturen und lokale GPS-Zeiten behalten ihre bisherige Priorität.
- Der Änderungsmonitor behandelt einen bekannten Wechsel der jeweiligen Zeit-/Gleisquelle als neue Vergleichsbasis. Der Rückfall auf einen anders bezogenen Wert erzeugt keine scheinbare Verspätungsverbesserung, Gleisänderung oder Ausfallrücknahme. Neu bestätigte komplette Ausfälle bleiben meldbar.
- Session-, Fahrt- und Statusrevision werden auch nach dem optionalen Zusatzabruf geprüft. Verspätete Antworten dürfen keinen neuen Check-in oder eine andere Sitzung überschreiben.

## Anzeige der Aktualität

`StopRealtimeInfo.fetchedAtMillis` beschreibt den erfolgreichen Clientabruf, nicht den Zeitpunkt einer Änderung bei DB oder Transitous. `providerUpdatedAtMillis` bleibt ohne belastbaren Beleg leer; ein Störungsmeldungs-Zeitstempel ist keine Prognosezeit.

Die Detailansicht zeigt Quelle und Abrufalter sowie Fehler beziehungsweise ältere Abrufe. Das frühere tagesabhängige grüne `LIVE`-Badge ist ersetzt. `lastUpdated` wird nur nach erfolgreichem Halteabruf erneuert. Ein Gleisvergleich zeigt beispielsweise `Gleis 7 statt 5`; Herkunft und Alter von Gleisen sind von Zeitwerten getrennt. GPS- und manuelle Zeiten behalten ihre eigene Quellenbezeichnung. Alte Fahrtcache-Einträge ohne Abrufmetadaten gelten als unbekannt, statt beim Neustart als frisch gestempelt zu werden.

## Abhängigkeiten

Vorhandenes OkHttp, Gson und Coroutines; keine zusätzliche Android-Bibliothek, kein DB-Zugangsspeicher und kein mitgelieferter Proxy. DBF ist ein öffentlich betriebener Dienst mit begrenzter Kapazität; sein Quellcode ermöglicht einen späteren eigenen Betrieb. Die Softwarelizenz beschreibt den Dienstcode und ist kein pauschaler Lizenznachweis für alle zugrunde liegenden Bahndaten.

## Validierung

Der [CI-Lauf 38072554976](https://github.com/shedowe19/routely/actions/runs/38072554976) auf Codecommit `9340072f935c3282d77655ae8635bcd087a3042d` besteht: **905 Tests in 71 Klassen**, keine Fehler, Fehlschläge oder übersprungenen Tests; vollständiges Debug-Lint **0 Fehler / 54 Warnungen**, Debug-APK und unsignierte Release-APK einschließlich Release-Vital-Lint. Alle 108 ausführbaren Gradle-Tasks liefen in 3 Minuten 42 Sekunden. Die unveränderten Python-Releaseguards bestanden mit 56 Tests. JUnit- und Lint-Artefakte wurden unabhängig ausgewertet. Es wurde kein signierter Release veröffentlicht oder physisches Android-Gerät geprüft.

Die 66 neuen Regressionen betreffen JSON-Vertrag und echte öffentliche Fixture, Zug-/Besuchsabgleich einschließlich verspäteter Mitternachtsfahrten, Cache/Quoten/HTTP-Grenzen, getrennte Feldherkunft, Sitzung und konkurrierende Statuskorrektur sowie Quellenwechsel im Änderungsmonitor. Details und Prüfbefehle stehen unter [Tests](../entwicklung/tests.md).

## Offene Fragen

- TODO: Neue Quelle auf echten Fahrten mit Gleiswechsel, Ausfall, Mitternacht und Rücknahmen mit Träwelling vergleichen. Eine einzelne erfolgreiche Tafel beweist weder Vollständigkeit noch einen festen Aktualitätsvorsprung.
- TODO: Abdeckung und Belastung des öffentlichen Dienstes bei wachsender Zahl von Installationen bewerten; bei Bedarf die quelloffene Software selbst betreiben.
- Unklar: Tatsächliches Produktionsalter der Träwelling-/IRIS-Prognosen; Client-Abrufzeit ist kein Provider-Aktualitätsbeleg.
- TODO: Möglichen Datenverlust in der vorhandenen Stopover-Duplikatauswahl gesondert prüfen; aktuell bewertet sie nicht alle Echtzeitfelder.

## Quellen

- [DBF: JSON-Version 3 und aktuelle Nutzungsgrenzen](https://dbf.finalrewind.org/)
- [DBF: Quellcode und Lizenz](https://github.com/derf/db-fakedisplay)
- [DBF: JSON-Controller zum Prüfstand](https://github.com/derf/db-fakedisplay/blob/7e23707ac622dca704dc78523fc3683ba5ccebae/lib/DBInfoscreen/Controller/Stationboard.pm)
- [Träwelling: Transitous](https://help.traewelling.de/features/timetable/transitous/)
- [Träwelling: Refresh-Auswahl zum Prüfstand](https://github.com/Traewelling/traewelling/blob/4d602796da8409017314cc771b1127d169155f02/app/DataProviders/Repositories/TripRepository.php)
- [Träwelling: Refresh-Default](https://github.com/Traewelling/traewelling/blob/4d602796da8409017314cc771b1127d169155f02/config/trwl.php)
- [DB Timetables: Nutzungsplan](https://developers.deutschebahn.com/db-api-marketplace/apis/product/timetables)
- [DB-Vendo-Client: aktueller Funktionsvertrag und Grenzen](https://github.com/public-transport/db-vendo-client)

## Verwandte Seiten

- [Externe Schnittstellen](./externe-schnittstellen.md)
- [API-Kompatibilität](./traewelling-kompatibilitaet.md)
- [TripTracking](../module/trip-tracking.md)
- [StatusDetail](../module/status-detail.md)
- [Fahrtänderungen](../module/trip-changes.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Tests](../entwicklung/tests.md)
- [Offene Fragen](../offene-fragen.md)
