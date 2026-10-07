# Modul: Check-in

## Zweck

Ermöglicht dem Nutzer, Haltestellen zu suchen, Verbindungen abzufragen und letztendlich in einen Zug, Bus oder eine Tram einzuchecken. Dies ist die Hauptfunktionalität der Routely-App.

## Kontext

Der Check-in Prozess führt den Nutzer schrittweise von der Ortung/Suche bis zur Bestätigung der Fahrt. Die UI ist primär im `CheckInScreen` zu finden, der Teil des zentralen HorizontalPagers ist.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/CheckInScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInSelection.kt`

## Verhalten und Ablauf

Der typische Ablauf eines Check-ins nutzt mehrere API-Endpunkte nacheinander:

1. **Bahnhofsauswahl (Start):**
   - Entweder über die Textsuche (`GET /api/v1/trains/station/autocomplete/{query}`)
   - Oder über die Ortung (`TraewellingRepository.getNearbyStations` → `GET /api/v1/stations` mit den aus dem Standort berechneten Boxgrenzen `min_lat`, `max_lat`, `min_lon`, `max_lon`)
   - `NearbyStationIdentity` dedupliziert ausschließlich belegte Stationsidentität: gleiche gültige interne ID oder bei fehlender ID dieselbe nichtleere UUID. Verschiedene interne IDs bleiben erhalten, auch bei ähnlichem Namen und wenigen Metern Abstand. Unbelegte Einträge werden nicht zusammengelegt.

2. **Abfahrtsauswahl:**
   - Sobald ein Startbahnhof gewählt ist, werden die Abfahrten geladen (`GET /api/v1/station/{id}/departures`).
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

Der Stationsschritt enthält die ausdrücklich aktivierbare [Fahrterkennung](./ride-recognition.md). Eine laufende eigene Fahrt pausiert die Suche. Kandidaten bleiben prozesslokal und können mehrdeutig sein; der Nutzer prüft Linie/Richtung. `CheckInViewModel` zeigt nur Kandidaten mit passender `authSessionRevision` und prüft bei Auswahl Fixalter, aktuelle Zugangsgeneration und das Fehlen eines aktiven Check-ins erneut. Ein gültiger Vorschlag übernimmt Einstieg, Abfahrt und bereits geladene Tripdetails; anschließend folgen normale Zielauswahl und manuelle Bestätigung. Die Herkunft der Zielauswahl bleibt erhalten: Zurück nach erkannter Fahrt führt zur Stationssuche/Erkennung und entfernt alte Stations-/Abfahrtsreste; eine manuell gewählte Fahrt führt zur tatsächlich geladenen Abfahrtsliste zurück. CONFIRM-Zurück erhält diese Herkunft. Zurück aus der manuellen Abfahrtsliste erhält die ausgewählte gültige Station als Suchergebnis mit ihrem Namen; fehlende gültige Auswahl leert die Suche. Der Ablauf behauptet damit keinen negativen Suchbefund ohne Anfrage. Regressionen: `CheckInBackNavigationTest` und `CheckInNavigationGuardTest`. Es gibt keinen automatischen `POST /trains/checkin`.

## Zeitfelder und Konflikte

Für einen Halt gelten `effectiveDeparture = departureReal ?: departurePlanned` und `effectiveArrival = arrivalReal ?: arrivalPlanned`. Die Legacy-Felder `departure` und `arrival` sind keine Datenquelle mehr.

`buildCheckInSubmission` hält POST-`departure` und `arrival` an den ausgewählten geplanten Besuchsmarkern fest. Manuelle Eingaben werden einschließlich Zeitzone und zeitlicher Reihenfolge vor dem Erstellen validiert. Anschließend führt `submitCheckIn` genau einen POST aus; nach erfolgreicher Erstellung folgen bei Bedarf die manuellen Istzeiten als Status-PUT. Beide Repository-Aufträge sind an denselben `AuthSession`-Snapshot gebunden.

Ein fehlgeschlagener Zeit-PUT oder lokaler Speicherfehler macht den bereits erstellten Check-in nicht erneut absendbar. Die Erfolgskarte erklärt den Teilerfolg und verweist zur Bearbeitung der vorhandenen Fahrt im Profil. Auch eine akzeptierte POST-2xx-Antwort ohne verwertbare Status-ID endet mit einem Prüfhinweis statt einer zweiten POST-Schaltfläche; ohne belegte ID wird keine lokale Begleitung erfunden.

Ein erfolgreicher Check-in enthält `data.status` und `data.points`. Bei HTTP 409 liest das Repository `data.conflicts`, erzeugt eine `CheckInConflictException` und nennt die betroffenen Linien, Ziele und Status-IDs. Die auslaufenden Felder `message.status_id` und `message.lineName` werden nicht verwendet. Eine leere Konfliktliste führt zu einer allgemeinen Überschneidungsmeldung.

## Start der Fahrtverfolgung

Nach einem erfolgreichen Check-in speichert `CheckInViewModel` die aktive Status-ID. `MainActivity` übernimmt den sichtbaren, berechtigungsgeprüften Start des [TripTrackingService](./trip-tracking.md); das ViewModel startet keinen GPS-Foreground-Service aus dem Hintergrund.

## Reisegrund

Im Bestätigungsschritt zeigt `CheckInScreen` eine Chip-Auswahl für den Reisegrund an:

| UI-Label       | API-Wert | Bedeutung                             |
| -------------- | -------- | ------------------------------------- |
| `Privat`       | `0`      | Standardwert für private Fahrten      |
| `Geschäftlich` | `1`      | Dienstfahrten                         |
| `Arbeitsweg`   | `2`      | Weg zwischen Wohnort und Arbeitsplatz |

`CheckInViewModel` hält den Wert im `CheckInUiState.travelReason` und übergibt ihn beim Absenden an `CheckInRequest.business`.

Die Reisegrund-Auswahl nutzt `FilterChip`-Elemente statt eines Dropdowns, damit die drei Optionen direkt sichtbar und auf mobilen Geräten schneller erreichbar sind.

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

## Flutter-Umsetzung

`flutter/lib/features/checkin/` erhält die geplanten Besuchsmarker für POST, separate manuelle PUT-Korrektur, Konflikte und Punkte. Angenommene Erstellung mit unbekannter ID oder späterem Teilfehler darf keinen zweiten POST auslösen. Sichtbare Zurück-/Tabnavigation bleibt während einer Mutation gesperrt. Weitere Details stehen in der [Flutter-Architektur](../architektur/flutter-migration.md). Die bisherigen Kotlin-Verträge bleiben die Verhaltensreferenz.

Wenn ein Check-in nach HTTP-2xx bereits angenommen sein kann, aber Antwortbody, Nachkorrektur oder Folgeabruf scheitern, darf der Erstellungs-POST nicht erneut gesendet werden. `AcceptedMutationException` unterscheidet diese Unsicherheit auch bei Body-Timeout, Verbindungsabbruch und überschrittener Antwortgröße von einer eindeutig abgelehnten Mutation. Der bestätigte beziehungsweise unklare Erfolg bleibt an die ursprüngliche Sitzung/Auswahl gebunden.

Die native Flutter-Näheresuche wartet auf einen frischen Standort statt ungeprüft einen Providercache zu übernehmen. Android verwendet monotone Fixzeit mit maximal 30 Sekunden Alter; iOS prüft 0 bis 30 Sekunden Alter und verwirft zukünftige Fixes. Eine neue Android-Vertragsregression deckt veraltete und zukünftige Cachewerte ab; sie wurde zusammen mit den fünf weiteren Tracking-Vertragsregressionen im ersten GitHub-CI-Lauf erfolgreich ausgeführt. Der [Flutter-Prüfstand](../entwicklung/flutter.md#prüfstand-vom-07102026) trennt diesen Nachweis vom danach gescheiterten Lint und der nachfolgenden Berechtigungsrennen-Korrektur.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Fahrterkennung](./ride-recognition.md)
- [TripTracking](./trip-tracking.md)
- [Externe Schnittstellen](../api/externe-schnittstellen.md)
- [Datenmodell](../daten/datenmodell.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
