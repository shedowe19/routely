# Offene Fragen

## Zweck

Sammlung von offenen Punkten, Unsicherheiten und Annahmen im Projekt.

## Offene Punkte

- TODO: Bei Bedarf die am 05.10.2026 erfolgreiche [Live-GET-Prüfung](./entwicklung/tests.md) als wiederholbare Integrationstests einrichten. Falls echte API-Tokens nötig sind, müssen diese lokal und nicht versioniert bereitgestellt werden.
- TODO: Neue Träwelling-Changelog-Einträge regelmäßig mit dem tatsächlich eingesetzten Routen- und Ressourcenvertrag abgleichen. Der letzte dokumentierte Audit verwendet Upstream `develop`-Commit `4d602796da8409017314cc771b1127d169155f02`.
- TODO: Nach dem Nutzerbericht zu `1.7.0` den Übergang Maubisstraße → Rathaus mit dem korrigierten [GPS-Stationsalarm](./module/trip-tracking.md) erneut auf einer echten Fahrt prüfen. Unklar: Die genaue Ursache der fehlenden Rathaus-Ansage ist ohne Standort-/Audioverlauf nicht bewiesen. Weitere Prüfungen umfassen Tunnel, nahe Halte, Vorbeifahrt und Rundfahrten; Luftlinie ist keine genaue Ankunftsprognose.
- TODO: Auf Android-Geräten präzise/grobe/entzogene Freigabe, ausgeschaltete Ortung, Display-aus-Betrieb, `START_STICKY`, Wiederanlauf mit Cache und Audiofokus prüfen. Hier steht kein physisches Testgerät zur Verfügung; der Fahrplan-Rückfall beendet die Fahrt bewusst nicht automatisch.
- TODO: Die [Status-Timeline](./module/status-detail.md) mit großer Schrift und mehrzeiligen Zeiten visuell prüfen: genau eine Besuchsmarkierung, durchgehende Linie, konsistente Haltepunkte und keine Fortschrittsübernahme aus einer anderen Fahrt.

## Verwandte Seiten

- [Index](./index.md)
- [Tests](./entwicklung/tests.md)
- [TripTracking](./module/trip-tracking.md)
- [StatusDetail](./module/status-detail.md)
- [Secrets und Sicherheit](./konfiguration/secrets-und-sicherheit.md)
