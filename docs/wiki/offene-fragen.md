# Offene Fragen

## Zweck

Aktuelle offene Arbeit, praktische Prüfbedingungen und Produktentscheidungen. Die vollständige Prüfung aller 39 zusammengeführten Fragegruppen mit Quellen und Einzelantworten steht im [Audit vom 03.11.2026](./entwicklung/offene-fragen-2026-11-03.md). Historische Wiederholungen wurden zugunsten dieser nachvollziehbaren Zuordnung entfernt; noch fehlende Nachweise bleiben offen.

## Offene Punkte

- **P12 – Speicherfehler und Recovery:** Ein vollständiger Umgang mit Lese-/Schreibfehlern samt sicherer Wiederherstellungsoberfläche fehlt. Kein automatisches Leeren der Preferences, weil dadurch Tokens und aktive Fahrtzustände verloren gehen könnten. Das [Speicherkonzept](./konfiguration/preferences-manager.md) beschreibt die nötigen Fehlerfälle und Entscheidungsgrenzen.
- **A01 – laufender API-Abgleich:** Der geprüfte Träwelling-Stand ist develop 6051078ef10246d931a34c72d12ad3f96c8a53bc mit Changelog 4035dadd77fc2fe692396aa4e9a5dcea5a1a6c67. Keine weitere Pflichtmigration wurde gefunden. Weitere Änderungen sind regelmäßig gegen tatsächliche Routen, Ressourcen und Clientverbraucher zu prüfen. Operator-IDs bleiben im geprüften Server noch numerisch; continuationTrip ist dort trotz Changelog nicht im TripResource ausgegeben. Siehe [API-Kompatibilität](./api/traewelling-kompatibilitaet.md).
- **Bestätigter Validierungsstand:** Der als `v1.11.0` veröffentlichte Zeitausbau besteht 987 Android-Unit-Tests ohne Fehler oder Skips, vollständiges Debug-Lint ohne Fehler (55 Warnungen) sowie Debug- und unsignierten Release-Build. Alle 65 Offlineprüfungen wurden in der zugehörigen CI erneut erfolgreich ausgeführt. Sieben zusätzliche lesende GET-Stichproben prüfen aktuelle und vergangene Abfahrten/Fahrtdetails; die frühere lesende Live-Prüfung mit 16 Erfolgen bleibt dem vorherigen Stand zugeordnet. Signierte APK, Version 1.11.0/Code 20 und Commitbindung sind unter [Deployment](./entwicklung/deployment.md#verifizierte-veröffentlichung-v1110) geprüft. Nachweise und ausdrücklich nicht ausgeführte Geräteprüfungen stehen unter [Tests](./entwicklung/tests.md).

## Noch nötige System- und Fahrttests

Die Kennungen verweisen auf die vollständigen Bedingungen im [Audit](./entwicklung/offene-fragen-2026-11-03.md). Vorhandene Regressionen und Codeprüfungen beantworten Logikfragen, ersetzen aber die folgenden tatsächlichen Beobachtungen nicht:

- **G01–G03 – Tunnel, konkrete Nutzerberichte und GPS-Güte:** Essen Hbf → Bismarckplatz → Savignystraße mit mehreren Halten ohne GPS und späterer Wiederverankerung; Maubisstraße → Rathaus bei Verfrühung; S28-Quellenwechsel und Einstiegsansage vor Abfahrt. Fix-, Ereignis- und Audiozeiten gemeinsam erfassen, einschließlich Rundfahrt, Signalflattern, Neustart und konkurrierender Ansage. Radius/Hysterese und tatsächliche ETA-Güte danach bewerten.
- **G04 – native Formen:** Kurven, parallele Gleise, Rundfahrt, Umleitung, lange Linienzüge, Form-/Sessionwechsel und harter Ablauf ohne Netz prüfen. Quellenqualität, Speicher- und Netzverbrauch auf weiteren Servern messen. Linienzüge können Stationssehnen enthalten und sind keine amtliche Gleisgarantie.
- **G05–G06 – konkrete SEV-Fahrten:** RE1 in beiden Richtungen, physischer Einstieg, Mülheimer Richtungsbeleg, Duisburger Ankunftspunkt, Sonderfristen und Maßnahmenende anhand vollständiger API-Fahrt und vor Ort prüfen. Straße, gemeinsamer Restweg, Umleitung, Kreuzung, parallele Wege, GPS-/Netzausfall und Cache-Neustart benötigen Messfahrten. Pkw-Geometrie ist keine bestätigte Busführung; ohne eindeutigen Beleg bleibt der API-/Planrückfall.
- **G07 und G10 – öffentliche Quellen:** Weitere Bahnhofseiten, HTML-Änderungen, Richtungsformulierungen und Gültigkeitszeiträume überwachen. DBF bei Gleiswechsel, Ausfall, Mitternacht und Rücknahmen zeitlich mit Träwelling vergleichen. Clientabrufzeit belegt kein Provideralter; eine flottenweite Kapazität oder ein fester Aktualitätsvorsprung ist nicht nachgewiesen. Lokale Limits und Attribution ersetzen keine Bewertung wachsender Nutzung und Anbieterbedingungen.
- **G08–G09 – Erkennung und Änderungen:** Auf realen Bus-/Bahnfahrten Treffer- und Fehlalarmquote bei Parallelverkehr, Kurven und Lücken prüfen; lange Suche mit GPS-/API-/Akkuverbrauch messen. Reale Gleis-/Ausfalländerungen, mehrere Ereignisse desselben Abrufs, deaktivierte Meldungen und konkurrierende TTS testen.
- **P01 und P07 – Session, Lifecycle und Restore:** Verspätete Antworten bei Logout, erneutem Login mit gleichem Token, Server-/Kontowechsel, Rotation und Prozessneustart prüfen. Schnelle Suche/Auswahl, doppelte Bestätigung und Lesemarkierung sowie Room-Upgrade mit privaten und gültig leeren Daten einbeziehen. Cloud-Restore und Geräteübertragung müssen die ausgeschlossenen Credential-/Cachezustände tatsächlich respektieren; XML allein ist kein Restorelauf.
- **P02–P04 – Hintergrundbetrieb und Audio:** Android-15+-Timeout im Test ausdrücklich aktivieren; synchronen Cleanup und sichtbaren Cache-Wiederanlauf prüfen. Mindestens 30 Minuten Display-aus/Doze auf dem betroffenen Samsung-Gerät mit gewährter/abgelehnter Akkuausnahme, Energiesparen, Ortungs-/Netzausfall und sämtlichen Stoppwegen messen. FLP-Wiederanlauf, Uhrsprung, Audiofokus, TTS-Initialfehler und Wechsel tatsächlich installierter Engines/Sprachen/Stimmen gehören dazu. Das heutige Target 34 unterliegt regulär noch nicht dem dataSync-Budget ab Target 35.
- **P05–P06 – Systemanzeigen:** Live Updates auf API 35, 36 und 36.1 sowie Samsung einschließlich Promotion, Wegwischen, kalter/warmer Navigation und Sperrbildschirm-Privatsphäre prüfen. Widget-Systemzustellung bei nicht exportiertem Receiver, Quellenwechsel, negative Abweichung und große Schrift im Launcher testen. Das System entscheidet die Hervorhebung.
- **P08–P11 – Oberfläche und Plattformintegration:** Betreiberanzeige, Sichtbarkeitsdropdowns und Moderationssperre, Editor-/Zurücknavigation und Meldungsziele mit großer Schrift, Tastatur und TalkBack prüfen. Für die neuen Zeitfunktionen zusätzlich Datum-/Uhrzeitdialoge, `Fahrt öffnen`, Jetzt-Zeitkorrekturen und deren echte Serverbestätigung samt passendem Begleitungsende prüfen. Timeline benötigt genau einen passenden Besuchsmarker und konsistente mehrzeilige Zeiten. Coil-3-Bildcache, Material-3-Refresh, Standortabfrage sowie fehlende/verweigerte System-Activities und Rückkehr zur Settingskarte bleiben praktische Fälle.

## Produkt- und Betriebsentscheidungen

Diese Punkte sind geklärte Funktionsgrenzen oder optionale Erweiterungen, keine unbeantworteten Ursachenbehauptungen:

- **A05:** Vollständiger OAuth-Browserlogin mit registriertem Client, Callback, PKCE/State und UI; der persönliche Tokenlogin ist bereits erreichbar.
- **A07/R02:** Eine neue Kontakt-/Webdomain und administrativer Schutz gegen Löschen von Releaseclaims sind eigenständige Entscheidungen. Der Anzeigename Routely erfordert allein keine neue Paket- oder Callbackidentität.
- **R04:** Ein Play-Store-Angebot benötigt eine geplante Targetmigration und Storekonfiguration. Aktuell erfolgt die Verteilung als GitHub-APK; targetSdk 34 erfüllt die heutige Vorgabe für neue Play-Uploads nicht. Siehe [Deployment](./entwicklung/deployment.md).
- **U02–U04:** Automatische Systemtheme-Auswahl, weitere Farbskalen, andere Darstellung deaktivierter Punkte und Likes in Profilkarten. Bestehend sind Hell/Dunkel/AMOLED, ausgeblendete deaktivierte Punkte und Likes im Feed.
- **U05:** Mehrere Reiseetappen, Anschlüsse, vollständige Offlineplanung, Karte, Tickets und Wear OS. Der aktuelle Begleiter verfolgt einen Check-in.

## Geklärte und korrigierte Fragen

- Die historischen 16 Hauptbefunde, zwölf zusätzlichen Nachreview-Befunde und acht weiteren Nachreview-Befunde sind bereits korrigiert; die [historischen Nachweise](./entwicklung/main-review-2026-10-06.md) bleiben erhalten.
- **R01:** Der echte veröffentlichte Release v1.9.1 ist durch heruntergeladene APK, Signaturen, Manifest, Digest, Metadaten, Tag, Claim und Workflow geprüft. Der frühere TODO zum ersten signierten Release ist geschlossen; siehe [Deployment-Nachweis](./entwicklung/deployment.md#verifizierte-veröffentlichung-v191).
- **R03/A07/U01:** Die Toolchain-/AAR-Frage, Folgen der App-Umbenennung und Dokumentation des Editorlayouts sind geklärt.
- Betreiberanzeige, Sichtbarkeitsauswahl und Meldungsnavigation sind umgesetzt. **A06/G11/P11:** Zusätzlich sind die bestätigten Parallel-Refresh-, Stopover-Datenverlust- und System-Settings-Fallbackfehler korrigiert. Der endgültige Gesamtprüflauf dazu steht noch aus.
- **A02/A03:** Wiederholbares lesendes API-Prüfscript und zusätzliche Repositoryfälle für HTTP 409 liegen vor. 65 Python-Offlinefälle einschließlich neun Scriptprüfungen sind erfolgreich. Der frühere CLI-Lauf lieferte 16 erfolgreiche GETs; die erneute verschärfte Live-Strukturprüfung bleibt im Endnachweis zu ergänzen. Kein Token und keine persönliche API-Antwort sind Teil des Wikis.

## Verwandte Seiten

- [Vollständiger Audit aller 39 Fragegruppen](./entwicklung/offene-fragen-2026-11-03.md)
- [Tests und tatsächliche Nachweise](./entwicklung/tests.md)
- [Projekt-Wiki](./index.md)
- [API-Kompatibilität](./api/traewelling-kompatibilitaet.md)
- [Live-Datenquellen](./api/live-datenquellen.md)
- [GPS-Zeiten](./module/gps-zeiten.md)
- [SEV-Haltestellen](./module/sev-haltestellen.md)
- [PreferencesManager](./konfiguration/preferences-manager.md)
- [Sicherheit](./konfiguration/secrets-und-sicherheit.md)
