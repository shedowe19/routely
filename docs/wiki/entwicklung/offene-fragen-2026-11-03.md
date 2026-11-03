# Audit der offenen Fragen vom 03.11.2026

## Zweck

Hält alle beim erneuten Projektaudit gefundenen offenen Fragen zusammen, trennt beantwortete Sachfragen von fehlenden praktischen Nachweisen und bewahrt die noch ausstehenden Aufgaben.

## Kontext

Die Prüfung erfasste alle 63 zuvor vorhandenen Wiki-Seiten einschließlich der zentralen Liste, Modulbeschreibungen, Testhistorie und Entscheidungsdokumente. TODO-, Unklar- und Annahme-Markierungen sowie Abschnitte ohne solche Kennzeichnung wurden berücksichtigt. Wiederholungen sind zu 39 Fragegruppen zusammengeführt. Die Kennungen dieser Seite dienen der Pflege und sind nicht mit historischen Befundnummern des [Main-Reviews](./main-review-2026-10-06.md) gleichzusetzen.

Die bisherige zentrale Liste enthielt historische Wiederholungen und inzwischen erledigte Aufgaben. Ihre Neufassung verweist deshalb auf diese vollständige Aufbereitung; Fragen werden durch die Kürzung nicht als erledigt erklärt. Die drei gewünschten Funktionen – Betreiberanzeige, Sichtbarkeitsauswahl und Meldungsnavigation – sind im lokalen Arbeitsstand umgesetzt.

## Wichtige Dateien

- app/src/main/kotlin/de/traewelling/app/data/repository/AuthRepository.kt
- app/src/main/kotlin/de/traewelling/app/data/model/Models.kt
- app/src/main/kotlin/de/traewelling/app/util/SystemSettingsNavigation.kt
- app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt
- app/src/main/kotlin/de/traewelling/app/ui/navigation/NotificationTargetResolver.kt
- .github/scripts/check_api_readonly.py
- .github/tests/test_api_readonly.py
- .github/scripts/release_guard.py
- docs/wiki/offene-fragen.md

## Verhalten und Bewertungsmaßstab

**Geklärt** bezeichnet eine durch Quellcode, aktuelle Primärquellen oder echte Artefakte beantwortete Frage. **Behoben und geprüft** bedeutet, dass Änderung, gezielte Regression und abschließender Gesamtprüflauf vorliegen; tatsächliche Geräte- oder Fahrtnachweise sind davon getrennt. **Systemtest** oder **Fahrttest** verlangt den jeweils beschriebenen praktischen Nachweis. **Produktentscheidung** bezeichnet eine mögliche Erweiterung, keinen bestätigten Fehler. **Implementierungsbedarf** bleibt ausdrücklich offene technische Arbeit.

Priorität P1 betrifft Datenintegrität oder die sichere Vorbereitung einer späteren Anbindung, P2 die Verlässlichkeit bestehender Funktionen, P3 optionale Produkt- und Betriebsentscheidungen. Sie ist keine automatisch zugesagte Reihenfolge für neue Funktionen.

### Ausgeführte Nachweise

- Der abschließende lokale Android-Lauf bestand in 2 Minuten 34 Sekunden mit 949 Tests in 76 Klassen ohne Fehler, Fehlschläge oder Skips; vollständiges Debug-Lint hat 0 Fehler und 55 Warnungen. Debug- und unsignierter Release-Build sind erfolgreich. Die 221 erfassten Quellen blieben währenddessen SHA-256-identisch.
- 65 Offlineprüfungen (56 Releaseguards und neun API-Checktests) bestanden. Der aktuelle CLI-Stand mit verschärften Antwortschemas bestand live mit 16 erfolgreichen GET-Anfragen ohne Fehler oder übersprungene Stichprobe.
- Die Korrekturen und alle acht Deduplizierungsfälle wurden unabhängig gegengeprüft. Der alte Deduplizierungscode hatte vorher drei von sechs gezielten Fällen verloren.
- Vollständiger Ablauf und Grenzen stehen unter [Tests](./tests.md). Es fand kein physischer Geräte-, Samsung-, TalkBack-, Restore-, Fahrt- oder schreibender Live-API-Test statt.

### API und Anmeldung

| ID / Priorität | Frage und Quellen | Antwort und verbleibende Arbeit |
| --- | --- | --- |
| A01 / P2 | Regelmäßiger [API-Abgleich](../api/traewelling-kompatibilitaet.md) | Für den heutigen gepinnten Stand geklärt: develop 6051078ef10246d931a34c72d12ad3f96c8a53bc, Changelog 4035dadd77fc2fe692396aa4e9a5dcea5a1a6c67. Gegenüber dem früheren Stand wurden 35 weitere Commits geprüft, ohne weitere Pflichtmigration. Künftige Änderungen brauchen erneut den Vergleich von Changelog, tatsächlichem Vertrag und Clientverbrauchern. |
| A02 / P2 | Wiederholbare GET-Prüfung und Tokenbereitstellung; [Tests](./tests.md) | Umgesetzt und geprüft: das Script führt ausschließlich GET aus, leitet Zugangsdaten bei Redirects nicht weiter, begrenzt Antwortgrößen und gibt anonymisierte Resultate aus. Token kommen aus Standardeingabe oder Umgebung, nicht aus dem Repository. Neun Offlinefälle und der aktuelle verschärfte CLI-Lauf mit 16 erfolgreichen GETs ohne Fehler oder übersprungene Stichprobe sind bestanden. |
| A03 / P2 | Check-in-Konflikt HTTP 409; [Check-in](../module/checkin.md) | Behoben und geprüft: vier zusätzliche Repositoryfälle sichern die Konfliktverarbeitung neben den vorhandenen Modellfixtures ab; sie sind im endgültigen Testlauf enthalten. Ein schreibender Live-Check-in wurde nicht ausgelöst. |
| A04 / P2 | Operator-UUIDs und continuationTrip; [API-Kompatibilität](../api/traewelling-kompatibilitaet.md) | Geklärt: der geprüfte Server liefert noch numerische Operator-IDs; das Modell unterstützt String-ID und UUID separat. continuationTrip ist im Changelog erwähnt, wird im geprüften TripResource aber nicht ausgegeben und fehlt im Live-Beispiel. Vor einer Durchbindungsfunktion ist der tatsächliche Vertrag erneut zu prüfen. |
| A05 / P3 | OAuth-Browserlogin; [Auth](../module/auth.md), [PKCE](../module/auth-pkce.md) | Produktentscheidung: erreichbar ist der persönliche Tokenlogin. Ein Browserlogin benötigt registrierten Client, Callback, PKCE-/State-Lifecycle und UI. Vorhandene Helfer allein bilden diesen Ablauf nicht. |
| A06 / P1 vor OAuth-Anbindung | Gleichzeitige Refreshrotation; [PKCE](../module/auth-pkce.md) | Behoben und geprüft: identische gleichzeitige Refreshaufträge teilen über Repositorygrenzen eine Ausführung. Andere Sitzungen bleiben unabhängig. Abbruch, erneuter Versuch, temporärer Fehler und invalid_grant sind durch sechs neue kontrollierte Fälle im endgültigen Lauf abgedeckt; bestehende Sessionprüfungen bleiben erhalten. |
| A07 / P3 | App-Umbenennung, Callback und Domain; [ADR](../entscheidungen/2026-04-28-app-rename-routely.md) | Geklärt: Anzeigename Routely ändert weder Paket/Namespace de.traewelling.app noch Callback traewelling://oauth-callback. Der Tokenlogin verwendet keinen OAuthclient. Allein die Umbenennung verlangt keine Backendmigration. Eine neue Domain ist eine Produktentscheidung; eine spätere OAuthregistrierung muss zum tatsächlich verwendeten Callback passen. |

### Release, Build und Bibliotheken

| ID / Priorität | Frage und Quellen | Antwort und verbleibende Arbeit |
| --- | --- | --- |
| R01 / P2 | Erster echter signierter Release nach den Schutzkorrekturen; [Deployment](./deployment.md#verifizierte-veröffentlichung-v191) | Geschlossen: die tatsächlich veröffentlichte APK v1.9.1 wurde mit apksigner und aapt geprüft. Signaturen v2/v3, Paket, Code 18, Name, Größe und SHA-256 stimmen mit Metadaten und GitHub-Digest überein. Tag, Codeclaim und Workflow binden denselben Commit. Dies ist kein neuer Release der lokalen Änderungen. |
| R02 / P3 | Dauerhafter Schutz von Codeclaims; [Deployment](./deployment.md) | Geklärte Grenze: der Helfer erstellt Claims unverändernd, richtet aber kein Ruleset gegen Administratorlöschung ein. Vollständig gelöschte Historie kann nicht rekonstruiert werden. Zusätzlicher administrativer Löschschutz ist eine Betriebsentscheidung. |
| R03 / P2 | SDK-/AAR-/Kotlin-/R8-Kompatibilität; [Abhängigkeiten](../architektur/externe-abhaengigkeiten.md#erneute-kompatibilitätsprüfung-vom-03112026) | Geklärt und geprüft: offizielle Toolchainangaben und tatsächlich aufgelöste AAR-Metadaten passen zu compileSdk 36, AGP 8.13.2, Kotlin 2.3 und JDK 17. Der endgültige Build einschließlich Lint und beider APK-Varianten ist erfolgreich. Zukünftige Bibliotheksstände sind dadurch nicht pauschal freigegeben; Geräteverhalten siehe P10. |
| R04 / P3 | Play-Store-Veröffentlichung; [Deployment](./deployment.md) | Bedingte Produktentscheidung: vorhandene Verteilung erfolgt als GitHub-APK. Für einen neuen Play-Upload verlangt die heute gelesene offizielle Vorgabe API 36; targetSdk 34 reicht dafür nicht. Eine Storeeinführung benötigt Targetmigration mit Plattformprüfung sowie Bundle-, Signierungs- und Storekonfiguration. compileSdk 36 allein ersetzt das nicht. |

### GPS, SEV und öffentliche Datenquellen

| ID / Priorität | Frage und Quellen | Antwort und verbleibende Arbeit |
| --- | --- | --- |
| G01 / P2 | Tunnel Essen Hbf → Bismarckplatz → Savignystraße; [Tracking](../module/trip-tracking.md), [Tests](./tests.md) | Fahrttest: synthetische Wiederverankerung ist geprüft, der historische Fix-/Audioverlauf fehlt. Mehrere Halte ohne GPS, drei geeignete Rückkehrfixes, Cursor/Timeline/Ansagen/Zielabschluss gemeinsam erfassen. Rundfahrten, stationäre Rückkehr, erneuter Ausfall, Cache-Neustart und Rückkehr erst am Ziel sind eigene Fälle. |
| G02 / P2 | Rathaus- und S28-Berichte; [Tracking](../module/trip-tracking.md), [GPS-Zeiten](../module/gps-zeiten.md) | Fahrttest: frühere Logiklücken bei Samplefenster, Prognosebewegung und Einstiegsansage sind korrigiert. Verfrühte Fahrt Maubisstraße → Rathaus, S28 vor tatsächlicher Abfahrt, kurze Halte sowie lange konkurrierende Änderungsansage mit Zeit-/Fix-/Audioverlauf prüfen. Ohne alte Aufzeichnung ist die damalige Geräteursache nicht vollständig rekonstruierbar. |
| G03 / P2 | ETA-Güte, Radius/Hysterese, Nähe gegenüber Halt; [GPS-Zeiten](../module/gps-zeiten.md) | Fahrttest: Zeitgültigkeit und konservativer Rückfall sind geprüft, echte Genauigkeit nicht. Verfrühung, Verspätung, längerer Halt, Kurven, Signalverlust und Wiederkehr gegen beobachtete Ereignisse messen. Header, Halte, Widget und Sperrbildschirm müssen dieselbe geeignete Quelle verwenden. |
| G04 / P2 | Native Bahn-/Tramformen; [ADR](../entscheidungen/2026-10-06-native-streckenverlaeufe.md) | Fahrttest und Messung: Linienzüge können Stationssehnen enthalten und sind keine amtliche Gleisgarantie. Kurven, parallele Gleise, Rundfahrten, Umleitungen, lange Abschnitte, Form-/Sessionwechsel und harten Ablauf ohne Netz prüfen. Speicher-/Netzverbrauch und Quellenqualität weiterer Server messen; Gerätefixes werden beim Formabruf nicht übertragen. |
| G05 / P2 | Physische SEV-Halte, Richtung und Gültigkeit; [SEV](../module/sev-haltestellen.md) | Teilweise geklärt: heutige öffentliche Seiten liefern lesbare Punkte für Essen, Mülheim und Duisburg. Das beweist keine konkrete aktuelle Busfahrt und keinen Duisburger Ankunftspunkt. Benötigt werden vollständige API-Fahrt und Vor-Ort-Beleg für Einstieg, Richtung, Maßnahmenende, Sonderfristen und mehrdeutige Grenzen; ohne Beleg bleibt die API-Position. |
| G06 / P2 | Straßenweg und gemeinsamer SEV-Restweg; [ADR](../entscheidungen/2026-10-07-sev-gemeinsamer-restweg.md), [GPS](../module/gps-zeiten.md) | Fahrttest: Pkw-Routenkandidaten sind keine bestätigten Buswege. RE1-Rückfahrt, zusammenlaufende Alternativen, Neustart, Signalverlust, parallele Straßen, Kreuzungen und echte Umleitung prüfen. Beobachtete Ereignisse bleiben auch ohne Prognose sichtbar; fehlende Endpunkte oder Geometrie erhalten den API-/Planrückfall. |
| G07 / P2 | Bedingungen, Struktur und Dienstlast; [externe Schnittstellen](../api/externe-schnittstellen.md), [SEV](../module/sev-haltestellen.md) | Anbieterquellen wurden aktuell gelesen. Lokale Abruflimits, User-Agent und OSRM-Attribution sind umgesetzt; daraus folgt keine flottenweite Kapazitätszusage. Bahnhof-Webbedingungen sind von db.info-Appbedingungen zu unterscheiden; über erlaubtes Anzeigen/Prüfen hinausgehende Nutzung kann Zustimmung erfordern. Weitere Slugs, Richtungen, Zeiträume, HTML-Änderungen, Nutzungsumfang und Last sind bei Ausbau erneut zu bewerten. |
| G08 / P2 | Fahrterkennung und Verbrauch; [Fahrterkennung](../module/ride-recognition.md) | Fahrttest: synthetische Zuordnung ist geprüft. Treffer-/Fehlalarmquote auf Bus-/Bahnfahrten, parallelen Linien, Kurven und GPS-Lücken messen. Logout, aktive Fahrt, Berechtigungen sowie längere Suche mit API-/GPS-/Akkuverbrauch prüfen. Die aus dem Fix gebildete Stations-Bounding-Box wird an Träwelling übertragen. |
| G09 / P2 | Zustellung von Fahrtänderungen; [Fahrtänderungen](../module/trip-changes.md) | System-/Fahrttest: reale Gleiswechsel, Ausfälle und mehrere Ereignisse desselben Abrufs mit deaktivierten Benachrichtigungen und konkurrierender TTS prüfen. Ohne frische eindeutige Providerwerte darf keine Änderung erfunden werden; Startverspätung bleibt Ausgangslage. |
| G10 / P2 | DBF-Abdeckung und Provideralter; [Live-Datenquellen](../api/live-datenquellen.md) | Geklärte Datengrenze plus Fahrttest: Clientabrufzeit ist keine Provideränderungszeit. Gleiswechsel, Ausfall, Mitternacht und Rücknahmen mit zeitlich erfassten Antworten vergleichen. Version 3 bietet keinen belastbaren separaten Teilausfall-/Rücknahmeimport. Ein garantierter Aktualitätsvorsprung gegenüber Träwelling ist nicht belegt. |
| G11 / P1 | Verlust ergänzender Stopover-Daten; [Live-Datenquellen](../api/live-datenquellen.md) | Behoben und geprüft: drei von sechs neuen Fällen scheiterten am alten Auswahlverfahren. Die Zusammenführung erhält nun ergänzende Echtzeit-, Gleis-, Cancellation- und Stationsfelder samt Wertprovenance; getrennte Besuche bleiben getrennt. Leere Gleisangaben werden berücksichtigt. Alle acht gezielten Regressionen des endgültigen Stands sind bestanden. |

### Android, Oberfläche und Speicher

| ID / Priorität | Frage und Quellen | Antwort und verbleibende Arbeit |
| --- | --- | --- |
| P01 / P2 | Sitzungen, Cache und Lifecycle; [Auth](../module/auth.md), [Tests](./tests.md) | Systemtest: verspätete Antworten bei Logout und erneutem Login mit gleichem Token, Konto-/Serverwechsel, Rotation/Prozessneustart, schnelle Suche/Auswahl, doppeltes Tippen und gleichzeitige Lesemarkierungen prüfen. Room-Upgrade darf alte private Daten nicht zeigen; gültig leere Daten müssen ihren Cache ersetzen. API-26–30-System-/Gesten-Zurück gehört zur Matrix. |
| P02 / P2 | Foreground-Timeout und Bereinigung; [Tracking](../module/trip-tracking.md) | Systemtest: Cleanup ist implementiert und erhält den Timeoutcache. targetSdk 34 aktiviert das reguläre dataSync-Budget ab Target 35 noch nicht. Auf Android 15+ die Grenze ausdrücklich im Test aktivieren; Jobs, GPS, Audiofokus und WakeLock müssen synchron enden, alte Callbacks dürfen nichts neu veröffentlichen, Wiederanlauf muss sichtbar möglich sein. |
| P03 / P2 | Display aus, Doze, Samsung und Akku; [Tracking](../module/trip-tracking.md), [Testmatrix](./tests.md) | Systemtest: begrenzter WakeLock und Erneuerung sind implementiert. Mindestens 30 Minuten auf dem betroffenen Samsung-Gerät mit gewährter/abgelehnter Ausnahme, Doze, Energiesparen, Herstellerbeschränkung und Netz-/GPS-Ausfall prüfen. Standort-, Service- und Audioverlauf sowie Verbrauch erfassen; alle Stopp-/Logout-/Destroy-Wege müssen freigeben. |
| P04 / P2 | FLP-, Uhr- und TTS-Lifecycle; [Tracking](../module/trip-tracking.md), [Settings](../module/settings.md) | Systemtest: Retry-/Besitz-/Zeit- und Queuegrenzen sind synthetisch abgesichert. Behebbare Ortungsfehler, ausgeschaltete Ortung, Uhrsprung, TTS-Initialfehler, Audiofokus und Wechsel tatsächlich installierter Engine/Sprache/Stimme prüfen. Tatsächliche Sprachausgabe ist kein Unit-Test-Ergebnis. |
| P05 / P2 | Live Updates und Sperrbildschirm; [Reisefortschritt](../module/trip-progress.md) | Systemtest auf API 35, 36, 36.1 und Samsung: System-/OEM-Promotion, Berechtigungen, Wegwischen, große Schrift, kalte/warme Navigation und deaktivierte Sperrbildschirmdetails prüfen. Implementierte Anfrage und ProgressStyle erzwingen keine Hervorhebung durch das System. |
| P06 / P2 | Widget und Systemzustellung; [Widget](../module/widget.md) | Systemtest: Receiver ist nicht exportiert, eigene Updates sind explizit, periodisches Widgetpolling ist nicht eingerichtet. Launcher-/Systemupdates, abgewiesene fremde Updates, Quellenwechsel, negative Abweichung und große Schrift nach GPS-Ausfall prüfen. |
| P07 / P2 | Cloud-Restore und Geräteübertragung; [Sicherheit](../konfiguration/secrets-und-sicherheit.md) | Systemtest: Backup-/Transferregeln schließen DataStore und Room aus; Manifest-/Buildprüfung ist vorhanden. Tatsächlichen Restore mit unterstütztem Transport und Geräteübertragung prüfen. Token-, Session- und aktive Fahrtzustände dürfen dabei nicht unerwartet wiederhergestellt werden. |
| P08 / P2 | Neue Funktionen und Bedienungshilfen; [Check-in](../module/checkin.md), [Meldungen](../module/notifications.md) | Systemtest: Dropdowns, Moderationssperre, Zurück-/Editorerhalt und Meldungsziel mit großer Schrift, Tastatur und TalkBack prüfen. Unbekanntes Meldungsziel und fehlgeschlagene Lesemarkierung sind getrennte Fälle; Rückkehr muss zum Meldungstab führen. |
| P09 / P2 | Timeline und mehrzeilige Zeiten; [Statusdetail](../module/status-detail.md) | Sichtprüfung auf Gerät: genau ein Besuchsmarker, durchgehende Linie und passende Haltepunkte bei großer Schrift, langen Stationsnamen, mehreren Zeitquellen und Teilroute. Kein Fortschritt darf aus einer anderen Fahrt übernommen werden. |
| P10 / P2 | Bibliotheksverhalten auf Gerät; [Build](./build.md) | Systemtest: Coil-3-Bilder/HTTP-Cache, Material-3-Pull-to-refresh, Compose-Layouts, Standortabfrage und TTS-Einstellungen prüfen. Kompatible AAR-Metadaten und erfolgreicher Build ersetzen diese Oberflächenprüfung nicht. |
| P11 / P2 | Fehlende oder verweigerte System-Einstellungen; [Settings](../module/settings.md) | Behoben und geprüft: vorher ungeschützte Fallbacks und SecurityException werden durch gemeinsame Strategie und Android-Adapter behandelt. Alle fünf Regressionen sind bestanden. Auf Android/Samsung bleiben tatsächliche Activity-Verfügbarkeit, Rückkehr zur Karte und Freigabeänderungen zu prüfen. |
| P12 / P2 | PreferencesManager-Fehler und Recovery; [PreferencesManager](../konfiguration/preferences-manager.md) | Offener Implementierungsbedarf: ein vollständiger transparenter Umgang mit Lese-/Schreibfehlern und sichere Recovery-UI fehlen. Automatisches Ersetzen durch leere Preferences würde Datenverlust riskieren. Eine getrennte Umsetzung braucht Tests und eine ausdrückliche Nutzerentscheidung vor verlustbehafteter Wiederherstellung. |

### Dokumentation und optionale Produktentscheidungen

| ID / Priorität | Frage und Quellen | Antwort und verbleibende Arbeit |
| --- | --- | --- |
| U01 / P3 | Editorlayout dokumentieren; [Statusdetail](../module/status-detail.md) | Erledigt: Anordnung, Scrollverhalten, gesperrte Bedienung während Speichern und Sichtbarkeitsauswahl sind anhand des tatsächlichen Dialogs dokumentiert. |
| U02 / P3 | Systemtheme und Farbskalen; [ADR](../entscheidungen/2026-04-29-dark-mode-und-settings.md) | Produktentscheidung: Hell/Dunkel/AMOLED sind ausdrücklich wählbar. Eine automatische Systemoption oder weitere Farbskalen sind mögliche Erweiterungen, kein bestätigter Fehler. |
| U03 / P3 | Punkte ausblenden oder durchstreichen; [Punkte](../features/points-enabled.md) | Produktentscheidung: bei deaktivierten Punkten wird aktuell ausgeblendet. Keine Rechen- oder API-Inkompatibilität ist belegt; andere Darstellung benötigt eine bewusste Gestaltungsentscheidung. |
| U04 / P3 | Profil-Likes; [Profile](../module/profile.md), [Nutzerprofil](../module/user-profile.md) | Geklärte Funktionsgrenze: Profilherzen sind ausdrücklich deaktiviert; Likes funktionieren im Feed. Eine zusätzliche Profilaktion wäre eine Erweiterung, kein leerer Klickhandler. |
| U05 / P3 | Weitere Ausbauideen; [Features](../features/README.md), [Main-Review](./main-review-2026-10-06.md) | Produktentscheidung: Mehr-Etappen-Reisen, Anschlussüberwachung, vollständige Offlineplanung, Karte, Tickets und Wear OS sind nicht umgesetzt. Der Begleiter verfolgt einen Check-in. Diese Ideen sind keine verbleibenden historischen Codebefunde. |

## Abhängigkeiten

Die Antworten stützen sich auf den lokalen Quellstand, kontrollierte Regressionen, aktuelle Träwelling-Ressourcen, offizielle Android-Dokumentation und den tatsächlich heruntergeladenen veröffentlichten Release. Öffentliche Datenquellen bleiben von Verfügbarkeit, Nutzungsbedingungen und Datenqualität abhängig. Token und persönliche API-Antworten werden nicht in dieser Wissensbasis gespeichert.

## Offene Fragen

- Die bestätigten Ergebnisse sind unter [Tests](./tests.md) eingetragen; bei weiteren Änderungen müssen die betroffenen Nachweise erneuert werden.
- P12 ist noch technische Arbeit; A05, R02, R04 und U02–U05 benötigen bei gewünschtem Ausbau eine Produkt- beziehungsweise Betriebsentscheidung.
- G01–G10 sowie P01–P10 und die Gerätehälfte von P11 enthalten konkrete praktische Prüfbedingungen. Ohne diese Beobachtungen werden sie nicht als abgeschlossen bezeichnet.
- A01 bleibt fortlaufende Pflege. Der heutige Vertragsabgleich ist eine Momentaufnahme.

## Verwandte Seiten

- [Zentrale offene Fragen](../offene-fragen.md)
- [Tests und tatsächliche Nachweise](./tests.md)
- [API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
- [Deployment](./deployment.md)
- [Main-Review und historische Befunde](./main-review-2026-10-06.md)
- [Live-Datenquellen](../api/live-datenquellen.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [SEV-Haltestellen](../module/sev-haltestellen.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
