# Modul: TripTrackingService

## Zweck

Der Android-Foreground-Service verfolgt die eingecheckte Haltfolge mit GPS und meldet den nächsten Halt per Notification, Widget und optionaler Sprachausgabe. Er vergleicht frische API-Daten für Änderungshinweise, berechnet lokale GPS-Zeitprognosen und liefert das gemeinsame Haltemodell der Fortschrittsbenachrichtigung. Bei fehlendem brauchbarem Standort nutzt er gekennzeichnete API-/Fahrplanangaben. Dies ist ein Stationsalarm, keine Turn-by-Turn-Streckenführung.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/service/StationTrackingEngine.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingLiveState.kt`
- `app/src/main/kotlin/de/traewelling/app/service/GpsJourneyTimeEstimator.kt`
- `app/src/main/kotlin/de/traewelling/app/service/JourneyTimeResolver.kt`
- `app/src/main/kotlin/de/traewelling/app/service/SpeechDeliveryQueue.kt`
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

Standort-Batches werden vollständig in zeitlicher Reihenfolge verarbeitet. Ein gemeinsamer Mutex schützt Engine-Mutation und Übernahme des Ergebnisses einschließlich Notification, UI-Fortschritt, TTS und Persistenz gegenüber Tick, Routen- und Einstellungsänderungen. Netzwerkzugriffe liegen außerhalb dieser Sperre.

Das Repository liefert Status und Stopovers. `checkedInRoute` grenzt die Route anhand von `matchesStopover` auf Einstieg bis Ziel ein; nicht auflösbare Grenzen ersetzen keine gültige Route. Manuelle Check-in-Zeiten werden in Echtzeitfelder übernommen. `TrackingStop` enthält Name, Koordinaten und Plan-/Echtzeit der Station sowie einen Besuchsschlüssel: bevorzugt Stopover-UUID, sonst Station-ID, Planzeiten und Routenindex.

`StationTrackingEngine` ist reine Kotlin-Logik ohne Android- oder Netzwerkzugriffe. Sie prüft die geordnete Haltfolge und bewahrt den aktuellen Besuch bei API-Aktualisierungen über seinen Schlüssel. Es wird nicht beliebig der global nächstgelegene Bahnhof ausgewählt. Ein noch rein zeitbasierter Cursor bleibt vorläufig: Der erste brauchbare GPS-Fix kann ihn bei einem eindeutigen nahen Halt räumlich neu verankern, auch bei großer Verspätung. Mehrdeutige Stationsbesuche werden nicht beliebig ausgewählt.

Ein erster Fix fern aller Stationen macht einen bereits zeitbasiert vorgerückten Cursor nicht zu einer bestätigten GPS-Zuordnung. Er bleibt nach Cache-Restaurierung korrigierbar und wird bis zur räumlichen Bestätigung als `Fahrplan · ungefähr` angezeigt. Ein späterer eindeutiger stationsnaher Fix kann ihn zum passenden Besuch zurückführen.

`trackingLiveState` veröffentlicht Cursor, Besuchsschlüssel, passenden Halt, Ankunfts-/Abschlussstatus, Fortschrittsquelle und optional `gpsTimes` als prozesslokalen `StateFlow`. Der Zustand wird beim Fahrtwechsel und Service-Ende entfernt. Er enthält keine Geräteposition und wird nicht in einem neuen DataStore-Key gespeichert. Die [Status-Detail-Timeline](./status-detail.md) übernimmt ihn nur für die eigene, angezeigte aktive Fahrt.

## GPS-Trigger und Fortschritt

Ein brauchbarer Fix hat gültige Koordinaten, höchstens 100 Meter gemeldete Ungenauigkeit und ist höchstens 30 Sekunden alt. Ungültige, alte oder bereits verarbeitete Zeitstempel bestätigen keine neue Annäherung. Nach einer längeren Signallücke muss ein neuer Annäherungstrend entstehen.

Die Entfernung zum aktuellen Halt muss erkennbar sinken, bevor der Ansageradius einen Trigger erzeugt. Der Eintritt in diesen Radius erledigt den Halt nicht. Zwischenankunft erfordert `Entfernung + Ungenauigkeit <= 120 m` und einen bestätigten Annäherungstrend oder zwei frische innere Fixes; der Einstieg kann schon im inneren Bereich als erreicht gelten.

Nach innerer Ankunft wird beim anschließenden Entfernen mit Hysterese zum nächsten Besuch gewechselt. 220 Meter bleiben die allgemeine Abfahrtsgrenze. Bei dicht aufeinanderfolgenden Halten ist ein früherer Wechsel möglich: Frische Fixes müssen das Entfernen vom beobachteten Halt und die Annäherung an dessen geordneten Nachfolger zeigen; der Nachfolger muss um mehr als die doppelte aktuelle Ungenauigkeit näher liegen. Die Bewegung muss mindestens 35 Meter beziehungsweise die Genauigkeitsschwelle stützen. Kleine Bewegungen können sich seit der geringsten beobachteten Entfernung summieren, statt jeweils 35 Meter zwischen zwei Fixes zu verlangen.

Für Zwischenhalte gibt es zusätzlich eine konservative Vorbeifahrt-Erkennung anhand Annäherung, minimaler Distanz und anschließendem Entfernen. Nach einem Wechsel bewertet dieselbe frische Fixfolge sofort den Nachfolger, damit dessen Ansage bei kurzen Busabständen nicht erst auf ein weiteres Update warten muss. Ein Fix rückt höchstens einen nicht gestrichenen Besuch vor. Wiederholte Stationsbesuche bleiben durch ihre Schlüssel getrennt; ausgefallene Halte werden übersprungen.

Am Ziel gilt ein eigener Bereich von 300 Metern einschließlich Ungenauigkeit. Ankunft benötigt zwei frische innere Fixes und eine aktuell gemeldete Geschwindigkeit höchstens 3 m/s oder mindestens zehn Sekunden stabilen Aufenthalt um einen Anker von etwa 20–30 Metern. Ein bestätigter stationärer Aufenthalt benötigt keinen vorherigen Annäherungstrend. Schnelle Vorbeifahrt, ein erster Fix nahe am Ziel und vergangene Planzeit allein beenden die Fahrt nicht. Auch diese räumliche Heuristik beweist keinen tatsächlichen Fahrzeughalt.

Beim späten Trackingstart können frische Bewegungsfixes die Abfahrt vom Ursprung herleiten, wenn sie im plausiblen Korridor weg vom Ursprung und auf den nächsten Halt zeigen. Dieser räumliche Bootstrap wartet nicht bis zur Plan- oder API-Abfahrt: Bereits vor diesen Zeiten unterstützte Weiterbewegung kann den Folgehalt etablieren und anschließend eine verfrühte GPS-Zeitprognose liefern. Stationäres Warten allein erzeugt dagegen keine Weiterfahrtprognose. Nach einer Signallücke kann ein bereits angenäherter Zwischenhalt ähnlich wieder eingeordnet werden. Dabei wird Bewegung erneut gesammelt; ein altes Entfernungsminimum vor der Lücke reicht nicht aus. Das Ziel wird durch diese Abfahrts-/Vorbeifahrtlogik nicht übersprungen und benötigt weiterhin eigene Ankunftsbeobachtungen.

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

Der Fahrplan-Rückfall bestätigt niemals die Zielankunft und beendet die Fahrt nicht automatisch. Bei dauerhaft fehlendem GPS muss der Nutzer die Fahrt über `Beenden` abschließen. Die Notification erklärt den ungefähren Fortschritt als `Fahrplan · ungefähr`; bei vergangener/fehlender Zielzeit oder fehlendem aktuellen Halt zusätzlich `Fahrt manuell beenden`. Das Widget kennzeichnet die aufgelöste Zeitquelle separat und verwendet den Fortschritts-/Beendenhinweis, wenn keine Zeit auflösbar ist. Die TTS-Ansage beginnt mit `Voraussichtlich`.

## GPS-Zeitprognosen

Nach der Engine-Auswertung verarbeitet `GpsJourneyTimeEstimator` den passenden Fix und die Plan-Ankunft/-Abfahrt der eingegrenzten Route. Geeignete Beobachtungen liefern lokale Istzeiten oder einen konservativen Versatz der kommenden Planzeiten. Eine gerichtete Fixfolge und die geplante Fahrzeit stützen die räumliche Interpolation; Luftlinie geteilt durch Momentangeschwindigkeit ist keine ETA-Methode.

`JourneyTimeResolver` verwendet je Ereignis frische eindeutig zugeordnete GPS-Zeit, sonst manuelle Zeit, parsebare API-Echtzeit und schließlich Planzeit. Notification, Widget, Fahrtdetail und Sperrbildschirm verwenden denselben Resolver und kennzeichnen die Zeitquelle. Standortqualität, Korridor und fehlende Daten können die GPS-Zeit verwerfen, während der räumlich etablierte Besuchscursor erhalten bleibt. Schwellen, stabile Ankunftsbeobachtung, längere Halte und Quellenentscheidung stehen unter [GPS-Zeiten](./gps-zeiten.md).

## Persistenz und Offlinebetrieb

`trip_tracking_state` speichert ein versioniertes JSON mit Status-ID, Check-in, zuletzt gültiger eingegrenzter Haltfolge und `TrackingProgress` (Cursor, Besuchsschlüssel, innerer Ankunftsstatus, `gpsEstablished`, erfolgreich eingereihte Ansageschlüssel und Abschlussstatus). Standortfixes, Bewegungshistorie, GPS-Istzeiten und GPS-Prognosen bleiben ausschließlich im Speicher; es wird keine GPS-Historie an Träwelling gesendet und kein automatischer Status-PUT ausgelöst.

Nach mindestens einem erfolgreichen Laden kann diese Route bei API-Ausfällen und nach Service-Neustart wiederverwendet werden. Ohne gültigen Cache und ohne erfolgreiche API-Antwort existiert keine auswertbare Haltfolge. Fortschritt wird nur für die noch aktive Status-ID gespeichert; Fahrtwechsel, Logout und bestätigtes Beenden entfernen den zugehörigen Cache.

## TTS, Notification und Widget

TTS benötigt die separate Option `Haltestellen ansagen` und Audiofokus. Sprache und Stimme kommen aus den Einstellungen. Ein Ansageschlüssel wird nur nach erfolgreichem Einreihen mit `TextToSpeech.SUCCESS` dauerhaft bestätigt. Bei ausgeschalteter/nicht bereiter TTS, verweigertem Audiofokus oder fehlgeschlagenem Einreihen wird er für erneuten Versuch freigegeben. Eine während der Initialisierung wartende Ansage wird nur abgespielt, wenn ihr Besuch noch aktuell ist.

`SpeechDeliveryQueue` ordnet jede eingereihte Ansage einer eindeutigen ID aus Status-ID, Service-Generation und laufender Nummer zu. Späte oder doppelte Callbacks können dadurch keinen neueren Versuch entfernen. Audiofokus bleibt erhalten, bis die letzte wartende Ansage endet. Bei `onError` oder `onStop` wird der zugehörige Besuch nur dann wieder freigegeben und gespeichert, wenn er noch aktuell ist; ein bereits verlassener Halt wird nicht erneut angesagt.

Bei Zielankunft wartet der Service auf eine bereits laufende oder gerade eingereihte Zielansage. Erst deren Abschluss, Fehler-/Stop-Callback oder spätestens ein 15-Sekunden-Timeout beendet den Service; die eigene Zielansage wird nicht sofort durch `stopTracking` abgeschnitten.

Notification und Widget erhalten Linie, nächsten Halt, Ziel, aufgelöste Zeit, Gleis sowie positive oder negative Abweichung zur Planzeit. Die Zeitquelle wird als `GPS beobachtet`, `GPS-Schätzung`, `Manuell`, `API-Echtzeit` oder `Fahrplan` gekennzeichnet. Gleisinformation bleibt aus den API-Feldern; GPS-Prognosen erzeugen keine Gleisdaten.

## Fahrtänderungen und Fortschrittsanzeige

Der [Änderungsmonitor](./trip-changes.md) verarbeitet ausschließlich frische erfolgreiche API-Snapshots. Die erste Antwort beziehungsweise die erste Antwort nach Cache-Wiederanlauf bildet still die Basis. Deduplizierungsmetadaten derselben Fahrt werden im bestehenden Cache gespeichert; der Standort-Tick erzeugt keine Änderungshinweise. Der separate Benachrichtigungskanal öffnet das passende Fahrtdetail; optionale Änderungssprache benötigt zusätzlich globale TTS. `SpeechDeliveryKind` trennt diese Ansagen von Stationsansagen und deren Wiederholungsfreigabe.

Das [Fortschrittsmodell](./trip-progress.md) verwendet den gemeinsamen Besuchscursor für verbleibende Halte und den Android-Balken. Das Ziel wird durch Zeitfortschritt allein nicht als erreicht markiert. Ab API 36 wird `ProgressStyle` verwendet, ältere Geräte erhalten eine normale Fahrtbenachrichtigung. Die Live-Update-Anfrage ist systemabhängig; deaktivierte Sperrbildschirmdetails verhindern sie und verwenden eine allgemeine öffentliche Ersatzanzeige.

## Stopp-Bedingungen

- GPS-Zielkriterium mit innerem Aufenthalt/niedriger Geschwindigkeit; gegebenenfalls erst nach Abschluss der Zielansage
- Manuell über die Notification-Aktion `Beenden`
- Aktive Fahrt wird ersetzt oder entfernt, zum Beispiel beim Logout

Beim Beenden werden Location-Callbacks, Polling und TTS gestoppt. Aktive Status-ID und Cache werden gemeinsam und nur für die passende Fahrt gelöscht.

## Validierung und offene Fragen

- Ein Nutzerbericht zu `1.7.0` meldet bei einer etwa zwei Minuten verfrühten Fahrt eine korrekte Ansage von Maubisstr. und die fehlende Ansage des folgenden Halts Rathaus. Der Screenshot zeigte mehrere `AKTUELL`-Markierungen und unterbrochene Timeline-Segmente. Das Zeitfenster je Zeile erklärt die mehrfachen Markierungen; überlappende Abfahrtsbereiche und die Ansagezustellung werden durch die Änderungen abgesichert. Unklar: Ohne Fix-/Audioverlauf ist die konkrete Ursache der fehlenden Ansage nicht bewiesen.
- Historische erfolgreiche Prüfläufe und der aktuelle automatisierte Prüfumfang stehen unter [Tests](../entwicklung/tests.md).
- TODO: Den korrigierten kurzen Haltübergang einschließlich Rathaus erneut auf einer echten Fahrt prüfen. Weitere Fälle: Tunnel, Vorbeifahrt, Rundfahrten, grobe Standortfreigabe, ausgeschaltetes Display und Neustart. Hier steht kein physisches Testgerät zur Verfügung.
- TODO: Radius- und Hysteresewerte nach diesen Fahrten bewerten; reine Nähe ist kein Nachweis eines Fahrzeughalts.

## Offizielle Quellen

- [Foreground-Service-Typen](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Standortberechtigungen und Display-aus-Betrieb](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [Geofencing: mögliche Hintergrundverzögerung](https://developer.android.com/develop/sensors-and-location/location/geofencing)
- [LocationRequest: Best-Effort-Vorgaben](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest)

## Verwandte Seiten

- [Check-in](./checkin.md)
- [Fahrterkennung](./ride-recognition.md)
- [Fahrtänderungen](./trip-changes.md)
- [Reisefortschritt](./trip-progress.md)
- [GPS-Zeiten](./gps-zeiten.md)
- [Settings](./settings.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Widget](./widget.md)
- [StatusDetail](./status-detail.md)
- [Datenmodell](../daten/datenmodell.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Externe Abhängigkeiten](../architektur/externe-abhaengigkeiten.md)
- [Tests](../entwicklung/tests.md)
- [Offene Fragen](../offene-fragen.md)
