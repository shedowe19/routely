# Flutter-Migration

## Stand und Einstieg

Der Migrationsstand vom 07.10.2026 führt die plattformübergreifende Anwendung unter `flutter/` ein. Ausgangspunkt ist der bisherige Main-Stand `8ecd87b1727d7a95d6fb09d40bb8541ece0c4695`. Die Kotlin-Anwendung unter `app/` bleibt als überprüfbare Verhaltensreferenz mit ihren Regressionen erhalten. Der manuelle Android-Releaseworkflow baut ab diesem Stand die Flutter-Anwendung.

Flutter ist auf **3.47.6** festgelegt; `pubspec.lock` bindet die tatsächlich aufgelösten Abhängigkeiten. Gemeinsame Dart-Oberflächen, API-Verträge und Reiseberechnung werden durch kleine native Android-/iOS-Hosts ergänzt. Ein erfolgreicher Build ersetzt keine Fahrt auf einem echten Gerät. Vorhandene Buildziele und tatsächlich ausgeführte Nachweise sind unter [Prüfstand](../entwicklung/flutter.md#prüfstand-vom-07102026) getrennt dokumentiert.

## Funktionsvergleich

| Bereich | Flutter-Umsetzung | Plattformgrenzen |
| --- | --- | --- |
| Anmeldung | Eigener HTTPS-Träwelling-Server, Tokenprüfung, gesicherter Sitzungsdatensatz, Logout | Browser benötigt HTTPS/Localhost und die CORS-Freigabe des ausgewählten API-Servers |
| Feeds | Freunde/Global, Pagination, Aktualisieren, bestätigte Likes und lokale Offline-Ansicht | Browsercache bleibt im Arbeitsspeicher; Tokens liegen im plattformspezifischen sicheren Speicher |
| Profile und Community | Eigenes/fremdes Profil, Verlauf, Statistik, Nutzersuche, Folgen/Anfrage | Serverseitige Sichtbarkeit bleibt maßgeblich |
| Meldungen | Unread-Zähler, Pagination, Einzel-/Alle-gelesen, interne Links | Hintergrundzustellung sozialer Meldungen wird nicht als Pushdienst behauptet |
| Check-in | Suche/Nähe, Zeitpunkt, Verkehrsmittel, Einstieg, konkreter Zielbesuch, Grund/Text, manuelle Zeiten, Konflikte/Punkte | Eine angenommene Erstellung darf nach Teilfehlern nicht erneut gesendet werden |
| Fahrt-Details | Status/Stopovers, Bearbeitung/Löschen, Zeitenquellen, ein aktueller/nächster Halt, durchgehende Timeline, Ausfälle, SEV-Wegbeschreibung | Fremde Fahrten werden nicht zur eigenen Begleitung aktiviert |
| GPS und Prognose | Portierte Besuchslogik, monotone Standortzeit, Tunnelwiederaufnahme, beobachtete/geschätzte Zeiten, API-Rückfall | Uhrzeit allein beweist weder einen GPS-Halt noch die Zielankunft |
| Strecken | Echte Träwelling-Bahn-/Tram-Polylines, geordnete Kurvenprüfung, OSRM-Straßenalternativen und gemeinsamer Restweg | Unbestätigte SEV-Bushalte dürfen keine Straßenprognose legitimieren |
| SEV | Öffentliche Bahnhofskarten, Richtung, Berliner Datum, begrenzter Cache, vorsichtige Koordinatenauflösung | Mehrdeutige/veraltete Angaben bleiben Hinweise ohne behaupteten Busstandort |
| Ansagen und Änderungen | Priorisierte Sprachausgabe mit bestätigter Zustellung, aktive Änderungsmeldung unabhängig von TTS | Stimmen und Sprach-Engines unterscheiden sich nach Gerät |
| Fahrterkennung | Präzise lokale Bewegung, begrenzte Stations-/Abfahrtsuche, zeitgebundene Vorschläge | Android/iOS: sichtbarer bzw. erlaubter Hintergrund-Standort; Check-in braucht immer eine Bestätigung |
| Live-Fortschritt | Android-Notification/AppWidget; iOS Live Activity/WidgetKit-Erweiterung | Signierte Apple-App-Groups für Widgets; Apples Live-Activity-Laufzeitgrenzen |
| Design/Einstellungen | Indigo/Lila, Teal/Amber, Verkehrsfarben, runde Karten, Hell/Dunkel/AMOLED/System, Ansageradius und Stimmen | Layout passt sich Bildschirm und Textgröße an |
| Weitere Geräte | Web, Windows, macOS und Linux mit derselben Oberfläche und Vordergrundbegleitung | Native Sperrbildschirm- und Hintergrunddienste sind Android/iOS vorbehalten |

Die vorhandene PKCE-Hilfslogik war in der bisherigen Anmeldung nicht als Benutzerfluss angebunden. Die Migration erfindet deshalb keinen OAuth-Login als bereits vorhandene Funktion. Apple Watch, CarPlay und Android Auto sind keine impliziten Flutter-Zielplattformen dieser Migration.

## Schichten und Eigentum

- `flutter/lib/data/`: unveränderliche JSON-Modelle, HTTPS-API, gesicherte Sitzungen, Mutation und Cache.
- `flutter/lib/features/`: sessiongebundene Controller und Screens für alle bisherigen Oberflächen.
- `flutter/lib/tracking/`: deterministische Reise-/GPS-/Geometrie-/SEV-/Änderungsberechnung ohne Plattformaufrufe.
- `flutter/lib/runtime/`: sichtbarer Laufzeitadapter und Hintergrund-Orchestrierung; API-Antworten bleiben Providerdaten, GPS-Zeiten bleiben reine Anzeige.
- `flutter/lib/recognition/`: bestätigungspflichtige Erkennung, begrenzte Discovery und generationgebundene Vorschläge.
- `flutter/lib/platform/native_trip_bridge.dart`: `routely/tracking` und `routely/tracking_events`.
- `flutter/android/`: Standortdienst, Wake-Lock, Mitteilungen, AppWidget und Altinstallationsimport.
- `flutter/ios/`: CoreLocation, Hintergrund-Engine, Sprachausgabe, ActivityKit und ein WidgetKit-Xcode-Target mit Embed-Phase für `RoutelyWidget.appex`.

Der native Host startet den Dart-Einstieg `trackingMain`. Ausschließlich diese Hintergrund-Engine erhält GPS-Fixes; die sichtbare Engine erhält bereits ausgewertete Snapshots. Browser/Desktop verwenden dieselbe Berechnung ausschließlich im Vordergrund. Keine doppelte GPS-Verarbeitung zwischen zwei Flutter-Engines.

## Sitzungen und Lebenszyklus

Die Befehlsidentität besteht aus `sessionRevision`, `statusId` und einer steigenden `generation`. Start, Stop, Veröffentlichung und Notification-Aktionen prüfen diese Identität. Native Generationstombstones bleiben nach dem Stop erhalten, damit eine verspätete Startanforderung die alte Fahrt nicht erneut aktiviert.

Server, Token, vollständiger Benutzer, Sitzungsgeneration und aktive Status-ID liegen in einem sicheren Datensatz. Die sichtbare Engine ist der einzige Schreiber. Die Hintergrund-Engine öffnet `AppStore` schreibgeschützt und prüft die gespeicherte Generation vor und nach API-Aufrufen. Der Datensatz hält außerdem höchstens 64 statusbezogene Revisionsstempel für bestätigte Bearbeitung, Löschung und Entwertung; Likes ändern diesen Fahrtstempel nicht. Ein Auswahlwechsel während eines asynchronen sicheren Schreibens wird auch nach dessen Abschluss erneut geprüft; bei Entwertung stellt dieselbe Schreibwarteschlange den aktuellen Sitzungsdatensatz wieder her. Einstellungen werden zwischen Engines neu geladen. Löschen, Logout und Zielkorrektur müssen auch laufende Ansagen, API-Antworten und alte Widgets entwerten.

Ein gescheiterter oder durch Logout/neuen Start überholter nativer Trackingstart stoppt ausschließlich die Generation seines eigenen Startversuchs. Die aktive Auswahl wird nur bei weiterhin derselben Sitzung und Operation zurückgesetzt; der vorherige native Besitzer wird nur nach Konfigurationsabgleich übernommen. Ein Fehler beim ersten Snapshot nach bestätigtem Start beendet den Dienst nicht, sondern zeigt einen Hinweis zum erneuten Laden der Anzeige.

Der Erkennungs-Hintergrundworker gleicht verspätete Konfigurationsevents mit dem aktuellen sicheren Datensatz und nativen Besitzer ab. Bei Sitzungs-/Consententzug oder ungültigem Bootstrap veröffentlicht er einen terminalen Snapshot ausschließlich für seine eigene Identität und wartet auf die Freigabe; er verwendet keinen im Hintergrund abgelehnten sichtbaren Stop-Befehl. Ein neuer Besitzer wird dadurch nicht beendet.

Tracking und Fahrterkennung teilen in jeder Engine einen einzigen nativen Ereignisstrom. Verspätete Konfigurationen können keinen neueren Besitzer zurücksetzen. Beim Wiederöffnen gleicht die Oberfläche native Stop-Markierungen anhand von Sitzung, Fahrt und Generation ab: echter Stop oder Abschluss entfernt die aktive Fahrt, während Timeout und Laufzeitfehler einen sichtbaren Fortsetzen-Einstieg erlauben. Ein terminaler Dart-Abbruch gibt nur seinen eigenen nativen Dienst frei.

Bootstrap wartet den begrenzten Altinstallationsimport ab, bevor eine neue Anmeldung möglich ist; gespeicherte Anmeldungen werden zusätzlich geprüft. Abmeldung bleibt bei einem fehlerhaften oder hängenden Sprachdienst möglich. Alte Logout- und Navigationsabschlüsse sind an ihre ursprüngliche Sitzung bzw. Detailroute gebunden. Eine Sprachvorschau darf nach Kontowechsel oder Verlassen der Einstellungen nicht mehr beginnen.

Die Fahrterkennung durchsucht höchstens zwei nahe Stationen und sechs deduplizierte Abfahrten pro Station, einschließlich eines fünfminütigen Rückblicks. Ihre gemeinsame Discovery-Grenze beträgt 45 Sekunden; einzelne Ausfälle verwerfen nutzbare Teilergebnisse nicht. Vorschläge und Benachrichtigungsaktionen gelten ausschließlich für die aktuelle Sitzung und Erkennungsgeneration. Ein Uhrwechsel verwirft alte Beobachtungen und Suchergebnisse.

Progress-Checkpoints enthalten konkrete Besuche und zugestellte Ansageschlüssel. GPS-Beobachtungen und Forecasts werden nicht als Verlauf gespeichert. Routing-HTTP enthält Stations-/bestätigte Bushaltkoordinaten, niemals laufende Nutzer-Fixes oder Träwelling-Zugangsdaten. Die manuelle Näheresuche und die ausdrücklich aktivierte Fahrterkennung übermitteln standortabgeleitete Bounding-Box-Grenzen für nahe Stationen an den gewählten Träwelling-Server. Daraus ist die verwendete Position ableitbar; die laufende Reiseberechnung überträgt keine Standortfixes. Der iOS-Standortzwecktext benennt diese Übermittlung ausdrücklich.

## Android und iOS

Android behält `de.traewelling.app`, Mindestversion 26 und die vorhandene Release-Signierung. compileSdk und targetSdk der Flutter-App sind 36; Angaben zu targetSdk 34 in älteren Modultexten beschreiben den erhaltenen Kotlin-Ausgangsstand. Debug benutzt die eigene `.debug`-Identität. Der Import liest `datastore/traewelling_prefs.preferences_pb` einmalig, validiert die Anmeldung und löscht alte Credentials erst nach erfolgreichem Commit. Fehlgeschlagener Import lässt die manuelle Anmeldung zu.

Die native einmalige Näheresuche akzeptiert höchstens 30 Sekunden alte Fixes; Android prüft die monotone Providerzeit und iOS verwirft auch zukünftige Zeitstempel. Ein alter Providercache ersetzt keinen frischen Ortungsversuch.

Android verwendet einen explizit aus der sichtbaren App gestarteten Vordergrunddienst. Standort, Wake-Lock und Engine werden beim Stop/Timeout freigegeben. Eine Akku-Ausnahme wird nur über eine bewusste Systemeinstellungsaktion angefordert; sie behauptet keine Umgehung der Betriebssystemregeln.

iOS benötigt mindestens 16.1 und verwendet CoreLocation-Hintergrundmodus sowie ActivityKit/WidgetKit. Die Fahrterkennung verlangt präzisen Standort und eine echte `authorizedAlways`-Hintergrundfreigabe. Die Anfrage startet bei unentschiedenem Recht direkt den Always-Ablauf und wartet bei einem Upgrade von When-in-use auf den Systemcallback. Vertagt iOS die Entscheidung ohne Callback, liefert der Adapter nach 30 Sekunden die tatsächliche Freigabe zurück; fehlendes Recht bleibt ein sichtbarer Fehler und wird nicht als gewährt angenommen. Das Widget ist ein echtes Xcode-Target mit Embed-Phase, geteiltem Activity-Schema und App-Group `group.de.traewelling.app`. Signieren/Provisionieren erfordert die Apple-Konfiguration des Eigentümers. Erzwungenes Beenden, entzogene Berechtigungen und iOS-Suspendierung können die Begleitung beenden; ein Dart-Timer ist keine unbegrenzte Hintergrundgarantie. Die Live Activity hat Apples begrenzte Laufzeit.

## Verwandte Seiten

- [Migrationsentscheidung](../entscheidungen/2026-10-07-flutter-migration.md)
- [Flutter entwickeln und prüfen](../entwicklung/flutter.md)
- [API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [SEV-Haltestellen](../module/sev-haltestellen.md)
- [Offene Fragen](../offene-fragen.md)
