# Offene Fragen

## Zweck

Sammlung von offenen Punkten, Unsicherheiten und Annahmen im Projekt.

## Offene Punkte

- TODO: Die [öffentlich exportierbaren SEV-Ersatzhaltestellen](./module/gps-zeiten.md) der RE1-Busfahrt Essen → Mülheim → Duisburg eindeutig nach Ersatzverkehr, Station, Besuch, Datum und Richtung zuordnen, bevor sie in die Android-App integriert werden. `tools/extract_bahnhof_sev.py` liest öffentliche bahnhof.de-Kartendaten ohne RIS::Stations-Zugang; der Beispielabruf vom 06.10.2026 enthält drei Stationen und fünf SEV-Punkte. Der bisherige App-Code übernimmt die API-Stationskoordinaten weiterhin unverändert. Unklar: Tatsächliche Koordinaten dieser Fahrt und die Ankunftsposition des in Duisburg endenden Busses sind aus dem Screenshot nicht erkennbar. Straßenumwege benötigen eine gesonderte Prüfung des geraden GPS-Prognosekorridors.
- TODO: Dauerhafte Nutzungsbedingungen, Änderungen der öffentlichen HTML-Struktur und Aktualisierung der [SEV-Quelle](./module/gps-zeiten.md) prüfen. Zeitweilige Verlegungen und Sonderfristen müssen als Hinweise erhalten bleiben; `properties.version` ist kein Gültigkeitsintervall. Der Beispielabruf ist eine Momentaufnahme und kein laufender Aktualisierungsdienst.
- TODO: Bestehende UI-Lücken abschließen: [OAuth-Anmeldung und Token-Erneuerung](./module/auth-pkce.md) anbinden, Sichtbarkeitsauswahl beim [Check-in](./module/checkin.md) und [Bearbeiten](./module/status-detail.md) anbieten, leere Herz-Handler im [eigenen](./module/profile.md) und [fremden Profil](./module/user-profile.md) verbinden sowie [Meldungsnavigation](./module/notifications.md) ergänzen. Diese Punkte sind Bestandslücken, keine beschlossene Feature-Roadmap.
- TODO: Bei Bedarf die am 05.10.2026 erfolgreiche [Live-GET-Prüfung](./entwicklung/tests.md) als wiederholbare Integrationstests einrichten. Falls echte API-Tokens nötig sind, müssen diese lokal und nicht versioniert bereitgestellt werden.
- TODO: Neue Träwelling-Changelog-Einträge regelmäßig mit dem tatsächlich eingesetzten Routen- und Ressourcenvertrag abgleichen. Der letzte dokumentierte Audit verwendet Upstream `develop`-Commit `4d602796da8409017314cc771b1127d169155f02`.
- TODO: Nach dem Nutzerbericht zu `1.7.0` den Übergang Maubisstraße → Rathaus mit dem korrigierten [GPS-Stationsalarm](./module/trip-tracking.md) erneut auf einer echten Fahrt prüfen. Unklar: Die genaue Ursache der fehlenden Rathaus-Ansage ist ohne Standort-/Audioverlauf nicht bewiesen. Weitere Prüfungen umfassen Tunnel, nahe Halte, Vorbeifahrt und Rundfahrten; Luftlinie ist keine genaue Ankunftsprognose.
- TODO: Auf Android-Geräten präzise/grobe/entzogene Freigabe, ausgeschaltete Ortung, Display-aus-Betrieb, `START_STICKY`, Wiederanlauf mit Cache und Audiofokus prüfen. Hier steht kein physisches Testgerät zur Verfügung; der Fahrplan-Rückfall beendet die Fahrt bewusst nicht automatisch.
- TODO: Nach dem Nutzerbericht vom 06.10.2026 ausbleibende Ansagen bei ausgeschaltetem Display mit der [Display-aus-/Doze-Matrix](./entwicklung/tests.md) auf Samsung S26 Ultra prüfen: mindestens 30 Minuten, erzwungenes Doze auf einem Testgerät, gewährte/abgelehnte Akku-Ausnahme, Energiesparmodus und Herstellerbeschränkungen. Standort-, API-, Service- und Audioverlauf getrennt erfassen; die konkrete Ursache des Berichts ist bislang nicht bewiesen. Nach Beenden, Logout und Service-Zerstörung darf kein CPU-WakeLock zurückbleiben. Android-Ausnahme und WakeLock ersetzen keinen Geräte-/Akkuverbrauchsnachweis und können Nutzer-Force-Stop nicht aufheben.
- TODO: Die [Status-Timeline](./module/status-detail.md) mit großer Schrift und mehrzeiligen Zeiten visuell prüfen: genau eine Besuchsmarkierung, durchgehende Linie, konsistente Haltepunkte und keine Fortschrittsübernahme aus einer anderen Fahrt.
- TODO: Den S28-Nutzerbericht vom 06.10.2026 zu häufigem Wechsel zwischen `GPS-Schätzung` und `API-Echtzeit` sowie einer erst nach der Abfahrt gesprochenen Einstiegsansage erneut auf einer Fahrt prüfen. Die spätere Bildschirmaufnahme zeigt Quellenwechsel bei eingeschaltetem Display, aber keine Standort- oder Audiodaten. Die [UI-Uhrkorrektur](./module/status-detail.md) muss frische Fixes zwischen sekündlichen UI-Ticks sofort anhand der aktuellen Zeit prüfen; tatsächlich zukünftige und abgelaufene Daten bleiben ungültig. Die [GPS-Zeiten](./module/gps-zeiten.md) halten eine bereits belegte Prognose nur bei passenden frischen Folgefixes bis zum ursprünglichen Ablauf; die [Stationsansage](./module/trip-tracking.md) muss bei bestätigtem Warten vor der gültigen Abfahrt erfolgen und nach beginnender Abfahrt ausbleiben. Eine lange Änderungsansage unmittelbar vor Abfahrt muss den Einstiegsversuch nur innerhalb des weiter gültigen Wartefensters verzögern; nach Losfahren darf kein alter Hinweis nachgereicht werden. Prognosegüte bei Verfrühung, Verspätung, längerem Halt, Tunnel, Kurven und Wiederkehr des Signals bleibt offen. Header, Halte, Widget und Samsung-Sperrbildschirm müssen dieselbe geeignete Zeitquelle verwenden. Die konservative räumliche Interpolation ist kein Nachweis der tatsächlichen Prognosegüte.

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
