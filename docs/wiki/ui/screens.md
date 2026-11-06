# UI: Screens

## Zweck

Übersicht aller Compose-Screens der App.

## Alle Screens

| Screen             | Datei                              | Zweck                                     |
| ------------------ | ---------------------------------- | ----------------------------------------- |
| SetupScreen        | `ui/screens/SetupScreen.kt`        | Login/Token-Eingabe                       |
| FeedScreen         | `ui/screens/FeedScreen.kt`         | Dashboard/Global-Feed                     |
| CheckInScreen      | `ui/screens/CheckInScreen.kt`      | Check-in Flow                             |
| NotificationScreen | `ui/screens/NotificationScreen.kt` | Benachrichtigungen                        |
| ProfileScreen      | `ui/screens/ProfileScreen.kt`      | Eigenes Profil + letzte Fahrten           |
| UserProfileScreen  | `ui/screens/UserProfileScreen.kt`  | Fremdes Profil                            |
| UserSearchScreen   | `ui/screens/UserSearchScreen.kt`   | Benutzer-Suche                            |
| StatusDetailScreen | `ui/screens/StatusDetailScreen.kt` | Status-Detail mit Timeline und Reisegrund |
| SettingsScreen     | `ui/screens/SettingsScreen.kt`     | Theme, GPS, Fahrterkennung, Änderungen, Fortschritt, TTS und Android-Akku-Ausnahmestatus |

## Navigation

Die App nutzt einen HorizontalPager mit 4 Tabs:

- Feed (Home)
- Check-in
- Notifications (mit Badge bei ungelesenen)
- Profil

Die `NavigationBar` liegt auf `surfaceContainer` mit einer feinen `outlineVariant`-Trennlinie.
Ausgewählte Tabs nutzen `primaryContainer` als Indicator sowie Primary-Icon/-Text; das frühere
tonal-elevation-10-dp-Erscheinungsbild entfällt.

Der Feed schaltet `Freunde`/`Global` über den segmentierten `FeedTypeSwitcher` statt über eine
`TabRow` mit Icons. Karten zeigen relative Zeit, Abfahrts-/Ankunftszeiten, Dauer und – sofern
Echtzeitdaten vorliegen – einen Pünktlich-/Verspätungs-/Ausfall-Chip. Beim ersten Laden
erscheinen Skeleton-Karten statt eines Spinners. Die Meldungsliste verwendet dichte Zeilen mit
Trenner, Tagesgruppen (`Heute`, `Gestern`, …) und einem Skeleton beim Erstladen. Der eigene
Profilkopf bündelt Avatar, Handle und die drei Kennzahlen in einer gemeinsamen Fläche; die
Einstellungen sind über das Zahnrad im Profilkopf erreichbar, Abmelden über das zweite Icon.

Zusätzliche Screens werden als Stack navigiert:

- `userProfile/{username}`
- `userSearch`
- `statusDetail/{statusId}`
- `settings` (Einstieg über das eigene Profil)

Der Feature-ViewModel-Store und diese Navigation sind an `AuthSession.revision` gebunden. Rotation erhält den Store derselben Sitzung; Logout oder neue Zugangsgeneration beendet alte Aufträge und baut den Featurezustand sowie die Navigationsfolge neu auf. Auth- und Settings-ViewModel bleiben Activity-bezogen. Details: [Auth](../module/auth.md).

Der sichtbare Main-Eintrag schützt einen laufenden Check-in auch nach Tabwechsel vor System-Zurück; zusätzliche Stackziele behalten ihre normale Zurücknavigation. Nur die sichtbare Check-in-Page übernimmt gewöhnliche Schrittwechsel. Die Herkunft der Zielauswahl bestimmt den Rückweg: GPS-Erkennung zur Stationssuche/Erkennung, manuelle Fahrt zur geladenen Abfahrtsliste. Zurück aus der Abfahrtsliste erhält die bekannte gültige Station als Suchergebnis, statt ohne Anfrage einen falschen Leerzustand zu zeigen. Die Android-freien Navigationsregressionen ersetzen keine API-30-Geräteausführung.

## Benachrichtigungsnavigation

Die Check-in-Erfolgskarte öffnet einen bestätigten Status mit `Fahrt öffnen` direkt im Fahrtdetail. Solange Erstellung oder Zeitkorrektur läuft, bleibt die Schaltfläche gesperrt. Die Jetzt-Aktionen für verifiziert eigene Fahrten stehen anschließend unter `Fahrt bearbeiten`; angenommene Antworten ohne verwertbare ID erzeugen keinen erfundenen Navigationspfad.

Der Meldungstab löst API-Links über `NotificationTargetResolver` gegen den aktuellen Server auf. Gültige `/status/{id}`- und `/@{username}`-Ziele öffnen Fahrtdetail beziehungsweise Profil mit normalem Rückweg zum Meldungstab. HTTPS, Host, Port und ein möglicher Installationspfad müssen zur aktiven Instanz passen. Einstellungen, Events und ungültige Ziele erhalten keine falsche Appnavigation; die Gelesenmarkierung funktioniert davon unabhängig. Die betroffenen Zeilen kennzeichnen ein unterstütztes Ziel durch einen Öffnungspfeil und eine benannte Bedienaktion.

`MainActivity` übersetzt `open_status_id` in einen `NavigationRequest` zum passenden `statusDetail/{statusId}`. `open_recognition` öffnet den Check-in-Tab. Ein bereits abgeschickter CONFIRM-POST beziehungsweise seine laufende SUCCESS-Zeitkorrektur wird dabei nicht zurückgesetzt. Die Requests haben einen Verbrauchs-/Sequenzschlüssel und werden bei kaltem Start sowie `onNewIntent` verarbeitet; die Fahrtbenachrichtigung und Änderungshinweise führen damit zur jeweiligen Fahrt. Ein Request mit optionaler `authSessionRevision` wird nur in derselben aktuellen Zugangsgeneration geöffnet; ein alter Hinweis darf nach Kontowechsel nicht dieselbe numerische Status-ID des neuen Kontos öffnen. Der Opt-in-Bereich der Fahrterkennung steht im Stationsschritt des Check-ins und bietet aktuelle Vorschläge, Pausen-/Fehlerhinweise und Beenden.

## Zustandsdarstellung

### Wiederherstellung lokaler App-Daten

`MainActivity` zeigt vor Anmeldung und Navigation eine modale `StorageRecoveryDialog`-Oberfläche, solange der gemeinsame Speicherzustand oder die frisch gelesene Sitzung noch nicht bestätigt ist. Dadurch wird ein Kaltstart-Lesefehler nicht als Abmeldung dargestellt. Bei einem späteren Fehler bleiben die vorhandenen Navigation und Formulare komponiert; die modale Oberfläche sperrt Bedienung und darunterliegende Semantik, bis der Zugriff wieder sicher ist. Eine Sitzungsbereinigung erfolgt nur bei tatsächlich bestätigter Abmeldung.

`Erneut versuchen` prüft den Speicher; es wiederholt keinen Check-in oder Statuswrite. Nur bestätigte beschädigte Preferences bieten `Lokale App-Daten zurücksetzen` an. Der zusätzliche Dialog erklärt den Verlust lokaler Anmeldung, Einstellungen und aktiver Begleitung; Fahrten auf dem Server bleiben erhalten. Eine Bestätigung ist an genau den aktuellen Fehlerzyklus gebunden und darf nach erfolgreicher Wiederherstellung oder einem neuen Fehler keine Daten zurücksetzen. IO-Fehler erhalten keine Resetaktion.

Die sichtbare Activity startet Begleitung und Erkennung nach erfolgreicher Wiederherstellung erst mit frisch gelesener Konfiguration. Die Bereitschaftsgeneration und das tatsächliche Ende einer alten Serviceinstanz gehören zur Startentscheidung, auch wenn dieselbe aktive Fahrt erhalten bleibt. Fehler bei Berechtigungsmarkern oder Erkennungsaktionen gehen über dieselbe Speicheroberfläche. Die reine Kotlin-Prüfung `StorageRecoveryPresentationTest` sichert Kaltstart, verspätete Sitzungsübernahme, veraltete Resetbestätigungen und Service-Neustartgrenzen; tatsächliche Dialog-/TalkBack-/Rotationbedienung bleibt eine Geräteprüfung. Details: [PreferencesManager](../konfiguration/preferences-manager.md).

Feed, Check-in, StatusDetail, Profile, UserProfile, UserSearch und Notifications nutzen `StateMessage` für Lade-, Fehler- und Empty-States. Dadurch sind die visuellen Zustände über die wichtigsten Screens konsistent.

Fehler beim Refresh, Follow, Speichern oder Löschen werden auch bei bereits vorhandenen Inhalten angezeigt. Automatisches Nachladen weiterer Feed-/Meldungs-/Profilseiten pausiert bei einem Fehler; es wiederholt einen gescheiterten Auftrag nicht allein wegen unveränderter Scrollposition. Reine Profilkarten besitzen ausdrücklich deaktivierte Herz-Aktionen, offene private Folgeanfragen keinen unbelegten Abbruchbutton.

Die Status-Detail-Timeline nutzt für die eigene aktive Fahrt einen gemeinsamen Besuchscursor des Tracking-Service. GPS-Ankunft, Annäherung und ungefähre Fahrplanposition erhalten unterschiedliche Markierungen; die durchgehende Verbindungslinie verwendet denselben Fortschritt wie die Haltepunkte. Einzelheiten stehen unter [StatusDetail](../module/status-detail.md).

Header und Haltzeiten verwenden den gemeinsamen `JourneyTimeResolver` einschließlich frischer lokaler [GPS-Zeiten](../module/gps-zeiten.md). Jede Zeit kennzeichnet beobachtet, geschätzt, manuell, API-Echtzeit oder Fahrplan. GPS-Verlust wechselt die Zeitquelle, ohne für eine etablierte eigene Fahrt einen neuen Uhrzeitcursor zu erfinden. Fremde oder frühere Fahrten erhalten keine aktuellen GPS-Prognosen; das Bearbeitungsformular bleibt ebenfalls ohne GPS-Werte.

Bei GPS-Fortschritt kennzeichnet StatusDetail einen tatsächlich verwendeten nativen `Träwelling-Streckenverlauf` oder ein `SEV-Straßenmodell` getrennt von der Zeitquelle. Eine frische eindeutige Railprojektion kann diesen Hinweis auch ohne GPS-ETA liefern. Nur vorgeladene, abgelaufene oder nicht sicher verwendete Formen erhalten keinen Quellenhinweis; ein leerer Quellenwert wird nicht pauschal als Haltgerade beschriftet.

Die Einstellungs-Card `Begleitung bei ausgeschaltetem Display` liest den Akkuoptimierungsstatus direkt aus Android und aktualisiert ihn bei Rückkehr in die sichtbare Activity. Eine Ausnahme wird ausschließlich über einen ausdrücklichen Nutzertipp auf die Systemfreigabe angefragt. Sie ist kein gespeicherter App-Schalter; Start-/Fallbackverhalten steht unter [Settings](../module/settings.md).

Detail-Compositions besitzen eigene Beobachtungsleases. Activity-Neuanlage erhält den Fachentwurf und abgeschickte Mutationen; aktueller Busyzustand sperrt Verlassen. Erfolgreiche Löschungen bereinigen die aufgenommene Fahrt getrennt vom alten Screen und navigieren nur durch die aktuelle Detail-Composition. Dropdowns und Einstellungsaktionen besitzen zugeordnete Semantiklabels; die praktische TalkBack-/Rotationprüfung bleibt offen.

### Datum und Uhrzeit bearbeiten

`Zeiten erfassen` mit `Abfahrt jetzt` und `Ankunft jetzt` gehört ausschließlich in `Fahrt bearbeiten`. Die Schaltflächen ändern den Entwurf; erst `Speichern` überträgt die Änderung. Abbrechen speichert keine Jetzt-Zeit. Ein separater Hinweis bestätigt die Übernahme beziehungsweise einen Teilerfolg nach geschlossenem Editor, ohne weitere Schnellaktionen auf der Hauptseite.

Die Zeitfelder im Editor und bei der Check-in-Bestätigung nutzen `JourneyDateTimeField`: Ein Antippen öffnet native Android-Datums- und Uhrzeitdialoge. Angezeigt werden Datum, lokale Uhrzeit im eingestellten 12-/24-Stundenformat und die Gerätezeitzone einschließlich des zum Datum gehörenden Offsets. ISO-Werte mit `T` und `Z` bleiben ein internes API-Format. Erst eine vollständige Auswahl beider Dialoge aktualisiert das Formular; Abbruch und eine inzwischen ersetzte Eingabe verändern den bisherigen Wert nicht. `Automatische Zeit verwenden` entfernt die manuelle Korrektur. Sommerzeitlücken werden erklärt statt still verschoben; bei einer unveränderten doppelten Herbstuhrzeit bleibt der vorhandene Offset erhalten. Die Android-freien `JourneyDateTimePresentationTest`-Fälle sichern Anzeige und Zeitauflösung; tatsächliche native Dialog-/Tastatur-/TalkBack-Bedienung bleibt eine Geräteprüfung.

## Verwandte Seiten

- [Komponenten](./komponenten.md)
- [Settings](../module/settings.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Reisefortschritt](../module/trip-progress.md)
- [StatusDetail](../module/status-detail.md)
