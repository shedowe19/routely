# Modul: Check-in

## Zweck

Ermöglicht dem Nutzer, Haltestellen zu suchen, Verbindungen abzufragen und letztendlich in einen Zug, Bus oder eine Tram einzuchecken. Dies ist die Hauptfunktionalität der Routely-App.

## Kontext

Der Check-in Prozess führt den Nutzer schrittweise von der Ortung/Suche bis zur Bestätigung der Fahrt. Die UI ist primär im `CheckInScreen` zu finden, der Teil des zentralen HorizontalPagers ist.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/CheckInScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInSelection.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInDepartureSearch.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInTrackingCompletion.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/components/DepartureSearchTimeSelector.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/components/StatusVisibilitySelector.kt`
- `app/src/main/kotlin/de/traewelling/app/data/model/StatusVisibility.kt`

## Verhalten und Ablauf

Der typische Ablauf eines Check-ins nutzt mehrere API-Endpunkte nacheinander:

1. **Bahnhofsauswahl (Start):**
   - Entweder über die Textsuche (`GET /api/v1/trains/station/autocomplete/{query}`)
   - Oder über die Ortung (`TraewellingRepository.getNearbyStations` → `GET /api/v1/stations` mit den aus dem Standort berechneten Boxgrenzen `min_lat`, `max_lat`, `min_lon`, `max_lon`)
   - `NearbyStationIdentity` dedupliziert ausschließlich belegte Stationsidentität: gleiche gültige interne ID oder bei fehlender ID dieselbe nichtleere UUID. Verschiedene interne IDs bleiben erhalten, auch bei ähnlichem Namen und wenigen Metern Abstand. Unbelegte Einträge werden nicht zusammengelegt.

2. **Abfahrtsauswahl:**
   - Sobald ein Startbahnhof gewählt ist, werden die Abfahrten geladen (`GET /api/v1/station/{id}/departures`).
   - Stationssuche und Abfahrtsliste bieten eine gemeinsame Datum-/Uhrzeitauswahl. `Jetzt` lässt den `when`-Parameter weg; eine ausdrückliche Auswahl wird als UTC-Zeitpunkt mit Zone an `when` übergeben. Damit lassen sich vergessene Fahrten suchen, soweit der Server beziehungsweise sein Datenanbieter sie noch liefert.
   - Die `id` des Bahnhofs (numerisch) muss verwendet werden.
   - Liefert die ausgewählte Abfahrt ein eigenes `station`-Objekt, ist diese tatsächliche Abfahrtsstation für den Einstieg maßgeblich; sie kann vom zuvor gesuchten Bahnhof abweichen.

3. **Zielauswahl (Trip Detail):**
   - Wählt der Nutzer eine Abfahrt, muss der Zielbahnhof bestimmt werden.
   - Dazu wird die gesamte Route der Fahrt geladen (`GET /api/v1/trains/trip` mit `hafasTripId` und `lineName`).
   - Die App zeigt die Liste der kommenden Haltestellen an.
   - Stationsdaten stammen aus `stopover.station`. Start und Ziel nutzen `stationId`, Namen nutzen `stationName`, Kennungen nutzen `stationIdentifier(type)`.
   - Konkrete Halte werden über `matchesStopover` unterschieden, damit mehrere Besuche desselben Bahnhofs auf einer Fahrt nicht verwechselt werden.
   - `resolveCheckInOriginIndex` sucht anhand Station-ID beziehungsweise IBNR und der ausgewählten geplanten oder realen Abfahrtszeit genau einen Einstieg. Ohne Zeitbeleg genügt nur ein insgesamt eindeutiger Stationsbesuch. Ein wiederholter uneindeutiger Einstieg wird nicht durch den ersten Treffer ersetzt.
   - Ziele müssen nach diesem Einstieg liegen, nicht gestrichen sein und Station-ID sowie eine Ankunftszeit besitzen. Fehlt der eindeutige Einstieg oder ein zulässiges Ziel, bleibt der Ablauf mit einer erklärenden Fehlermeldung bei der Auswahl; die Route wird nicht pauschal angeboten.

4. **Der eigentliche Check-in:**
   - Wenn Start, Fahrt und Ziel bekannt sind, wird der Check-in durchgeführt.
   - `POST /api/v1/trains/checkin` mit `CheckInRequest` (Start, Ziel, Fahrt-ID, Reisegrund, etc.).
   - Der Reisegrund wird über das API-Feld `business` gesendet. Die App verwendet `TravelReason.PRIVATE` (`0`) als Standard und erlaubt die Auswahl von `BUSINESS` (`1`) und `COMMUTE` (`2`).
   - Manuelle Verspätungs-Overrides (`manualDeparture`, `manualArrival`), die die API zurückgibt, müssen direkt ins Datenmodell gemerged werden, um UI-Flackern zu vermeiden.

Abfahrten zeigen `direction` als Fahrtrichtung. `DepartureTimePresentation` bevorzugt die parsebare Echtzeit, zeigt eine abweichende Planzeit daneben und kennzeichnet sowohl positive als auch negative `delayMinutes`. Plan 18:00, Echtzeit 17:57 erscheint als 17:57 mit Plan 18:00 und −3 Minuten. Bei fehlender Echtzeit wird die Planzeit angezeigt. Ein Betreiber wird aus `TripDetails.operator` beziehungsweise `CheckinInfo.operator` gelesen; das veraltete `line.operator` wird nicht vorausgesetzt.

Der Check-in überträgt weiterhin numerische interne Station-IDs für `start` und `destination` und den Provider-Identifier für `tripId`. Eine Stopover-ID, IBNR oder Trip-UUID darf diese Werte nicht ersetzen.

Suche, Standortabfrage, Abfahrtswahl und Zielwechsel besitzen eine gemeinsame Auswahlgeneration. Neue Auswahl beziehungsweise Zurücksetzen beendet die alten Ladeaufträge; eine verspätete Antwort darf weder die aktuelle Station noch den Schritt überschreiben. Der Standortcallback aus dem Screen wird vor Übernahme ebenfalls auf diese Generation geprüft. Während laufender Bestätigung wird kein zweiter Check-in gestartet. Ein erfolgreicher alter Auftrag darf die aktive Status-ID nur für den noch passenden atomaren Auth-Snapshot speichern; Sitzung oder Auswahlwechsel verhindern die Übernahme.

## Fahrtvorschläge aus GPS

Eine ausdrücklich gewählte Suchzeit blendet aktuelle GPS-Vorschläge aus und verweigert deren Annahme im ViewModel. Der weiter laufende optionale Erkennungsdienst verändert diese Zeitwahl nicht. `Jetzt` beziehungsweise ein vollständiger Reset macht den normalen Live-Ablauf wieder verfügbar.

Der Stationsschritt enthält die ausdrücklich aktivierbare [Fahrterkennung](./ride-recognition.md). Eine laufende eigene Fahrt pausiert die Suche. Kandidaten bleiben prozesslokal und können mehrdeutig sein; der Nutzer prüft Linie/Richtung. `CheckInViewModel` zeigt nur Kandidaten mit passender `authSessionRevision` und prüft bei Auswahl Fixalter, aktuelle Zugangsgeneration und das Fehlen eines aktiven Check-ins erneut. Ein gültiger Vorschlag übernimmt Einstieg, Abfahrt und bereits geladene Tripdetails; anschließend folgen normale Zielauswahl und manuelle Bestätigung. Die Herkunft der Zielauswahl bleibt erhalten: Zurück nach erkannter Fahrt führt zur Stationssuche/Erkennung und entfernt alte Stations-/Abfahrtsreste; eine manuell gewählte Fahrt führt zur tatsächlich geladenen Abfahrtsliste zurück. CONFIRM-Zurück erhält diese Herkunft. Zurück aus der manuellen Abfahrtsliste erhält die ausgewählte gültige Station als Suchergebnis mit ihrem Namen; fehlende gültige Auswahl leert die Suche. Der Ablauf behauptet damit keinen negativen Suchbefund ohne Anfrage. Regressionen: `CheckInBackNavigationTest` und `CheckInNavigationGuardTest`. Es gibt keinen automatischen `POST /trains/checkin`.

## Zeitfelder und Konflikte

Für einen Halt gelten `effectiveDeparture = departureReal ?: departurePlanned` und `effectiveArrival = arrivalReal ?: arrivalPlanned`. Die Legacy-Felder `departure` und `arrival` sind keine Datenquelle mehr.

`buildCheckInSubmission` hält POST-`departure` und `arrival` an den ausgewählten geplanten Besuchsmarkern fest. Manuelle Eingaben werden einschließlich Zeitzone und zeitlicher Reihenfolge vor dem Erstellen validiert. Anschließend führt `submitCheckIn` genau einen POST aus; nach erfolgreicher Erstellung folgen bei Bedarf die manuellen Istzeiten als Status-PUT. Beide Repository-Aufträge sind an denselben `AuthSession`-Snapshot gebunden.

Ein fehlgeschlagener Zeit-PUT oder lokaler Speicherfehler macht den bereits erstellten Check-in nicht erneut absendbar. Die Erfolgskarte erklärt den Teilerfolg und verweist zur Bearbeitung der vorhandenen Fahrt im Profil. Auch eine akzeptierte POST-2xx-Antwort ohne verwertbare Status-ID endet mit einem Prüfhinweis statt einer zweiten POST-Schaltfläche; ohne belegte ID wird keine lokale Begleitung erfunden.

Ein erfolgreicher Check-in enthält `data.status` und `data.points`. Bei HTTP 409 liest das Repository `data.conflicts`, erzeugt eine `CheckInConflictException` und nennt die betroffenen Linien, Ziele und Status-IDs. Die auslaufenden Felder `message.status_id` und `message.lineName` werden nicht verwendet. Eine leere Konfliktliste führt zu einer allgemeinen Überschneidungsmeldung.

## Manuelle Abfahrt und Ankunft

Die optionalen Zeitfelder im Bestätigungsschritt verwenden `JourneyDateTimeField`: native Android-Datum-/Uhrzeitdialoge, lokale Darstellung mit Gerätezeitzone und 12-/24-Stundenformat. Nur eine vollständig bestätigte Auswahl ändert den Entwurf; Abbruch lässt ihn unverändert. Nicht existierende Sommerzeit-Uhrzeiten werden erklärt, vorhandene Offsets bei doppelter Uhrzeit erhalten. Leeren verwendet wieder die automatische Zeit. ISO-Strings bleiben intern und werden nicht als editierbare T-/Z-Rohfelder gezeigt.

Ein typisierter Storagefehler nach bestätigtem POST erhält die erzeugte Fahrt-ID und den SUCCESS-Zustand; die automatische Zeitnachkorrektur wird bei unprüfbarer lokaler Sitzung ausgesetzt. Ein bereits angenommener Zeit-PUT liefert ebenfalls einen Teilerfolgshinweis mit vorhandenem Status. Weder Wiederherstellung noch Fehlermeldung erzeugen einen zweiten Check-in. Die zusätzlichen Regressionen stehen unter [Tests](../entwicklung/tests.md).

## Start der Fahrtverfolgung

Nach einem erfolgreichen Check-in und einem gegebenenfalls folgenden Zeit-PUT prüft `CheckInViewModel` den **finalen** Status. Die erste parsebare Zeit aus manueller Ankunft, API-Echtzeit und Planankunft entscheidet: Ist die Ankunft bereits erreicht, bleibt die Fahrt in der Historie und startet keine Begleitung. Fehlt jede verwertbare Ankunftszeit, wird ebenfalls kein aktiver Zustand erfunden. Eine noch laufende Fahrt darf nur aktiviert werden, wenn keine andere lokale Fahrt begleitet wird oder dieselbe ID bereits aktiv ist. Die atomare Persistenz prüft zugleich die Auth-Sitzung; ein rückwirkender Eintrag überschreibt keine andere Begleitung.

Die lokale Aktivierung ist auf 15 Sekunden begrenzt; Fehler oder Ablauf ändern nichts am bereits angenommenen Check-in und bieten keinen zweiten POST an. Erfolgskarte und Hinweise unterscheiden gespeicherte Historie, aktivierte und unverändert bestehende Begleitung. `MainActivity` übernimmt den sichtbaren, berechtigungsgeprüften Start des [TripTrackingService](./trip-tracking.md); das ViewModel startet keinen GPS-Foreground-Service aus dem Hintergrund.

## Vergessene Fahrten nachtragen

Die Auswahl `Abfahrten ab` ist bereits vor der Stationswahl sichtbar und bleibt auf der Abfahrtsliste erreichbar, auch bei Ladefehlern oder einer leeren Liste. Datum und Uhrzeit werden mit Material-Dialogen gewählt; die Gerätezeitzone und der UTC-Offset sind erkennbar. Eine Sommerzeitlücke wird nicht still auf eine andere Uhrzeit verschoben: Die Eingabe bleibt zur Korrektur offen. Bei einer doppelt vorkommenden Uhrzeit bleibt ein bereits gewählter Offset erhalten; eine neue Auswahl verwendet den früheren Zeitpunkt. In der Bestätigung ist zusätzlich das tatsächliche Abfahrtsdatum der gewählten Fahrt sichtbar.

Jeder Zeitwechsel beendet die vorige Abfragegeneration und entfernt davon abhängige Fahrt-/Zielauswahlen. Verspätete Antworten dürfen die neue Liste nicht ersetzen. Zurück erhält die Suchzeit; vollständiges Zurücksetzen stellt `Jetzt` wieder her. Planzeiten des konkreten gewählten Haltebesuchs bleiben die POST-Marker; der Suchzeitpunkt ersetzt sie nicht.

Die App setzt kein erfundenes 7-/30-Tage-Limit. Träwelling akzeptiert einen datierten `when`-Parameter; historische Abfahrtssuche hängt vom Datenanbieter ab; bereits gespeicherte Trips können anschließend beim Detailabruf helfen. Eine leere Antwort erklärt diese Grenze und erlaubt eine neue Zeitwahl. Eine ausschließlich lesende Stichprobe am 03.11.2026 lieferte für Berlin Hbf sowohl aktuelle als auch Abfahrten von gestern und vor sieben Tagen sowie passende Fahrtdetails. Das ist kein Nachweis einer garantierten allgemeinen Aufbewahrungsdauer.

Regressionen: `CheckInDepartureSearchTest` prüft UTC-/Kalendertagszuordnung, Sommer-/Winterzeit, verspätete Antworten, Abbruch und Navigation. `CheckInTrackingCompletionTest` prüft abgeschlossene und laufende Fahrten, final korrigierte Ankünfte, aktive Begleitung, fehlende IDs sowie Persistenzfehler und Fristablauf.

## Reisegrund

Im Bestätigungsschritt zeigt `CheckInScreen` eine Chip-Auswahl für den Reisegrund an:

| UI-Label       | API-Wert | Bedeutung                             |
| -------------- | -------- | ------------------------------------- |
| `Privat`       | `0`      | Standardwert für private Fahrten      |
| `Geschäftlich` | `1`      | Dienstfahrten                         |
| `Arbeitsweg`   | `2`      | Weg zwischen Wohnort und Arbeitsplatz |

`CheckInViewModel` hält den Wert im `CheckInUiState.travelReason` und übergibt ihn beim Absenden an `CheckInRequest.business`.

Die Reisegrund-Auswahl nutzt `FilterChip`-Elemente statt eines Dropdowns, damit die drei Optionen direkt sichtbar und auf mobilen Geräten schneller erreichbar sind.

## Sichtbarkeit

Seit der Erweiterung vom 03.11.2026 bietet der Bestätigungsschritt eine gemeinsame Dropdown-Auswahl mit Kurzbeschreibung: Öffentlich (`0`), Nicht gelistet (`1`), Follower (`2`), Privat (`3`), Angemeldete Nutzer (`4`) und Vertraute Personen (`5`). Die Werte entsprechen dem geprüften Upstream-Enum `StatusVisibility`.

Die Vorauswahl bleibt wie bisher Öffentlich (`0`), ist nun aber ausdrücklich sichtbar und änderbar. `CheckInUiState.visibility` wird vom Bestätigungsschritt bis `CheckInRequest.visibility` durchgereicht. Zurück innerhalb der Auswahl erhält den Entwurf, ein vollständiger Reset stellt die ursprüngliche Vorauswahl wieder her. Während der Erstellung ist die Auswahl gesperrt. Ein Weglassen des Feldes würde im geprüften Backend ebenfalls Öffentlich ergeben; die App verspricht deshalb keinen automatisch übernommenen Profilstandard.

Regressionen in `CheckInSubmissionTest` sichern die bisherige Public-Vorauswahl, alle sechs ausdrücklichen Werte, Erhalt beim Zurückgehen, Rücksetzen und Ablehnung unbekannter Werte vor der Erstellung ab. Eine schreibende Live-Prüfung wurde dafür nicht ausgeführt.

Lade-, Fehler- und Empty-States im Check-in verwenden `StateMessage`, um dieselbe visuelle Zustandsdarstellung wie Feed und StatusDetail zu nutzen.

## Abhängigkeiten

- **TraewellingApiService**: Zum Abfragen der nötigen Daten und Absenden des Check-ins.
- **FusedLocationProviderClient**: Wird genutzt, um GPS-Koordinaten für die "In der Nähe" Suche zu generieren.
- **StateMessage**: Einheitliche UI für Lade-, Fehler- und Empty-States.

### Navigation während einer Erstellung

- U5 ist korrigiert: `hasPendingSubmission` umfasst CONFIRM und SUCCESS bei laufender Erstellung beziehungsweise Nachkorrektur. Auswahl-, Toolbar-Zurück- und Resetpfade beachten denselben Zustand. Erkennungsnavigation zeigt einen laufenden Check-in, startet ihn aber nicht neu. Innerhalb dieser geschützten VM-Pfade kann der angenommene POST seine Aktivierung und den manuellen Zeit-PUT zu Ende übernehmen; echte Sitzungswechsel bleiben eine getrennte Grenze. Eine Regression verwendet verzögerte Erstellung und Zeitkorrektur und bestätigt genau einen POST. System-Zurück ist zusätzlich abgesichert: Die sichtbare Check-in-Page navigiert gewöhnliche Auswahlschritte über denselben VM-Zustand; der sichtbare Main-Eintrag blockiert laufende Erstellung/Nachkorrektur auch nach Pager-Tabwechsel. Andere NavHost-Ziele behalten ihre Stacknavigation. Ein noch komponierter Nachbartab darf gewöhnliches Zurück nicht übernehmen. CONFIRM besitzt den Handler schon vor POST; der Callback prüft den aktuellen VM-Zustand und verhindert damit einen schnellen Abbruch vor Recomposition. Android-8–11/API-30-Geräteprüfung bleibt offen.

## Offene Fragen


- U1/U3/D3 des [Main-Reviews](../entwicklung/main-review-2026-10-06.md) sind durch getrennte Plan-/Istzeitaufträge, Verfrühungsanzeige und Identitätsdeduplizierung korrigiert. Regressionen verwenden kontrollierte Antworten; es gab keine schreibenden Live-API-Tests.
- TODO: Zeitkorrektur-Teilerfolg, Erfolgskarte und nahe verschiedene Halte auf dem Gerät prüfen.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Fahrterkennung](./ride-recognition.md)
- [TripTracking](./trip-tracking.md)
- [Externe Schnittstellen](../api/externe-schnittstellen.md)
- [Datenmodell](../daten/datenmodell.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
