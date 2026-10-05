# Offene Fragen

## Zweck

Sammlung von offenen Punkten, Unsicherheiten und Annahmen im Projekt.

## Offene Punkte

- TODO: Bestehende UI-Lücken abschließen: [OAuth-Anmeldung und Token-Erneuerung](./module/auth-pkce.md) anbinden, Sichtbarkeitsauswahl beim [Check-in](./module/checkin.md) und [Bearbeiten](./module/status-detail.md) anbieten, leere Herz-Handler im [eigenen](./module/profile.md) und [fremden Profil](./module/user-profile.md) verbinden sowie [Meldungsnavigation](./module/notifications.md) ergänzen. Diese Punkte sind Bestandslücken, keine beschlossene Feature-Roadmap.
- TODO: Bei Bedarf die am 05.10.2026 erfolgreiche [Live-GET-Prüfung](./entwicklung/tests.md) als wiederholbare Integrationstests einrichten. Falls echte API-Tokens nötig sind, müssen diese lokal und nicht versioniert bereitgestellt werden.
- TODO: Neue Träwelling-Changelog-Einträge regelmäßig mit dem tatsächlich eingesetzten Routen- und Ressourcenvertrag abgleichen. Der letzte dokumentierte Audit verwendet Upstream `develop`-Commit `4d602796da8409017314cc771b1127d169155f02`.
- TODO: Nach dem Nutzerbericht zu `1.7.0` den Übergang Maubisstraße → Rathaus mit dem korrigierten [GPS-Stationsalarm](./module/trip-tracking.md) erneut auf einer echten Fahrt prüfen. Unklar: Die genaue Ursache der fehlenden Rathaus-Ansage ist ohne Standort-/Audioverlauf nicht bewiesen. Weitere Prüfungen umfassen Tunnel, nahe Halte, Vorbeifahrt und Rundfahrten; Luftlinie ist keine genaue Ankunftsprognose.
- TODO: Auf Android-Geräten präzise/grobe/entzogene Freigabe, ausgeschaltete Ortung, Display-aus-Betrieb, `START_STICKY`, Wiederanlauf mit Cache und Audiofokus prüfen. Hier steht kein physisches Testgerät zur Verfügung; der Fahrplan-Rückfall beendet die Fahrt bewusst nicht automatisch.
- TODO: Die [Status-Timeline](./module/status-detail.md) mit großer Schrift und mehrzeiligen Zeiten visuell prüfen: genau eine Besuchsmarkierung, durchgehende Linie, konsistente Haltepunkte und keine Fortschrittsübernahme aus einer anderen Fahrt.
- TODO: [GPS-Zeiten](./module/gps-zeiten.md) auf echten Fahrten mit Verfrühung, Verspätung, längerem Halt, Tunnel, Kurven und Wiederkehr des Signals prüfen. Header, Halte, Widget und Samsung-Sperrbildschirm müssen dieselbe geeignete Zeitquelle verwenden. Die konservative räumliche Interpolation ist kein Nachweis der tatsächlichen Prognosegüte.

- TODO: Die [Fahrterkennung](./module/ride-recognition.md) auf realen Fahrten einschließlich paralleler Linien, Kurven, GPS-Lücken, Logout und aktiver Fahrt prüfen. Android-Freigaben, tatsächliche Erkennungsgüte sowie Akku-/API-Verbrauch sind keine Ergebnisse der reinen Engine-Tests.
- TODO: [Änderungshinweise](./module/trip-changes.md) mit realen API-Änderungen, deaktivierten Benachrichtigungen und konkurrierenden TTS-Ansagen prüfen. Ohne Providerwerte gibt es keine Änderungserklärung.
- TODO: [Reisefortschritt](./module/trip-progress.md) auf Android API 35, 36 und 36.1 sowie Samsung prüfen: System-/OEM-Promotion, große Schrift, Wegwischen, kalte/warme Navigation und ausgeschaltete Sperrbildschirmdetails. Live Updates bleiben vom System und Nutzer abhängig.

## Verwandte Seiten

- [Index](./index.md)
- [Tests](./entwicklung/tests.md)
- [TripTracking](./module/trip-tracking.md)
- [GPS-Zeiten](./module/gps-zeiten.md)
- [StatusDetail](./module/status-detail.md)
- [Secrets und Sicherheit](./konfiguration/secrets-und-sicherheit.md)
- [Fahrterkennung](./module/ride-recognition.md)
- [Fahrtänderungen](./module/trip-changes.md)
- [Reisefortschritt](./module/trip-progress.md)
