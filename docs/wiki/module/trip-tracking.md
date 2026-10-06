# Modul: TripTrackingService

## Zweck

Der Android-Foreground-Service verfolgt die eingecheckte Haltfolge mit GPS und meldet den nächsten Halt per Notification, Widget und optionaler Sprachausgabe. Er vergleicht frische API-Daten für Änderungshinweise, berechnet lokale GPS-Zeitprognosen und liefert das gemeinsame Haltemodell der Fortschrittsbenachrichtigung. Bei fehlendem brauchbarem Standort nutzt er gekennzeichnete API-/Fahrplanangaben. Dies ist ein Stationsalarm, keine Turn-by-Turn-Streckenführung.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/service/StationTrackingEngine.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingLiveState.kt`
- `app/src/main/kotlin/de/traewelling/app/service/GpsJourneyTimeEstimator.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingRouteGeometry.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TransitRouteTracking.kt`
- `app/src/main/kotlin/de/traewelling/app/data/routing/TransitRouteRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/routing/TransitRouteParser.kt`
- `app/src/main/kotlin/de/traewelling/app/data/routing/RoadRouteRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/routing/RoadRouteParser.kt`
- `app/src/main/kotlin/de/traewelling/app/service/JourneyTimeResolver.kt`
- `app/src/main/kotlin/de/traewelling/app/service/SpeechDeliveryQueue.kt`
- `app/src/main/kotlin/de/traewelling/app/service/SpeechInitializationLifecycle.kt`
- `app/src/main/kotlin/de/traewelling/app/service/LifecycleRetryBudget.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingWakeLockLease.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingLocationObservation.kt`
- `app/src/main/kotlin/de/traewelling/app/service/SessionStartRequests.kt`
- `app/src/main/kotlin/de/traewelling/app/MainActivity.kt`
- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/de/traewelling/app/widget/TripWidgetProvider.kt`

## Start und Standortfreigabe

`CheckInViewModel` speichert nach einem erfolgreichen Check-in nur die aktive Status-ID. `MainActivity` beobachtet diese und die GPS-Einstellung im Zustand `RESUMED`; der Service wird aus der sichtbaren Activity gestartet. Präzise Standortfreigabe und aktivierte Ortungsdienste bestimmen, ob GPS aktiviert werden darf. Ohne diese Voraussetzungen startet der Fahrplanmodus. Automatische Standortanfragen werden je aktiver Fahrt begrenzt; der manuelle Einstellungsbutton kann bei dauerhafter Ablehnung die App-Berechtigungen öffnen.

Das Manifest deklariert `location|dataSync` sowie `FOREGROUND_SERVICE_LOCATION`. Der Service aktiviert bei GPS den Typ `location`, sonst `dataSync`. Bei einem `START_STICKY`-Neustart ohne Start-Intent wird GPS nicht eigenständig wieder aktiviert: Standortzugriff bleibt aus, bis eine sichtbare Activity ihn erneut geprüft hat. Dies ist ein vorübergehender Ausfall, kein bewusst gewählter Zeitmodus; ein bereits per GPS etablierter Besuch bleibt geschützt. Ein korrekt gestarteter Location-Foreground-Service kann auch bei ausgeschaltetem Display Updates erhalten; die tatsächliche Zustellung bleibt geräteabhängig.

Activity und Service lesen aktive ID und `AuthSession` gemeinsam über `trackingConfiguration`. Die sichtbare Startanfrage trägt die Sessionrevision; der Service bindet seine Verarbeitung an den vollständigen Snapshot und Fahrtgeneration. Ein Zugangsgenerationswechsel invalidiert laufende Verarbeitung sofort, auch wenn ein anderer Auftrag noch den Tracking-Mutex hält. Restore, API-Ergebnis, Anzeige, TTS und Persistenz prüfen die passende Sitzung erneut. Dieselbe numerische Status-ID auf einem anderen Konto oder Server gilt damit als neue Fahrt. Stop- und Wegwischaktionen der Benachrichtigung enthalten ebenfalls Revision und eine revisionsbezogene Intent-Identität; eine alte Aktion darf die neue gleich nummerierte Fahrt nicht verändern. Stop-Aktionen mit fehlender Revision werden ebenfalls abgelehnt.

`SessionStartRequests` hält ausschließlich kurzlebige Startaufträge im RAM. Erst ein atomar gültiger Start verdrängt ältere Initialisierungen. Ein später eingetroffener ungültiger Auftrag einer früheren Sitzung darf einen noch wartenden gültigen Auftrag der aktuellen Sitzung/Fahrt nicht verdrängen. Ohne passenden laufenden oder wartenden Start wird eine bloß vorläufige Foreground-Anzeige beendet; ein gültiger Stop beziehungsweise Timeout invalidiert wartende Starts.

`TripTrackingService.onTimeout(startId, fgsType)` beendet die laufende Verarbeitung synchron, falls Android die Foreground-Zeitgrenze meldet. Jobs, Standortupdates, TTS, Audiofokus und WakeLock werden freigegeben; Kommando-/Fahrtgeneration und Service-Scope verhindern eine spätere Veröffentlichung aus bereits wartenden Starts. Live-Zustand und Widget werden zurückgesetzt, aktive Status-ID und Fahrtcache dagegen für einen späteren sichtbaren Wiederanlauf erhalten. Der Callback wartet weder auf Preference-Schreiben noch auf die letzte Ansage und bestätigt keine Zielankunft. Das reguläre Android-15-Budget von insgesamt sechs Hintergrundstunden je 24 Stunden für `dataSync` gilt erst bei `targetSdk >= 35`; die App hat derzeit `targetSdk = 34` und `compileSdk = 36`. Die Behandlung ist daher eine Absicherung für die ausdrücklich aktivierte Android-Kompatibilitätsgrenze oder ein späteres Target-Upgrade, kein Nachweis eines regulären Budgetabsturzes dieses Builds. Eine Akku-Ausnahme hebt dieses Budget nicht auf.

## Display aus, CPU-WakeLock und Doze

Der Foreground-Service allein hält die CPU nicht wach. Seit der Korrektur vom 06.10.2026 hält ausschließlich die bestätigte aktive Fahrt einen nicht referenzgezählten `PowerManager.PARTIAL_WAKE_LOCK` mit dem Tag `Routely:TripTracking`. Er wird nach erfolgreicher Foreground-Promotion und Abgleich der aktiven Status-ID angefordert, bereits vor einer möglichen Cache-Wiederherstellung. Das Display bleibt ausgeschaltet; es wird kein Screen-WakeLock verwendet.

`TrackingWakeLockLease` bindet die Haltung an die aktuelle Service-Generation. Jeder `acquire` hat **120 Sekunden Timeout**, eine separate Coroutine erneuert sie nach **60 Sekunden**, solange dieselbe Generation mit aktiver Fahrt läuft und der Service nicht stoppt. Eine veraltete Fahrtgeneration darf nicht erneuern. Das Timeout ist der zusätzliche Rückhalt, falls die Erneuerung nicht mehr läuft; es ersetzt die ausdrückliche Freigabe nicht. Fehlgeschlagenes Anfordern darf die Tracking-App nicht abstürzen lassen und kann bei weiterhin aktiver Fahrt erneut versucht werden.

Fahrtwechsel gibt die alte Haltung frei, bevor die neue Fahrt sie übernimmt. Manuelles Beenden, normaler Service-Abschluss, fehlgeschlagener Start beziehungsweise Foreground-Rückfall und `onDestroy` beenden die Erneuerung und geben die Haltung frei. Beim bestätigten Ziel bleibt sie bis zum bestehenden TTS-Abschluss oder dessen 15-Sekunden-Timeout bestehen. Ein erneuter sichtbarer Start während der letzten Ansage startet GPS/Polling nicht neu. Außerhalb einer aktiven Fahrt wird kein CPU-WakeLock gehalten; die getrennte Fahrterkennung erhält durch diese Änderung keinen dauerhaften WakeLock.

Beim manuellen Beenden schützt die bereits begrenzte Haltung noch das atomare Löschen von aktiver Status-ID und Cache. `stopping=true` verhindert weitere Erneuerung; `finishService` in `finally` gibt sie auch bei einem Speicherfehler frei. Ein hängender Löschvorgang kann die ursprüngliche 120-Sekunden-Lease somit nicht unbegrenzt verlängern.

**Doze bleibt eine Android-Systementscheidung:** Im normalen Doze werden WakeLocks und Netzwerkzugriff beschränkt. Eine vom Nutzer gewährte Akkuoptimierungsausnahme erlaubt unter anderem partielle WakeLocks und Netzwerkzugriff, hebt aber nicht sämtliche Plattform- oder Herstellerbeschränkungen auf. Deshalb ergänzt die [Einstellungs-Card](./settings.md) eine ausdrückliche Systemanfrage und zeigt deren tatsächlichen Status. Ohne Ausnahme darf die App keine unverzögerten Ticks, API-Antworten oder Ansagen im tiefen Doze versprechen. Auch mit Ausnahme bleiben Standortzustellung, Samsung-Hintergrundregeln, Energiesparmodus, die ausgewählte TTS-Engine und Audiofokus separat zu prüfen. Nutzer-Force-Stop, entzogene Freigaben und Systemprozessende werden nicht umgangen.

Android-Grundlagen: [Doze und App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby), [WakeLock anfordern](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/set) und [WakeLock-Leitlinien](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices). Reale Zustellung und Akkuverbrauch stehen in der [Gerätetestmatrix](../entwicklung/tests.md).

## Datenfluss und Intervalle

| Verarbeitung | Gewünschtes Intervall |
| --- | --- |
| Status und Stopovers über Träwelling aktualisieren | 60 Sekunden |
| Fahrplan-/Signalalter prüfen | 10 Sekunden |
| Standortupdates unterwegs | 12 Sekunden |
| Standortupdates bis 3 km zum aktuellen Halt | 3 Sekunden |

Die LocationRequest-Mindestintervalle liegen bei 5 beziehungsweise 2 Sekunden; Android behandelt Anfragen als Best-Effort-Vorgaben. Die GPS-Auswertung läuft unabhängig vom API-Polling.

Eine fehlgeschlagene Registrierung bei `FusedLocationProviderClient` erhält nach dem ersten Versuch höchstens drei Wiederholungen mit zwei, fünf und 15 Sekunden Wartezeit. `LifecycleRetryBudget` bindet die Serie an einen monotonen Besitzer; Callbackidentität, Fahrtgeneration und Session prüfen die konkrete Registrierung zusätzlich. Verspätete Fehler oder Erfolge dürfen weder eine neue Fahrt beeinflussen noch deren Budget zurücksetzen. Ausgeschaltetes GPS, fehlende präzise Freigabe, ausgeschaltete Ortung, Zielabschluss und Service-Ende beenden die Wiederholungen. Ein `SecurityException`-Pfad versucht nicht erneut im Hintergrund. Nach ausgeschöpftem Budget bleibt der Fahrplan-Rückfall; ein erneuter sichtbarer App-Start kann eine neue autorisierte Serie beginnen.

`TrackingLocationClock` verwendet `Location.elapsedRealtimeNanos` für Alter und Reihenfolge; `Location.time` ist keine Fortschrittsbasis. Derselbe gecachte Fix erhält bei API-/Uhr-Ticks exakt seinen ursprünglichen Ereigniszeitpunkt. Ältere, tatsächlich zukünftige oder mehr als 30 Sekunden alte monotone Zeitmarken erzeugen keine neue Beobachtung. Eine Änderung des Versatzes zwischen Systemzeit und monotoner Uhr um mehr als eine Sekunde invalidiert Positionsbelege, Engine-Zeitwasserstand und GPS-Zeitprognosen. Besuchscursor und bereits gesprochene Schlüssel bleiben bestehen; der Adapter behält seine monotone Reihenfolge und verlangt einen strikt neuen Fix. Fahrt-/Sessionwechsel oder eine neue Serviceinstanz erzeugen einen neuen Adapter. Dies trennt die veränderliche Anzeige-/Fahrplanuhr von der Beobachtungsreihenfolge.

Standort-Batches werden vollständig in zeitlicher Reihenfolge verarbeitet. Ein gemeinsamer Mutex schützt Engine-Mutation und Übernahme des Ergebnisses einschließlich Notification, UI-Fortschritt, TTS und Persistenz gegenüber Tick, Routen- und Einstellungsänderungen. Netzwerkzugriffe liegen außerhalb dieser Sperre.

`trackingLocationFix` unterscheidet eine tatsächlich fehlende Android-Geschwindigkeitsangabe (`null`) von einer gemeldeten ungültigen Angabe wie NaN, Unendlich oder einem negativen Wert. Letztere bleibt als ungültiger Wert für Engine und Schätzer erkennbar und darf nicht über die Zehn-Sekunden-Regel für unbekannte Geschwindigkeit einen Einstiegs-Wartehinweis oder GPS-Aufenthaltsversatz ermöglichen. Die bisherige getrennte Ziel-Aufenthaltsprüfung wird dadurch nicht geändert.

Das Repository liefert Status und Stopovers. `checkedInRoute` grenzt die Route anhand von `matchesStopover` auf Einstieg bis Ziel ein; nicht auflösbare Grenzen ersetzen keine gültige Route. Manuelle Check-in-Zeiten werden in Echtzeitfelder übernommen. `TrackingStop` enthält Name, Koordinaten und Plan-/Echtzeit der Station sowie einen Besuchsschlüssel: bevorzugt Stopover-UUID, sonst Station-ID, Planzeiten und Routenindex.

`StationTrackingEngine` ist reine Kotlin-Logik ohne Android- oder Netzwerkzugriffe. Sie prüft die geordnete Haltfolge und bewahrt den aktuellen Besuch bei API-Aktualisierungen über seinen Schlüssel. Es wird nicht beliebig der global nächstgelegene Bahnhof ausgewählt. Ein noch rein zeitbasierter Cursor bleibt vorläufig: Der erste brauchbare GPS-Fix kann ihn bei einem eindeutigen nahen Halt räumlich neu verankern, auch bei großer Verspätung. Mehrdeutige Stationsbesuche werden nicht beliebig ausgewählt.

Ein erster Fix fern aller Stationen macht einen bereits zeitbasiert vorgerückten Cursor nicht zu einer bestätigten GPS-Zuordnung. Er bleibt nach Cache-Restaurierung korrigierbar und wird bis zur räumlichen Bestätigung als `Fahrplan · ungefähr` angezeigt. Ein späterer eindeutiger stationsnaher Fix kann ihn zum passenden Besuch zurückführen.

`trackingLiveState` veröffentlicht Cursor, Besuchsschlüssel, passenden Halt, Ankunfts-/Abschlussstatus, Fortschrittsquelle, Sessionrevision und optional `gpsTimes` als prozesslokalen `StateFlow`. Der Zustand wird beim Fahrtwechsel und Service-Ende entfernt. Er enthält keine Geräteposition und wird nicht in einem neuen DataStore-Key gespeichert. Die [Status-Detail-Timeline](./status-detail.md) übernimmt ihn nur für die eigene, angezeigte aktive Fahrt derselben Zugangsgeneration.

## SEV-Punkte und asynchrone Anreicherung

Für Bus-RE/RB-Kandidaten lädt der Service die [öffentliche SEV-Quelle](./sev-haltestellen.md) in einer eigenen `sevJob`-Coroutine. Der zuerst übernommene API-Zustand und frische GPS-Fixes warten nicht auf diese Netzwerkantwort. Die vollständige API-Haltfolge bleibt als Richtungskontext verfügbar; Abrufe betreffen nur die eigene eingegrenzte, nicht gestrichene Route. Ergebnisse werden ausschließlich für dieselbe Service-Generation, Status-ID, Check-in-Grenzen und vollständige Haltfolge übernommen.

`cachedSevMaps` hält die öffentliche Quelle; `cachedSevStops` enthält die aktuell aufgelösten Hinweise je Besuch. Bei API-Aktualisierung, Fahrplan-Tick und frischem GPS-Fix werden Quellenalter und Gültigkeit erneut geprüft. `toTrackingStops()` verwendet ein eindeutig belegtes SEV-Koordinatenpaar, sonst die vorhandene API-Koordinate. Providerhalte, Stations-ID, Besuchsschlüssel, Zeiten und Gleise werden nicht überschrieben.

Bei einem veränderten physischen Punkt invalidiert der Service die GPS-Zeitbasis. `StationTrackingEngine.updateRoute()` entfernt eine Ankunftsbestätigung, wenn sich die aktuelle Koordinate ändert, erhält jedoch denselben Besuch und bereits gesprochene Schlüssel. Der Kartenabschluss verwendet einen Fahrplan-Tick; ein bereits verarbeiteter alter GPS-Fix wird nicht als neuer Bewegungsbeleg eingespeist.

Der vorhandene Cache kann `fullStopovers` und `sevMaps` enthalten. Beide Felder sind optional; alte Version-1-Caches bleiben lesbar. Wiederhergestellte SEV-Karten werden neu gegen Alter, Datum und Richtung geprüft. Wenn SEV-Karten vorliegen, wird die alte Ankunftsbestätigung bis zu einem frischen physischen Fix entfernt. Bei Netzwerkfehlern darf ein zuvor gültiger öffentlicher Snapshot nur bis zur Auflösungsgrenze weiterwirken. Die Hinweise werden über `TrackingLiveState.sevStops` an die Detailansicht publiziert. GPS-Gerätepositionen und Prognosen bleiben weiterhin unpersistiert. Fahrtwechsel, Zielabschluss und Service-Ende brechen `sevJob` ab.

Bei Bus-RE/RB-Kandidaten werden Bahn-Gleisangaben für Fahrtbenachrichtigung, Widget und TTS unterdrückt. Reine `PLATFORM`-Ereignisse des Änderungsmonitors werden für diese Fahrten nicht zugestellt; andere Änderungshinweise bleiben bestehen. Dies entfernt keine Gleisfelder aus der API und ersetzt sie nicht durch eine erfundene Bussteigangabe.

## Nativer Bahn-/Tramverlauf

Für passende Bahn-/S-/U-Bahn-/Tramkategorien lädt `transitRouteJob` den [Träwelling-Streckenverlauf](./gps-zeiten.md) über `polyline/{statusId}`. Die gesamte eindeutig eingegrenzte eigene Route einschließlich gestrichener Zwischenbesuche bildet die Zuordnungsbasis; unbekannte Kategorien, Busmodus und SEV werden ausgeschlossen. Planmarker, Stationsidentität/-koordinaten und eindeutige Service-Besuchsschlüssel gehören zum Request, Echtzeit-/Gleisänderungen und Gerätefixes nicht. Netzwerk liegt außerhalb des Tracking-Mutex.

Ein sessiongebundenes `TransitRouteRepository` gehört zur aktuellen Lease aus Status-ID, Fahrtgeneration, Auth-Snapshot und vollständigem Request. Übernahme prüft diese Basis erneut. Der eigene Service-Cache akzeptiert eine Form höchstens 15 Minuten, erlaubt Refresh ab 14 Minuten und wartet nach fehlgeschlagenem Laden mindestens eine Minute; der Repository-Fehlversuchcache beträgt zwei Minuten. Bei GPS aus, Fahrt-/Sitzungswechsel, Zielabschluss, Beenden und Zerstörung werden Abruf und Repository geschlossen. Ein Netzabschluss führt ausschließlich zur Fahrplanauswertung mit denselben verbrauchten Fixmarkern, nie zu einer neuen GPS-Beobachtung.

Der Parser übernimmt ausschließlich eindeutige geordnete Abschnitte mit zusätzlichem Formbeleg; reine Stationssehnen und mehrdeutige Schleifen liefern keinen neuen Linienzug. `TrackingRouteGeometry` stellt eine gemeinsame Projektion für Zeitschätzer und gerichtete Abfahrts-/Lücken-/Nahhalt-Hilfen bereit. Physische Haltankunft, Zielabschluss, Mehrhalt-Wiederverankerung und der normale Ansageradius bleiben unabhängige Stationsregeln. Eine native Form zertifiziert keine derzeit befahrene offizielle Gleisführung. Ohne geeignete Form bleibt die bisherige konservative Geradenprüfung für normale Bahn/Tram bestehen; der strengere SEV-Straßenvertrag bleibt getrennt.

## GPS-Trigger und Fortschritt

Die optionale Straßen-Geometrie für SEV-Zeitprognosen verändert die folgenden Regeln der `StationTrackingEngine` nicht. Halterkennung, Ansageradius und Zielabschluss verwenden weiterhin die eigenen geordneten Besuchs- und Entfernungsbelege.

Ein brauchbarer Fix hat gültige Koordinaten, höchstens 100 Meter gemeldete Ungenauigkeit und ist höchstens 30 Sekunden alt. Ungültige, alte oder bereits verarbeitete Zeitstempel bestätigen keine neue Annäherung. Nach einer längeren Signallücke muss ein neuer Annäherungstrend entstehen.

Auch der gewöhnliche Ankunfts-, Abfahrts- und Ansagepfad prüft vor seiner Mutation auf unplausible Ortswechsel. Zwischen zwei unterstützten Fixes mit höchstens 30 Sekunden Abstand darf die Distanz nicht größer sein als `100 m/s × Zeitabstand + beide Genauigkeiten`. Ein verworfener Sprung verbraucht seinen Zeitmarker, ersetzt jedoch nicht die letzte unterstützte Position und bestätigt weder Halt, Ansage noch Zielabschluss. Der separate Zeitschätzer bleibt zusätzlich konservativ; seine Ablehnung allein könnte einen bereits mutierten Cursor nicht schützen. Nach längeren Lücken gelten weiterhin die eigenen Wiederverankerungsbelege.

Die Entfernung zum aktuellen Halt muss erkennbar sinken, bevor der Ansageradius einen Trigger erzeugt. Der Eintritt in diesen Radius erledigt den Halt nicht. Zwischenankunft erfordert `Entfernung + Ungenauigkeit <= 120 m` und einen bestätigten Annäherungstrend oder zwei frische innere Fixes; der Einstieg kann schon im inneren Bereich als erreicht gelten.

Nach innerer Ankunft wird beim anschließenden Entfernen mit Hysterese zum nächsten Besuch gewechselt. 220 Meter bleiben die allgemeine Abfahrtsgrenze. Bei dicht aufeinanderfolgenden Halten ist ein früherer Wechsel möglich: Frische Fixes müssen das Entfernen vom beobachteten Halt und die Annäherung an dessen geordneten Nachfolger zeigen; der Nachfolger muss um mehr als die doppelte aktuelle Ungenauigkeit näher liegen. Die Bewegung muss mindestens 35 Meter beziehungsweise die Genauigkeitsschwelle stützen. Kleine Bewegungen können sich seit der geringsten beobachteten Entfernung summieren, statt jeweils 35 Meter zwischen zwei Fixes zu verlangen.

Für Zwischenhalte gibt es zusätzlich eine konservative Vorbeifahrt-Erkennung anhand Annäherung, minimaler Distanz und anschließendem Entfernen. Nach einem Wechsel bewertet dieselbe frische Fixfolge sofort den Nachfolger, damit dessen Ansage bei kurzen Busabständen nicht erst auf ein weiteres Update warten muss. Der gewöhnliche Abfahrts-/Vorbeifahrtfortschritt rückt je Fix höchstens einen nicht gestrichenen Besuch vor. Die getrennte, über mehrere Fixes bestätigte Wiederverankerung nach einer Signallücke kann dagegen einen späteren Besuch auswählen. Wiederholte Stationsbesuche bleiben durch ihre Schlüssel getrennt; ausgefallene Halte werden übersprungen.

Am Ziel gilt ein eigener Bereich von 300 Metern einschließlich Ungenauigkeit. Ankunft benötigt zwei frische innere Fixes und eine aktuell gemeldete Geschwindigkeit höchstens 3 m/s oder mindestens zehn Sekunden stabilen Aufenthalt um einen Anker von etwa 20–30 Metern. Ein bestätigter stationärer Aufenthalt benötigt keinen vorherigen Annäherungstrend. Schnelle Vorbeifahrt, ein erster Fix nahe am Ziel und vergangene Planzeit allein beenden die Fahrt nicht. Auch diese räumliche Heuristik beweist keinen tatsächlichen Fahrzeughalt.

Am bereits erreichten Einstieg wird der gewöhnliche Annäherungstrigger unterdrückt: Eine kleine Rückschwankung beim Losfahren darf nicht nachträglich `Bitte einsteigen` erzeugen. Ein Abfahrtshinweis ist dagegen während bestätigten Wartens verfügbar, auch wenn GPS den Fahrplan-Tick sonst ersetzt. Er benötigt mindestens zwei frische Fixes innerhalb von `Entfernung + Genauigkeit <= 120 m`, einen mindestens drei Sekunden stabilen Aufenthaltsanker und eine bekannte Geschwindigkeit von höchstens 1,5 m/s. Ohne Geschwindigkeitsangabe sind mindestens zehn Sekunden stabiler Aufenthalt nötig. Gemeldete hohe oder ungültige Geschwindigkeit wird dadurch nicht als Warten behandelt; Bewegung setzt den Anker zurück. Der effektive API-/manuelle Abfahrtszeitpunkt muss noch bevorstehen und höchstens 180 Sekunden entfernt sein. Der normale Tick kann dieses Zeitfenster auf Basis der bereits belegten frischen Standortfolge prüfen; Uhrzeit allein bestätigt weder Warten noch Zielankunft. Der Besuchscursor und seine GPS-Quelle bleiben erhalten.

Beim späten Trackingstart können frische Bewegungsfixes die Abfahrt vom Ursprung herleiten, wenn sie im plausiblen Korridor weg vom Ursprung und auf den nächsten Halt zeigen. Dieser räumliche Bootstrap wartet nicht bis zur Plan- oder API-Abfahrt: Bereits vor diesen Zeiten unterstützte Weiterbewegung kann den Folgehalt etablieren und anschließend eine verfrühte GPS-Zeitprognose liefern. Stationäres Warten allein erzeugt dagegen keine Weiterfahrtprognose. Nach einer Signallücke kann ein bereits angenäherter Zwischenhalt ähnlich wieder eingeordnet werden. Dabei wird Bewegung erneut gesammelt; ein altes Entfernungsminimum vor der Lücke reicht nicht aus. Das Ziel wird durch diese Abfahrts-/Vorbeifahrtlogik nicht übersprungen und benötigt weiterhin eigene Ankunftsbeobachtungen.

### Wiederverankerung nach Tunnel oder GPS-Ausfall

Eine bereits mit Annäherung oder Ankunft belegte gewöhnliche Abfahrt/Vorbeifahrt hat Vorrang vor der neuen Kandidatenfolge. Eine reine Vorschau verwendet dieselbe physische Fortschrittsbedingung wie die normale Auswertung und benötigt ein frisches vorheriges Fixpaar. Dadurch wird ein korrekt erreichter naher Folgehalt weiterhin sofort übernommen und angesagt; die neue Wiederverankerung darf diesen bestehenden Fortschritt nicht verzögern.

Der Nutzerbericht Essen Hbf → Bismarckplatz → Savignystraße beschreibt mehrere Halte ohne GPS und ein wiederkehrendes Signal am späteren Halt. Im alten Code blieb ein bereits etablierter Besuch geschützt, während die Initialisierung ihn nicht erneut zuordnete. Die bisherige Lückenerkennung konnte nur einen geordneten Folgehalt mit vor der Lücke bestätigter Annäherung übernehmen. Diese Kombination erklärt eine logische Mehrhalt-Wiederverankerungslücke; der tatsächliche Nutzer-Fix-/Audioverlauf ist damit nicht nachgewiesen.

Die zusätzliche Wiederverankerung ist bei initialer noch nicht physisch bestätigter Zuordnung, restauriertem GPS-Cursor, ausdrücklicher Standortinvalidierung oder mehr als 30 Sekunden Signallücke verfügbar. Bestehender Abfahrts-Bootstrap und die geordnete Einhalt-Lückenerkennung werden zuerst geprüft. Ein solcher Übergang belegt das Verlassen des alten Halts, noch nicht den neuen Besuch; die Mehrhalt-Wiederverankerung bleibt bis zu dessen eigener physischer Bestätigung verfügbar. Ein belegter aktueller Abschnitt darf weiterhin normal fortgesetzt werden; die Erweiterung ist kein regelmäßiges Springen zum nächsten beliebigen Bahnhof.

Ein späterer nicht gestrichener Besuch muss mit `Entfernung + Genauigkeit <= 150 m` genau einen räumlichen Kandidaten ergeben und einen nicht leeren, in der Route eindeutigen Besuchsschlüssel besitzen. Die Prüfung berücksichtigt alle nicht gestrichenen Besuche der Route einschließlich vergangener und aktueller Halte; nahe konkurrierende Besuche und eine wiederholte Stations-ID des Kandidaten verhindern eine beliebige Auswahl. Ein nicht gestrichenes Ziel darf nicht übersprungen werden. Derselbe Kandidat benötigt mindestens drei strikt neue, nicht zukünftige Fixes über mindestens sechs Sekunden mit höchstens 75 Metern Ungenauigkeit. Zwischenfixe dürfen höchstens 30 Sekunden auseinanderliegen; die räumliche Bewegung ist auf 100 m/s über die verstrichene Zeit plus beide Genauigkeitstoleranzen begrenzt. Auch konsistentes stationäres Wiederfinden kann den Halt bestätigen. Plan-/API-Uhrzeit ist kein Bestätigungsbeleg.

Während dieses Kandidatenbelegs bleibt der bisherige Besuchscursor erhalten, die Ausgabe nutzt `TIMETABLE` und erzeugt keine neue Ansage für den alten Besuch. `isReacquiringLocation()` liefert diesen vorübergehenden Engine-Zustand auch bei API-/Uhr-Ticks. Er wird nicht im Fahrtcache gespeichert. Wiederholte API-/Uhr-Fixes zählen nicht erneut und löschen einen weiterhin gültigen Kandidatenbeleg nicht. Neue unbrauchbare Fixes, neue Lücken und eine veränderte Besuchs-/Koordinatenbasis setzen die Kandidatenfolge zurück. Der Service prüft den Zustand zusätzlich vor einer während der TTS-Initialisierung zurückgestellten Ansage; diese darf den alten Besuch nicht nachträglich sprechen.

Die Engine behält den höchsten bereits akzeptierten Fixzeitstempel im RAM auch über Standortinvalidierung und GPS-Umschaltung hinweg. Bereits verbrauchte Fixes dürfen dadurch weder den Kandidatenbeleg noch den GPS-Cursor erneut stützen. Ein Prozessneustart persistiert diesen Zeitstempel nicht; ein restaurierter Cursor benötigt weiterhin neue lokale Positionsbelege.

Die bestätigte Auswahl geht ausschließlich vorwärts zum konkret belegten Besuch, setzt dessen Ankunfts-/Aufenthaltsbelege zurück und erhält bereits gesprochene Besuchsschlüssel. Übersprungene Tunnelhalte erhalten keine erfundenen Ankunfts-/Abfahrtszeiten oder rückwirkenden Ansagen. Auch ein neu ausgewähltes Ziel benötigt weiterhin die normalen frischen Ankunfts-/Aufenthaltsbelege, bevor es die Fahrt beendet. Cursor-Wiederverankerung und neue GPS-Zeiten bleiben getrennt; die Zeitauswertung muss die neue Basis selbst unterstützen.

Eine Auswahl im äußeren 150-Meter-Bereich kann einen bereits verlassenen Halt treffen. Daher entfernt sie nur den offenen Kandidatenzustand, ohne die Möglichkeit einer weiteren Wiederverankerung zu sperren. Gleiches gilt für eine vorläufige Initialauswahl und weitere Einhalt-Übergänge während dieser Phase. Eine Abfahrt oder Vorbeifahrt bestätigt den verlassenen Besuch, nicht automatisch dessen Nachfolger. Erst eine eigene innere Ankunftsbestätigung des neuen aktuellen Besuchs beziehungsweise der bestätigte Zielabschluss beendet die Wiederfindungsphase; sonst könnte der Cursor nach der ersten Korrektur erneut hängen bleiben.

Diese Wiederverankerung verwendet Haltpunkte. Der zusätzlich verfügbare native Linienzug kann andere gerichtete Fortschrittshilfen und Zeitprognosen stützen, ersetzt aber weder den frischen Kandidatenbeleg noch physische Zielankunft. Ohne geeigneten Linienzug bleiben gerade Haltverbindungen verfügbar; die getrennte SEV-Straßenprojektion wird dadurch nicht verändert. Geräteprüfungen und Regressionen stehen unter [Tests](../entwicklung/tests.md).

### Ansageradius

Standard ist `clamp(geglättete Geschwindigkeit in m/s * 45, 300, 2000)` Meter. Die Geschwindigkeit wird aus dem Standortwert oder aufeinanderfolgenden Positionen ermittelt und geglättet. Ohne verwertbare Geschwindigkeit gilt der Mindestradius. Die Einstellungen erlauben alternativ feste 300, 500, 1.000 oder 2.000 Meter.

| Gleichmäßige Geschwindigkeit | Automatischer Radius |
| --- | --- |
| 30 km/h | 375 m |
| 80 km/h | 1.000 m |
| 160 km/h | 2.000 m |

Der normale Ansageradius misst weiterhin Luftlinie zum physischen Halt, auch wenn die Zeitprojektion einen Linienzug nutzt. Die Formel garantiert keine 45-Sekunden-Ankunftsprognose. Kurze Stationsabstände, Kurven und Schleifen müssen bei echten Fahrten geprüft werden.

## Fahrplan-Rückfall

Ohne brauchbaren Standort oder ohne Koordinaten des aktuellen Halts verwendet die Engine Echtzeit, sonst Planzeit. Zeitbasierte Ansagen liegen innerhalb der nächsten **180 Sekunden**; die frühere Rundung auf ganze Minuten wird nicht mehr verwendet.

Vor dem ersten zuverlässigen Fix können vergangene Zwischenhalte anhand ihrer Zeit übersprungen werden. `gpsEstablished` hält fest, ob der Cursor bereits per GPS etabliert wurde; reine Zeitfortschritte bleiben nach einem Neustart räumlich korrigierbar. Ein etablierter Halt mit Koordinaten bleibt bei Signalausfall, Berechtigungsverlust und ungeprüftem Sticky-Neustart erhalten. Fehlende Koordinaten erlauben weiterhin zeitbasierten Fortschritt.

Bewusstes Ausschalten von `GPS verwenden` aktiviert dagegen den Zeitmodus und erlaubt dessen Fortschritt. Ein temporärer Standortausfall wird damit nicht gleichgesetzt. Beim erneuten Aktivieren kann GPS den vorläufigen Zeitcursor wieder verankern.

Der Fahrplan-Rückfall bestätigt niemals die Zielankunft und beendet die Fahrt nicht automatisch. Bei dauerhaft fehlendem GPS muss der Nutzer die Fahrt über `Beenden` abschließen. Die Notification erklärt den ungefähren Fortschritt als `Fahrplan · ungefähr`; bei vergangener/fehlender Zielzeit oder fehlendem aktuellen Halt zusätzlich `Fahrt manuell beenden`. Das Widget kennzeichnet die aufgelöste Zeitquelle separat und verwendet den Fortschritts-/Beendenhinweis, wenn keine Zeit auflösbar ist. Die TTS-Ansage beginnt mit `Voraussichtlich`.

## GPS-Zeitprognosen

Nach der Engine-Auswertung verarbeitet `GpsJourneyTimeEstimator` den passenden Fix und die Plan-Ankunft/-Abfahrt der eingegrenzten Route. Geeignete Beobachtungen liefern lokale Istzeiten oder einen konservativen Versatz der kommenden Planzeiten. Eine gerichtete Fixfolge und die geplante Fahrzeit stützen die räumliche Interpolation; Luftlinie geteilt durch Momentangeschwindigkeit ist keine ETA-Methode.

Bei gewöhnlichen Bahn-/Tramfahrten darf ein geeigneter visitgebundener nativer Abschnitt die Geradenprojektion ersetzen. `TrackingLiveState.gpsGeometrySource` verwendet zuerst die aktuelle Zeitauswertung, sonst die frische aktive Railprojektion der Stationsengine: `TRIP_POLYLINE` kennzeichnet einen tatsächlich passenden Träwelling-Linienzug, `ROAD_MODEL` ein SEV-Straßenmodell. `null` behauptet keine sicher verwendete Polyline. Eine aktive Railbasis kann auch bei fehlenden Planmarkern oder über 90 Minuten Fahrintervall sichtbar bleiben, während die Zeitprognose zurückfällt. Fixablauf oder laufende Wiederverankerung lassen den Enginehinweis entfallen. Ein nur geladenes Modell ist kein Beleg einer verwendeten Form oder einer GPS-Prognose; die [Detailansicht](./status-detail.md) erklärt die Quelle.

Bei Bus-RE/RB-Ersatzverkehr benötigt die Abschnittsprognose eine validierte [Straßen-Geometrie](./gps-zeiten.md) zwischen zwei aktuell eindeutig zugeordneten öffentlichen SEV-Punkten. `RoadRouteSelection` wählt höchstens den aktuellen Ankunftsabschnitt und dessen Folgeabschnitt, am Einstieg die ersten zwei passenden Abschnitte; ein nicht bestätigter Ersatzhalt wird nicht überbrückt. `roadRouteJob` lädt dieses Fenster außerhalb der Tracking-Sperre. Vor Übernahme müssen Fahrtgeneration, geordnete Besuchsschlüssel, aktuelles Fenster und physische Endpunkte weiterhin passen. API-/Standortverarbeitung warten nicht auf den Abruf. Native API-Polylines ohne Herkunftsbeleg und gerade Stationsverbindungen dienen nicht als SEV-Rückfall.

Der Schätzer nutzt den lokal passenden Linienzug nur zur Fortschrittsprojektion, nicht die OSRM-Fahrtdauer. Das Pkw-Profil ist kein offizieller Busweg. Fehlende, abgelaufene oder mehrdeutige Geometrie verhindert die bewegungsgestützte Abschnittsprognose. Bestätigte Ereignisse und der bestehende Planversatz aus Aufenthalt am passenden Zwischenhalt bleiben davon getrennt; ohne geeignete lokale Zeit gilt der normale Zeitquellenrückfall. Eine Formänderung desselben Besuchspaars entfernt Bewegung und Zukunftsprognose, erhält jedoch bestätigte tatsächliche Ereignisse derselben Haltbasis; ein zuvor verarbeiteter Fix wird dadurch nicht erneut als Beobachtung verwendet.

Die Prognosebasis berücksichtigt nur Endpunkte und Linienzüge des aktuell eingehenden Abschnitts, keinen geänderten Abrufzeitpunkt oder zusätzlich vorgeladenen Folgeweg. Nach hinreichendem eindeutigem Bewegungsbeleg kann ein Kandidat innerhalb dieses Abschnitts gebunden bleiben, auch wenn mehrere Wege später wieder dieselbe Straße nutzen. Korridorverlust, Mehrdeutigkeit innerhalb des gebundenen Wegs, Form-/Abschnittswechsel und ungültiges GPS lösen die Bindung. Beim geordneten Abschnittswechsel beginnen Bewegungsbeleg und Kandidatenwahl neu; ein kompatibler frischer Fix darf die bisherige gültige Prognose nur bis zum ursprünglichen Ablauf erhalten. Endpunktverbindungen erhalten die bestätigten öffentlichen SEV-Koordinaten; Wegsprünge unterliegen weiter Zeit-/Genauigkeitsprüfungen. Details stehen unter [GPS-Zeiten](./gps-zeiten.md).

`JourneyTimeResolver` verwendet je Ereignis frische eindeutig zugeordnete GPS-Zeit, sonst manuelle Zeit, parsebare API-Echtzeit und schließlich Planzeit. Notification, Widget, Fahrtdetail und Sperrbildschirm verwenden denselben Resolver und kennzeichnen die Zeitquelle. Eine bereits belegte Prognose wird bei Bremsen oder geordnetem Haltwechsel mit passender frischer Position bis zu ihrem unveränderten ursprünglichen Gültigkeitsende erhalten. Standortqualität, Korridor, Ablauf und fehlende Daten können die GPS-Zeit weiterhin sofort verwerfen, während der räumlich etablierte Besuchscursor erhalten bleibt. Schwellen, stabile Ankunftsbeobachtung, längere Halte und Quellenentscheidung stehen unter [GPS-Zeiten](./gps-zeiten.md).

## Persistenz und Offlinebetrieb

`trip_tracking_state` speichert ein versioniertes JSON mit Status-ID, Check-in, zuletzt gültiger eingegrenzter Haltfolge und `TrackingProgress` (Cursor, Besuchsschlüssel, innerer Ankunftsstatus, `gpsEstablished`, erfolgreich eingereihte Ansageschlüssel und Abschlussstatus). Standortfixes, Bewegungshistorie, GPS-Istzeiten und GPS-Prognosen bleiben ausschließlich im Speicher; es wird keine GPS-Historie an Träwelling gesendet und kein automatischer Status-PUT ausgelöst.

Auch die zusätzlichen Straßen-Geometrien bleiben ausschließlich im RAM: höchstens acht besuchsbezogene Service-Abschnitte und ein davon getrennter begrenzter Repository-Cache. Es gibt dafür kein neues DataStore-Feld und keine Cache-Migration. Ein laufender Prozess kann noch gültige geladene Wege bei Netzausfall verwenden; nach einem Neustart müssen benötigte Abschnittswege erneut verfügbar werden. GPS aus, Fahrtwechsel, Zielabschluss und Service-Ende beenden die fahrtspezifische Straßen-Anreicherung und verhindern die Übernahme später Ergebnisse. Ein gemeinsam genutzter öffentlicher Repository-Job kann nach Abbruch eines wartenden Service-Jobs noch auf das Request-Limit warten und anschließend den RAM-Cache füllen. Der tatsächliche HTTP-Call hat einen 20-Sekunden-Timeout; die gemeinsame Aufgabe kann keine Fahrt fortsetzen.

Native Bahn-/Tram-Geometrien bleiben ebenfalls unpersistiert: Der sessiongebundene Repository-Cache hält höchstens acht vollständige Request-Ergebnisse und vier laufende Abrufe. Der vorhandene Fahrtcache stellt nach Wiederanlauf lediglich Haltfolge und Besuchsidentität für einen neuen Abruf bereit. Ein noch gültiger bereits geladener Linienzug kann im laufenden Prozess einen Netzausfall überbrücken, aber nicht seinen ursprünglichen 15-Minuten-Ablauf verlängern. Ohne passende Geometrie und GPS-Belege gelten die vorhandenen Geraden-/API-/Planregeln.

Nach mindestens einem erfolgreichen Laden kann diese Route bei API-Ausfällen und nach Service-Neustart wiederverwendet werden. Ohne gültigen Cache und ohne erfolgreiche API-Antwort existiert keine auswertbare Haltfolge. Fortschritt wird nur für die noch aktive Status-ID gespeichert; Fahrtwechsel, Logout und bestätigtes Beenden entfernen den zugehörigen Cache.

Schreiben und Löschen des Fahrtcache prüfen den erwarteten Auth-Snapshot zusätzlich zur ID innerhalb derselben DataStore-Transaktion. Die Service-Sessionbindung bleibt RAM-Zustand; sie führt keine Zugangsdaten oder neue Revisionsfelder in das Version-1-Cache-JSON ein.

## TTS, Notification und Widget

Der Service beobachtet TTS-Aktivierung, Engine, Sprache und Stimme als reaktive Konfiguration. Eine Sprachengine wird erst für eine aktive Fahrt mit aktivierter Sprachausgabe angelegt. Enginewechsel und Ausschalten stoppen die Queue, geben Audiofokus frei und schließen die bisherige Instanz. Sprache und Stimme werden auf der bereiten Instanz neu gesetzt; eine nicht verfügbare Auswahl erzeugt einen sichtbaren Fehler statt still die alte Stimme weiterzuverwenden. Ein abgebrochener Versuch des noch aktuellen Besuchs wird freigegeben; bereits verlassene Besuche werden nicht nachträglich gesprochen.

`SpeechInitializationLifecycle` trennt Konfigurationsbesitzer und Initialisierungsversuch. Ein Versuch hat zehn Sekunden Zeit; Fehler oder Zeitüberschreitung erlauben höchstens drei Wiederholungen nach zwei, fünf und 15 Sekunden. Alte Init-/Utterance-Callbacks werden unter dem Tracking-Mutex anhand ihres Versuchstokens verworfen. Nach Erschöpfung bleibt ein Fehlerzustand, bis eine neue Konfiguration oder ein erneuter sichtbarer Start die Initialisierung anstößt. Beenden, Sitzungstausch und Zerstörung brechen Timeout und Retry ab und schließen TTS. Die bestehende höchstens 15 Sekunden dauernde Wartezeit auf die Zielansage wird dadurch nicht verlängert.

Eine zurückgestellte Ansage wird vor Wiedergabe erneut einem eindeutigen aktuellen, nicht gestrichenen Besuch der heutigen Check-in-Route zugeordnet. Auch eine geänderte Zielklassifizierung darf nicht aus einem alten `TrackingStop` übernommen werden. Cursor-, Wiederverankerungs-, Sitzungs- und Einstiegsrelevanzprüfung bleiben erforderlich. `TrackingLiveState.locationError` und `speechError` transportieren aktuelle Registrierungs-/Sprachfehler an Fahrtdetail und Fahrtbenachrichtigung; diese Diagnose wird nicht im Fahrtcache gespeichert.

TTS benötigt die separate Option `Haltestellen ansagen` und Audiofokus. Sprache und Stimme kommen aus den Einstellungen. Ein Ansageschlüssel wird nur nach erfolgreichem Einreihen mit `TextToSpeech.SUCCESS` dauerhaft bestätigt. Bei ausgeschalteter/nicht bereiter TTS, verweigertem Audiofokus oder fehlgeschlagenem Einreihen wird er für erneuten Versuch freigegeben. Eine während der Initialisierung wartende Ansage wird nur abgespielt, wenn ihr Besuch noch aktuell ist. Direkt vor Audiofokus und TTS prüft `isOriginAnnouncementRelevant` Einstiegsansagen erneut nach den asynchronen Preference-/Stimmabfragen: Derselbe nicht gestrichene Ursprungsbesuch muss aktuell und unabgeschlossen sein, und das bevorstehende effektive Abfahrtsfenster muss weiterhin gelten. Frisches GPS benötigt den bestätigten Wartebeleg, auch wenn der alte Versuch ursprünglich aus dem Fahrplanmodus kam. Ein inzwischen veralteter GPS-Versuch wird nicht als bloße Fahrplanansage nachgereicht. Ohne verwertbares GPS bleibt ein gültiger reiner Fahrplanversuch möglich. Ein abgelehnter Versuch wird über die bestehende Freigabe-/Retry-Logik behandelt.

Die Einstiegsansage beschreibt nun den tatsächlichen Zweck: `Deine Fahrt … startet in Kürze in …`, mit aufgelöster Abfahrtszeit und Abfahrtsgleis aus der API, statt eine bevorstehende Ankunft am bereits erreichten Ursprung zu behaupten. Im Fahrplanmodus wird die Abfahrt als voraussichtlich gesprochen; Zwischen- und Zielhaltansagen nutzen weiterhin ihre Ankunftsgleise.

Einstiegsansagen werden zusätzlich zurückgestellt, solange `SpeechDeliveryQueue` noch eine Stations- oder Änderungsansage enthält. Sie werden nicht hinter einer laufenden Ansage eingereiht, die erst nach der Abfahrt enden könnte. Die bestehende Freigabe-/Retry-Logik versucht den Hinweis anschließend erneut, solange derselbe bestätigte Wartezustand und das gültige Abfahrtsfenster noch vorliegen. Nach begonnener Bewegung oder Ablauf bleibt er aus. Normale Stations- und Änderungsansagen verwenden weiterhin `TextToSpeech.QUEUE_ADD`; dieser Schutz unterbricht oder entfernt keine andere Ansage.

`SpeechDeliveryQueue` ordnet jede eingereihte Ansage einer eindeutigen ID aus Status-ID, Service-Generation und laufender Nummer zu. Späte oder doppelte Callbacks können dadurch keinen neueren Versuch entfernen. Audiofokus bleibt erhalten, bis die letzte wartende Ansage endet. Bei `onError` oder `onStop` wird der zugehörige Besuch nur dann wieder freigegeben und gespeichert, wenn er noch aktuell ist; ein bereits verlassener Halt wird nicht erneut angesagt.

Bei Zielankunft wartet der Service auf eine bereits laufende oder gerade eingereihte Zielansage. Erst deren Abschluss, Fehler-/Stop-Callback oder spätestens ein 15-Sekunden-Timeout beendet den Service; die eigene Zielansage wird nicht sofort durch `stopTracking` abgeschnitten.

Notification und Widget erhalten Linie, nächsten Halt, Ziel, aufgelöste Zeit, Gleis sowie positive oder negative Abweichung zur Planzeit. Die Zeitquelle wird als `GPS beobachtet`, `GPS-Schätzung`, `Manuell`, `API-Echtzeit` oder `Fahrplan` gekennzeichnet. Gleisinformation bleibt aus den API-Feldern; GPS-Prognosen erzeugen keine Gleisdaten.

Der gemeinsame Helfer `trackingPlatform` wählt für Notification, Widget und Stationssprache am Einstieg das Echtzeit-Abfahrtsgleis, sonst das geplante Abfahrtsgleis und zuletzt das Legacy-Feld. Spätere Besuche verwenden entsprechend Ankunftsgleise. Leere oder reine Leerraumwerte werden übersprungen; Bus-RE/RB-Kandidaten erhalten weiterhin keine Bahngleisanzeige.

## Fahrtänderungen und Fortschrittsanzeige

Der [Änderungsmonitor](./trip-changes.md) verarbeitet ausschließlich frische erfolgreiche API-Snapshots. Die erste Antwort beziehungsweise die erste Antwort nach Cache-Wiederanlauf bildet still die Basis. Deduplizierungsmetadaten derselben Fahrt werden im bestehenden Cache gespeichert; der Standort-Tick erzeugt keine Änderungshinweise. Der separate Benachrichtigungskanal öffnet das passende Fahrtdetail; optionale Änderungssprache benötigt zusätzlich globale TTS. `SpeechDeliveryKind` trennt diese Ansagen von Stationsansagen und deren Wiederholungsfreigabe.

Das [Fortschrittsmodell](./trip-progress.md) verwendet den gemeinsamen Besuchscursor für verbleibende Halte und den Android-Balken. Das Ziel wird durch Zeitfortschritt allein nicht als erreicht markiert. Ab API 36 wird `ProgressStyle` verwendet, ältere Geräte erhalten eine normale Fahrtbenachrichtigung. Die Live-Update-Anfrage ist systemabhängig; deaktivierte Sperrbildschirmdetails verhindern sie und verwenden eine allgemeine öffentliche Ersatzanzeige.

Ein als angekommen markierter nicht gestrichener Zielbesuch zeigt null verbleibende Halte und `Am Ziel · Ankunft wird geprüft`, bis der Abschluss separat bestätigt ist. Der Balken bleibt bis dahin unter seinem Maximum. Für die Service-Lebensdauer erfasst `onStartCommand` jede neue `startId` bereits vor der Verarbeitung eines Wegwisch-Intents; `finishService` verwendet anschließend diese aktuelle ID beim Stoppen.

## Stopp-Bedingungen

- GPS-Zielkriterium mit innerem Aufenthalt/niedriger Geschwindigkeit; gegebenenfalls erst nach Abschluss der Zielansage
- Manuell über die Notification-Aktion `Beenden`
- Aktive Fahrt wird ersetzt oder entfernt, zum Beispiel beim Logout

Beim Beenden werden CPU-WakeLock-Erneuerung, Location-Callbacks, Polling und TTS gestoppt und die CPU-Haltung freigegeben. Aktive Status-ID und Cache werden gemeinsam und nur für die passende Fahrt gelöscht.

## Validierung und offene Fragen

- Die im [Main-Review](../entwicklung/main-review-2026-10-06.md) bestätigten G1–G4-Pfade sind im Code durch allgemeinen Sprungschutz, begrenzte Standortregistrierungs-Retries, monotone Beobachtungsreihenfolge und reaktiven TTS-Lifecycle abgesichert. Gezielte reine Regressionen prüfen Besitzwechsel, Retrygrenzen, verspätete Callbacks und Uhrsprünge; den tatsächlich ausgeführten Prüflauf dokumentiert [Tests](../entwicklung/tests.md). TODO: Die Android-Adapter zusätzlich mit behebbarer FLP-Störung, ausgeschalteter Ortung, Uhränderung sowie Enginewechsel und Initfehler auf einem Gerät prüfen.
- Ein Nutzerbericht vom 06.10.2026 meldet teilweise fehlende Ansagen nach Ausschalten des Displays. Im vorherigen Service fehlte ein eigener CPU-WakeLock; daraus folgt kein Nachweis, dass dies die einzige Geräteursache war. Die neue aktive Haltung und die Systemfreigabe müssen mit Standort-/Service-/Audioverlauf, erzwungenem Doze auf einem Testgerät und Samsung-Einstellungen geprüft werden. TODO: Mindestens 30 Minuten Display-aus-Betrieb, gewährte/abgelehnte Ausnahme, Energiesparmodus, GPS-/API-Ausfall, TTS sowie Freigabe aller Stop-/Destroy-Pfade nach der [Testmatrix](../entwicklung/tests.md) auf dem Gerät nachweisen.
- Ein Nutzerbericht zu `1.7.0` meldet bei einer etwa zwei Minuten verfrühten Fahrt eine korrekte Ansage von Maubisstr. und die fehlende Ansage des folgenden Halts Rathaus. Der Screenshot zeigte mehrere `AKTUELL`-Markierungen und unterbrochene Timeline-Segmente. Das Zeitfenster je Zeile erklärt die mehrfachen Markierungen; überlappende Abfahrtsbereiche und die Ansagezustellung werden durch die Änderungen abgesichert. Unklar: Ohne Fix-/Audioverlauf ist die konkrete Ursache der fehlenden Ansage nicht bewiesen.
- Ein Nutzerbericht vom 06.10.2026 zur S28 meldet häufiges Umschalten zwischen `GPS-Schätzung` und `API-Echtzeit` sowie eine Einstiegsansage erst nach der Abfahrt. Die Quellauswertung zeigte zwei logische Lücken: Die Prognose verlangte bei jedem Fix erneut Mindestbewegung und speicherte bisher nur acht Samples, was bei häufigen Updates kein achtsekündiges Fenster zuließ. Am Einstieg blockierte ein vorhandener GPS-Fix den zeitbasierten Hinweis, während spätere Näherungsjitter den alten Annäherungstrigger noch auslösen konnten. Die begrenzte Prognosestabilisierung und der bestätigte Wartehinweis sichern diese Fälle ab. Unklar: Ohne aufgezeichnete Fix-/Audiofolge ist der konkrete Gerätevorgang nicht vollständig rekonstruiert.
- Historische erfolgreiche Prüfläufe und der aktuelle automatisierte Prüfumfang stehen unter [Tests](../entwicklung/tests.md).
- TODO: Den korrigierten kurzen Haltübergang einschließlich Rathaus sowie die S28-Einstiegsansage vor der tatsächlichen Abfahrt erneut auf einer echten Fahrt prüfen. Bei verfrühter Weiterfahrt muss der geordnete Folgehalt rechtzeitig angekündigt werden; nach Beginn der Abfahrtsbewegung darf kein veralteter Einstiegsversuch nachgereicht werden. Zusätzlich eine lange laufende Änderungsansage unmittelbar vor der Abfahrt prüfen: Ein Einstiegsversuch muss bei belegtem Warten rechtzeitig erneut versucht werden oder nach verlassener Wartephase ausbleiben. Weitere Fälle: Tunnel, Vorbeifahrt, Rundfahrten, grobe Standortfreigabe, ausgeschaltetes Display und Neustart. Hier steht kein physisches Testgerät zur Verfügung.
- TODO: Radius- und Hysteresewerte nach diesen Fahrten bewerten; reine Nähe ist kein Nachweis eines Fahrzeughalts.

## Offizielle Quellen

- [Foreground-Service-Typen](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground-Service-Zeitgrenzen](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Standortberechtigungen und Display-aus-Betrieb](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [Geofencing: mögliche Hintergrundverzögerung](https://developer.android.com/develop/sensors-and-location/location/geofencing)
- [LocationRequest: Best-Effort-Vorgaben](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest)
- [Location-Zeitbasis](https://developer.android.com/reference/android/location/Location#getTime())
- [TTS-Initialisierung und Enginewahl](https://developer.android.com/reference/android/speech/tts/TextToSpeech)

## Ungenaue Fixes bei Wiederverankerung

G6 des [Nachreviews](../entwicklung/main-review-2026-10-06.md) ist korrigiert: Jede vom monotonen Clockadapter als neu gelieferte Standortbeobachtung erreicht die Stationsengine, auch bei unbrauchbarer Genauigkeit. Diese Beobachtung unterbricht den transienten Wiederverankerungsbeleg; sie erzeugt keine GPS-Zeitprognose. Alte, zu alte und wiederholte Providerwerte bleiben am Clockadapter ausgeschlossen. Reine Regressionen prüfen 80/150 Meter sowie NaN/unendliche Genauigkeit und die Wiederaufnahme mit einer neuen vollständigen Fixfolge. Android-Service-/FLP-Zustellung wurde damit nicht ausgeführt.

## Verwandte Seiten

- [Check-in](./checkin.md)
- [Fahrterkennung](./ride-recognition.md)
- [Fahrtänderungen](./trip-changes.md)
- [Reisefortschritt](./trip-progress.md)
- [GPS-Zeiten](./gps-zeiten.md)
- [SEV-Ersatzhaltestellen](./sev-haltestellen.md)
- [Settings](./settings.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Widget](./widget.md)
- [StatusDetail](./status-detail.md)
- [Datenmodell](../daten/datenmodell.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Externe Abhängigkeiten](../architektur/externe-abhaengigkeiten.md)
- [Tests](../entwicklung/tests.md)
- [Offene Fragen](../offene-fragen.md)
