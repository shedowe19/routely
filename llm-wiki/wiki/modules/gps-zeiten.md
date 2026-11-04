# GPS-Zeiten

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite erklärt, wie aus GPS-Beobachtungen lokale Ankunfts- und
Abfahrtszeiten werden und welche Quelle die Oberfläche schließlich anzeigt.

## Quellenreihenfolge

`JourneyTimeResolver.resolve` wählt pro Halt und Ereignis:

1. **GPS beobachtet** — die Bewegung hat den Halt tatsächlich erreicht oder
   verlassen.
2. **GPS geschätzt** — lokale Prognose aus Fahrplanoffset und beobachteter
   Fahrt.
3. **Manuell** — `manualDeparture`/`manualArrival` aus Träwelling.
4. **API-Echtzeit** — `when`/`departure` vom Server.
5. **Fahrplan** — `plannedWhen`/`plannedDeparture`.

Die Reihenfolge ist in `README.md:11` und in
`service/JourneyTimeResolver.kt:67-89` belegt.

## Beobachtung

`GpsJourneyTimeEstimator.update` verarbeitet jeden akzeptierten Fix:

- Ein Ereignis gilt als beobachtet, wenn der Zug den Halt mit ausreichender
  Sicherheit erreicht bzw. verlassen hat.
- Ankunft und Abfahrt werden getrennt behandelt.
- Ein Fix älter als 30 s darf keine neue Prognose mehr stützen; die Anzeige
  fällt dann auf die nächste Quelle zurück.
- Ein Wanduhrsprung setzt den Schätzer zurück.

## Prognose

- Die Prognose basiert auf dem Fahrplanoffset und der beobachteten Bewegung,
  nicht auf einer freien Interpolation.
- Sie ist an den konkreten Besuch gebunden (`visitKey`, UUID oder
  Station plus Planzeiten). Eine Prognose für einen anderen Halt wird
  verworfen.
- Kurvengeometrie ist Pflicht, wo sie verfügbar ist: Eine gerade Sehne zwischen
  zwei Halten ist kein Beleg für den tatsächlichen Weg. Bei SEV-Abschnitten
  muss eine verifizierte Straßenroute vorliegen.
- Endpunkte müssen innerhalb der zulässigen Distanz einrasten (250 m bei
  nativer Geometrie, 150 m bei OSRM).

## Warum GPS Vorrang hat

Manuelle Träwelling-Korrekturen sind beabsichtigte Nutzereingaben, aber sie
können veraltet sein. Eine frische, eindeutig zugeordnete GPS-Beobachtung ist
näher an der Realität. Deshalb steht sie vor der manuellen Korrektur. Fehlt
eine nutzbare GPS-Zeit, gewinnt die manuelle Korrektur vor API und Fahrplan.

## Anzeige

- Abweichungen: geplante Zeit durchgestrichen, tatsächliche Zeit hervorgehoben;
  rot bei Verspätung, grün bei Pünktlichkeit/Verfrühung.
- `StopRealtimeInfo` trennt Abrufzeit (`fetchedAtMillis`) von Providerzeit
  (`providerUpdatedAtMillis`). Ohne Providerzeit steht „Aktualität unbekannt“.
- Ein reiner Uhr-Tick verlängert keine abgelaufene Prognose.

## Grenzen

- Ohne GPS (Berechtigung aus, Tunnel, kein Empfang) bleibt der Cursor stehen,
  statt planmäßig zu springen.
- Im Fahrplanmodus kann das Ziel nie automatisch als erreicht gelten.
- GPS-Beobachtungen werden nicht persistiert; nur Routenmetadaten und
  Besuchsfortschritt liegen in `trip_tracking_state`.
- Die App ersetzt keine amtliche Fahrgastinformation.

## Tests

- `GpsJourneyTimeEstimatorTest` — Offset, Bewegungsfenster, Ablauf.
- `GpsDepartureObservationTest`, `GpsOrderedDepartureRegressionTest` —
  Reihenfolge und Regressionen.
- `GpsGeometryEndpointTest`, `GpsRoadRouteValidationTest`,
  `GpsRoadConsensusTest`, `NativeRailGpsTest` — Geometrie- und
  Konsistenzprüfungen.
- `JourneyTimeResolverTest` — Quellenreihenfolge und Fallbacks.
- `TunnelGpsRecoveryTest` — Verhalten nach Signalverlust.

## Risiken

- Die Qualitätsgrenzen sind konservativ; bei schlechtem Empfang bleiben
  Anzeigen länger auf API/Fahrplan.
- Eine falsch gebundene Geometrie würde den Fortschritt verfälschen. Deshalb
  werden mehrdeutige Zuordnungen verworfen, nicht geraten.

## Verwandte Seiten

- [Tracking-Service](tracking-service.md)
- [Streckenverläufe](streckenverlaeufe.md)
- [Live-Datenquellen](../api/live-datenquellen.md)
- [Index](../index.md)
