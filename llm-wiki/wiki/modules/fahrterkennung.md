# Fahrterkennung

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Die Fahrterkennung schlägt eine Fahrt vor, die der Nutzer möglicherweise
gerade antritt. Sie ist ausdrücklich ein Vorschlagssystem und startet kein
Tracking von selbst.

## Ablauf

1. Nutzer startet die Erkennung im Check-in-Bereich oder in den Einstellungen.
2. `RideRecognitionService` startet einen sichtbaren Standort-Foreground-
   Service und prüft danach die Voraussetzungen:
   - `ride_recognition_enabled` ist an,
   - ein Access-Token existiert,
   - `active_status_id` ist leer (keine Fahrt läuft bereits).
3. Der Dienst sammelt Standortfixes (Callback alle 6 s, mindestens 3 s) und
   sucht alle 90 s nach Kandidaten.
4. `RideRecognitionEngine` prüft Bewegung und Nähe zur Einstiegshaltestelle.
5. Treffer erscheinen als Vorschläge; der Nutzer entscheidet.

## Matching-Kriterien

Ein Vorschlag braucht:

- mindestens drei verlässliche Fixes (Genauigkeit ≤ 65 m),
- ein Beobachtungsfenster von mindestens 8 s innerhalb von 300 m plus
  Genauigkeit um die Einstiegshaltestelle,
- gerichteten Fortschritt von mindestens `max(80 m, 4 × Genauigkeit)`,
- Geschwindigkeit 2,8…95 m/s,
- Querabweichung ≤ `max(180 m, 3 × Genauigkeit)`,
- monotone Projektion auf die Route,
- Abfahrtsfenster −2 min…+8 min,
- die Ankunft am nächsten Halt darf nicht mehr als 5 min überschritten sein.

Stationäre, zitternde, gegenläufige oder rechtwinklige Bewegung wird
verworfen. Ein Sprung/Teleport ist kein Treffer.

## Lebenszyklus und Grenzen

- Kein Wake Lock. Der Dienst pollt alle 90 s und endet bei Verlust von
  Berechtigung, Standortanbieter oder Einstellung.
- Zustands- und Sessionwächter: `sessionId`, `observationRevision`,
  `ownedAuthSession`, `stopped`. Ein Uhrsprung leert Engine, Cache und Suche.
- Caches: 24 Fixes/120 s, 12 Routen/5 min, 12 Fahrtdetails/5 min.
- Mehrere nicht unterscheidbare Linien bleiben getrennte Kandidaten. Es gibt
  keine Fahrzeugidentifikation und keine Routenform-Inferenz.
- Discovery-Timeout 45 s; Fehler werden als Fehlschlag oder gültige leere
  Menge unterschieden (`RideDiscoveryRequests`).

## Schnittstellen

- Aktionen: `de.traewelling.app.STOP_RIDE_RECOGNITION`; Extras
  `extra_auth_session_revision`, `open_ride_recognition`.
- Notification-Kanal `ride_recognition` (LOW), ID 710.
- In-Process: `RideRecognitionService.state: StateFlow<RideRecognitionState>`.
- `CheckInViewModel` sammelt den Zustand und bietet „übernehmen“ an.

## Tests

`RideRecognitionEngineTest` und `RideDiscoveryRequestsTest` decken
Bewegungsfenster, Ablehnungen, Cache-Grenzen und Teilerfolge ab. Der
Service-Lebenszyklus und die Notification sind nicht instrumentiert getestet.

## Risiken

- Ohne Wake Lock kann die Erkennung bei tiefem Schlaf verzögert sein; das ist
  bewusst, um Akku zu sparen.
- Die Kriterien sind konservativ. Seltene Fälle (sehr kurze Fahrten, starke
  GPS-Störung) können ausbleiben.
- Ein Vorschlag ist keine Bestätigung. Der Nutzer muss ihn aktiv übernehmen.

## Verwandte Seiten

- [Check-in](checkin.md)
- [Tracking-Service](tracking-service.md)
- [Offene Fragen](../questions/offene-fragen.md)
- [Index](../index.md)
