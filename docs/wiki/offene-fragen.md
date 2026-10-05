# Offene Fragen

## Zweck

Sammlung von offenen Punkten, Unsicherheiten und Annahmen im Projekt.

## Offene Punkte

- TODO: Bei Bedarf die am 05.10.2026 erfolgreiche [Live-GET-Prüfung](./entwicklung/tests.md) als wiederholbare Integrationstests einrichten. Falls echte API-Tokens nötig sind, müssen diese lokal und nicht versioniert bereitgestellt werden.
- TODO: Neue Träwelling-Changelog-Einträge regelmäßig mit dem tatsächlich eingesetzten Routen- und Ressourcenvertrag abgleichen. Der letzte dokumentierte Audit verwendet Upstream `develop`-Commit `4d602796da8409017314cc771b1127d169155f02`.
- TODO: Den implementierten [GPS-Stationsalarm](./module/trip-tracking.md) auf echten Zug-/Busfahrten einschließlich Tunnel, naher Halte, Vorbeifahrt und Rundfahrten validieren. Automatischer Radius und Hysteresewerte sind noch nicht durch physische Fahrten bestätigt; Luftlinie ist keine genaue Ankunftsprognose.
- TODO: Auf Android-Geräten präzise/grobe/entzogene Freigabe, ausgeschaltete Ortung, Display-aus-Betrieb, `START_STICKY`, Wiederanlauf mit Cache und Audiofokus prüfen. Hier steht kein physisches Testgerät zur Verfügung; der Fahrplan-Rückfall beendet die Fahrt bewusst nicht automatisch.
- TODO: CI-Ergebnis der GPS-Erweiterung mit neuen Engine-Tests und vollständigem Debug-Build in [Tests](./entwicklung/tests.md) nachtragen; bisheriger grüner API-Prüflauf deckt die Erweiterung nicht ab.

## Verwandte Seiten

- [Index](./index.md)
- [Tests](./entwicklung/tests.md)
- [Secrets und Sicherheit](./konfiguration/secrets-und-sicherheit.md)
