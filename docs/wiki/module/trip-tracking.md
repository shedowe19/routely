# Modul: TripTrackingService

## Zweck

Der Android-Foreground-Service verfolgt die eingecheckte Haltfolge mit GPS und meldet den nächsten Halt per Notification, Widget und optionaler Sprachausgabe. Bei fehlendem brauchbarem Standort nutzt er gekennzeichnete Fahrplanangaben. Dies ist ein Stationsalarm, keine Turn-by-Turn-Streckenführung.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/service/StationTrackingEngine.kt`
- `app/src/main/kotlin/de/traewelling/app/MainActivity.kt`
- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/de/traewelling/app/widget/TripWidgetProvider.kt`

## Start und Standortfreigabe

`CheckInViewModel` speichert nach einem erfolgreichen Check-in nur die aktive Status-ID. `MainActivity` beobachtet diese und die GPS-Einstellung im Zustand `RESUMED`; der Service wird aus der sichtbaren Activity gestartet. Präzise Standortfreigabe und aktivierte Ortungsdienste bestimmen, ob GPS aktiviert werden darf. Ohne diese Voraussetzungen startet der Fahrplanmodus. Automatische Standortanfragen werden je aktiver Fahrt begrenzt; der manuelle Einstellungsbutton kann bei dauerhafter Ablehnung die App-Berechtigungen öffnen.

Das Manifest deklariert `location|dataSync` sowie `FOREGROUND_SERVICE_LOCATION`. Der Service aktiviert bei GPS den Typ `location`, sonst `dataSync`. Bei einem `START_STICKY`-Neustart ohne Start-Intent wird GPS nicht eigenständig wieder aktiviert: Standortzugriff bleibt aus, bis eine sichtbare Activity ihn erneut geprüft hat. Dies ist ein vorübergehender Ausfall, kein bewusst gewählter Zeitmodus; ein bereits per GPS etablierter Besuch bleibt geschützt. Ein korrekt gestarteter Location-Foreground-Service kann auch bei ausgeschaltetem Display Updates erhalten; die tatsächliche Zustellung bleibt geräteabhängig.

## Datenfluss und Intervalle

| Verarbeitung | Gewünschtes Intervall |
| --- | --- |
| Status und Stopovers über Träwelling aktualisieren | 60 Sekunden |
| Fahrplan-/Signalalter prüfen | 10 Sekunden |
| Standortupdates unterwegs | 12 Sekunden |
| Standortupdates bis 3 km zum aktuellen Halt | 3 Sekunden |

Die LocationRequest-Mindestintervalle liegen bei 5 beziehungsweise 2 Sekunden; Android behandelt Anfragen als Best-Effort-Vorgaben. Die GPS-Auswertung läuft unabhängig vom API-Polling.

Das Repository liefert Status und Stopovers. `checkedInRoute` grenzt die Route anhand von `matchesStopover` auf Einstieg bis Ziel ein; nicht auflösbare Grenzen ersetzen keine gültige Route. Manuelle Check-in-Zeiten werden in Echtzeitfelder übernommen. `TrackingStop` enthält Name, Koordinaten und Plan-/Echtzeit der Station sowie einen Besuchsschlüssel: bevorzugt Stopover-UUID, sonst Station-ID, Planzeiten und Routenindex.

`StationTrackingEngine` ist reine Kotlin-Logik ohne Android- oder Netzwerkzugriffe. Sie prüft die geordnete Haltfolge und bewahrt den aktuellen Besuch bei API-Aktualisierungen über seinen Schlüssel. Es wird nicht beliebig der global nächstgelegene Bahnhof ausgewählt. Ein noch rein zeitbasierter Cursor bleibt vorläufig: Der erste brauchbare GPS-Fix kann ihn bei einem eindeutigen nahen Halt räumlich neu verankern, auch bei großer Verspätung. Mehrdeutige Stationsbesuche werden nicht beliebig ausgewählt.

Ein erster Fix fern aller Stationen macht einen bereits zeitbasiert vorgerückten Cursor nicht zu einer bestätigten GPS-Zuordnung. Er bleibt nach Cache-Restaurierung korrigierbar und wird bis zur räumlichen Bestätigung als `Fahrplan · ungefähr` angezeigt. Ein späterer eindeutiger stationsnaher Fix kann ihn zum passenden Besuch zurückführen.

## GPS-Trigger und Fortschritt

Ein brauchbarer Fix hat gültige Koordinaten, höchstens 100 Meter gemeldete Ungenauigkeit und ist höchstens 30 Sekunden alt. Ungültige, alte oder bereits verarbeitete Zeitstempel bestätigen keine neue Annäherung. Nach einer längeren Signallücke muss ein neuer Annäherungstrend entstehen.

Die Entfernung zum aktuellen Halt muss erkennbar sinken, bevor der Ansageradius einen Trigger erzeugt. Der Eintritt in diesen Radius erledigt den Halt nicht. Zwischenankunft erfordert `Entfernung + Ungenauigkeit <= 120 m` und einen bestätigten Annäherungstrend oder zwei frische innere Fixes; der Einstieg kann schon im inneren Bereich als erreicht gelten.

Nach innerer Ankunft wird erst beim anschließenden Entfernen über 220 Meter mit Hysterese zum nächsten Besuch gewechselt. Für Zwischenhalte gibt es zusätzlich eine konservative Vorbeifahrt-Erkennung anhand Annäherung, minimaler Distanz und anschließendem Entfernen. Wiederholte Besuche derselben Station bleiben durch ihre Besuchsschlüssel getrennt; ausgefallene Halte werden übersprungen.

Am Ziel gilt ein eigener Bereich von 300 Metern einschließlich Ungenauigkeit. Ankunft benötigt zwei frische innere Fixes und eine aktuell gemeldete Geschwindigkeit höchstens 3 m/s oder mindestens zehn Sekunden stabilen Aufenthalt um einen Anker von etwa 20–30 Metern. Ein bestätigter stationärer Aufenthalt benötigt keinen vorherigen Annäherungstrend. Schnelle Vorbeifahrt, ein erster Fix nahe am Ziel und vergangene Planzeit allein beenden die Fahrt nicht. Auch diese räumliche Heuristik beweist keinen tatsächlichen Fahrzeughalt.

Beim späten Trackingstart können zwei frische Bewegungsfixes die Abfahrt vom Ursprung herleiten, wenn sie im plausiblen Korridor weg vom Ursprung und auf den nächsten Halt zeigen. Nach einer Signallücke kann ein bereits angenäherter Zwischenhalt ähnlich wieder eingeordnet werden. Das Ziel wird durch diese Abfahrts-/Vorbeifahrtlogik nicht übersprungen.

### Ansageradius

Standard ist `clamp(geglättete Geschwindigkeit in m/s * 45, 300, 2000)` Meter. Die Geschwindigkeit wird aus dem Standortwert oder aufeinanderfolgenden Positionen ermittelt und geglättet. Ohne verwertbare Geschwindigkeit gilt der Mindestradius. Die Einstellungen erlauben alternativ feste 300, 500, 1.000 oder 2.000 Meter.

| Gleichmäßige Geschwindigkeit | Automatischer Radius |
| --- | --- |
| 30 km/h | 375 m |
| 80 km/h | 1.000 m |
| 160 km/h | 2.000 m |

Gemessen wird Luftlinie; die Formel garantiert keine 45-Sekunden-Ankunftsprognose. Kurze Stationsabstände, Kurven und Schleifen müssen bei echten Fahrten geprüft werden.

## Fahrplan-Rückfall

Ohne brauchbaren Standort oder ohne Koordinaten des aktuellen Halts verwendet die Engine Echtzeit, sonst Planzeit. Zeitbasierte Ansagen liegen innerhalb der nächsten **180 Sekunden**; die frühere Rundung auf ganze Minuten wird nicht mehr verwendet.

Vor dem ersten zuverlässigen Fix können vergangene Zwischenhalte anhand ihrer Zeit übersprungen werden. `gpsEstablished` hält fest, ob der Cursor bereits per GPS etabliert wurde; reine Zeitfortschritte bleiben nach einem Neustart räumlich korrigierbar. Ein etablierter Halt mit Koordinaten bleibt bei Signalausfall, Berechtigungsverlust und ungeprüftem Sticky-Neustart erhalten. Fehlende Koordinaten erlauben weiterhin zeitbasierten Fortschritt.

Bewusstes Ausschalten von `GPS verwenden` aktiviert dagegen den Zeitmodus und erlaubt dessen Fortschritt. Ein temporärer Standortausfall wird damit nicht gleichgesetzt. Beim erneuten Aktivieren kann GPS den vorläufigen Zeitcursor wieder verankern.

Der Fahrplan-Rückfall bestätigt niemals die Zielankunft und beendet die Fahrt nicht automatisch. Bei dauerhaft fehlendem GPS muss der Nutzer die Fahrt über `Beenden` abschließen. Notification und Widget zeigen `Fahrplan · ungefähr`; bei vergangener/fehlender Zielzeit oder fehlendem aktuellen Halt zusätzlich `Fahrt manuell beenden`. Die TTS-Ansage beginnt mit `Voraussichtlich`.

## Persistenz und Offlinebetrieb

`trip_tracking_state` speichert ein versioniertes JSON mit Status-ID, Check-in, zuletzt gültiger eingegrenzter Haltfolge und `TrackingProgress` (Cursor, Besuchsschlüssel, innerer Ankunftsstatus, `gpsEstablished`, erfolgreich eingereihte Ansageschlüssel und Abschlussstatus). Standortfixes und Bewegungshistorie bleiben ausschließlich im Speicher; es wird keine GPS-Historie an Träwelling gesendet.

Nach mindestens einem erfolgreichen Laden kann diese Route bei API-Ausfällen und nach Service-Neustart wiederverwendet werden. Ohne gültigen Cache und ohne erfolgreiche API-Antwort existiert keine auswertbare Haltfolge. Fortschritt wird nur für die noch aktive Status-ID gespeichert; Fahrtwechsel, Logout und bestätigtes Beenden entfernen den zugehörigen Cache.

## TTS, Notification und Widget

TTS benötigt die separate Option `Haltestellen ansagen` und Audiofokus. Sprache und Stimme kommen aus den Einstellungen. Ein Ansageschlüssel wird nur nach erfolgreichem Einreihen mit `TextToSpeech.SUCCESS` dauerhaft bestätigt. Bei ausgeschalteter/nicht bereiter TTS, verweigertem Audiofokus oder fehlgeschlagenem Einreihen wird er für erneuten Versuch freigegeben. Eine während der Initialisierung wartende Ansage wird nur abgespielt, wenn ihr Besuch noch aktuell ist.

Bei Zielankunft wartet der Service auf eine bereits laufende oder gerade eingereihte Zielansage. Erst deren Abschluss, Fehler-/Stop-Callback oder spätestens ein 15-Sekunden-Timeout beendet den Service; die eigene Zielansage wird nicht sofort durch `stopTracking` abgeschnitten.

Notification und Widget erhalten Linie, nächsten Halt, Ziel, Zeit, Gleis und positive Verspätung. Der Quellenhinweis `GPS` beziehungsweise `Fahrplan · ungefähr` steht auch im Widget-Namen des nächsten Halts. GPS-Annäherung ersetzt die zeitlichen Angaben für Ankunft und Gleis nicht.

## Stopp-Bedingungen

- GPS-Zielkriterium mit innerem Aufenthalt/niedriger Geschwindigkeit; gegebenenfalls erst nach Abschluss der Zielansage
- Manuell über die Notification-Aktion `Beenden`
- Aktive Fahrt wird ersetzt oder entfernt, zum Beispiel beim Logout

Beim Beenden werden Location-Callbacks, Polling und TTS gestoppt. Aktive Status-ID und Cache werden gemeinsam und nur für die passende Fahrt gelöscht.

## Validierung und offene Fragen

- Der erste vollständige GPS-Prüflauf mit 62 Tests, Android-Debug-Build und APK-Upload war erfolgreich. Die aktuelle Suite umfasst zusätzlich die 35. GPS-Regression; historische Nachweise und aktuelle PR-Checks stehen unter [Tests](../entwicklung/tests.md).
- TODO: Echte Zug-/Busfahrten mit Tunnel, nahen Stationen, Vorbeifahrt, Rundfahrten, grober Standortfreigabe, ausgeschaltetem Display und Neustart validieren. Hier steht kein physisches Testgerät zur Verfügung.
- TODO: Radius- und Hysteresewerte nach diesen Fahrten bewerten; reine Nähe ist kein Nachweis eines Fahrzeughalts.

## Offizielle Quellen

- [Foreground-Service-Typen](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Standortberechtigungen und Display-aus-Betrieb](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [Geofencing: mögliche Hintergrundverzögerung](https://developer.android.com/develop/sensors-and-location/location/geofencing)
- [LocationRequest: Best-Effort-Vorgaben](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest)

## Verwandte Seiten

- [Check-in](./checkin.md)
- [Settings](./settings.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Widget](./widget.md)
- [Datenmodell](../daten/datenmodell.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Externe Abhängigkeiten](../architektur/externe-abhaengigkeiten.md)
- [Tests](../entwicklung/tests.md)
- [Offene Fragen](../offene-fragen.md)
