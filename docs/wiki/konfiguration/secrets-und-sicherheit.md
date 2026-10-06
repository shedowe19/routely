# Konfiguration: Secrets und Sicherheit

## Zweck

Sicherheitsrelevante Vorgaben für die Entwicklung.

## Regeln

- Niemals Test-User-Credentials, JWT Tokens oder Client Secrets in den Code oder das Git-Repository (und schon gar nicht hier ins Wiki!) pushen.
- Lokale `local.properties` verwenden, falls API-Keys für Entwickler-Builds benötigt werden, diese Datei ist standardmäßig in der `.gitignore`.
- Lokale `.env`-Dateien sowie lokale Signing-Dateien (`*.jks`, `*.keystore`) sind über `.gitignore` ausgeschlossen.
- API-Token für manuelle Tests dürfen nur lokal und nicht versioniert verwendet werden. Werte nie in Gradle-Befehle, CI-Logs, Markdown-Dateien oder Screenshots übernehmen.

## Sitzung und lokaler Feedcache

Ein neuer manueller Login sendet den Token nur nach Prüfung einer gültigen HTTPS-Server-URL ohne URL-Zugangsdaten, Query oder Fragment. Er wird erst nach vollständiger erfolgreicher Profilantwort gespeichert. Atomare Revisions-/Snapshot-Vergleiche schützen spätere Auth- und Check-in-Schreiboperationen; Logout entfernt die lokale Sitzung vor der optionalen API-Abmeldung. Der Featurezustand wird bei Zugangsgenerationswechsel beendet und neu aufgebaut. Einzelheiten stehen unter [Auth](../module/auth.md).

Der Room-Feedcache ist nach Feedart sowie einem SHA-256-Digest aus Server und Token partitioniert und verwendet keinen Klartexttoken als Cachekey. Der Digest ist keine Verschlüsselung der gespeicherten Statusdaten. Temporäre Fehler dürfen ausschließlich die weiterhin passende Seite-1-Partition verwenden; Authfehler, Abbruch und Sitzungswechsel erlauben keinen alten privaten Cache als Ersatz. Die Version-2-Schemaänderung verwirft den historischen unpartitionierten Feedcache. Allgemeine DataStore-/Room-Speicherung bleibt lokale App-Speicherung; diese Änderung ergänzt keine Verschlüsselungsbibliothek.

## Systembackup

S1 ist in der Implementierung korrigiert: Das Manifest behält `allowBackup = true`, verweist aber ausdrücklich auf `res/xml/backup_rules.xml` über `fullBackupContent` und auf `res/xml/data_extraction_rules.xml` über `dataExtractionRules`. Damit sind sowohl Android 11 und älter als auch das getrennte Cloud- und Geräteübertragungsregelwerk ab Android 12 abgedeckt. Alle drei Regelabschnitte verwenden dieselben Ausschlüsse:

| Backup-Domäne | Ausgeschlossener Pfad | Zweck |
| --- | --- | --- |
| `file` und `device_file` | `datastore/` | Gesamtes DataStore-Verzeichnis einschließlich temporärer Begleitdateien |
| `database` und `device_database` | `.` | Gesamtes Datenbankverzeichnis einschließlich Room-Datenbank, WAL, SHM und Journals |

`traewelling_prefs.preferences_pb` enthält Zugangsdaten, normale Einstellungen und den aktiven Fahrtcache gemeinsam. Deshalb werden mit diesen Regeln auch Serverwahl, App-Einstellungen, Erkennungs-Opt-in und aktive Begleitung nicht aus einem neuen Systembackup wiederhergestellt; nach Neuinstallation oder Gerätewechsel ist eine neue Anmeldung und Konfiguration erforderlich. Ebenso entfällt die Wiederherstellung privater Room-Feedcaches. Die vorhandenen lokalen Dateien auf dem aktuellen Gerät bleiben unverändert; es gibt keine Datenmigration oder neue Verschlüsselungsbibliothek. Andere nicht ausgeschlossene Dateibereiche bleiben grundsätzlich backupfähig. Eine spätere Einstellungssicherung benötigt einen getrennten, ausdrücklich geeigneten Speicher ohne Credentials.

Die Regeln folgen der [Android-Dokumentation zu Auto Backup](https://developer.android.com/identity/data/autobackup). Die ausdrücklichen Geräteübertragungsregeln sind erforderlich, da `allowBackup = false` allein bei manchen Herstellern Geräteübertragung nicht verhindert. Die Korrektur macht keine Aussage über die Verschlüsselung früherer Sicherungen und widerruft keine bereits ausgestellten Token. Den historischen Ausgangsbefund dokumentiert [Main-Review](../entwicklung/main-review-2026-10-06.md), S1.

TODO: Cloud-Restore und Geräteübertragung auf unterstützten Android-Versionen und einem Herstellergerät prüfen; XML-/Buildprüfung allein belegt keinen tatsächlich ausgeführten Systemrestore.

## Netzwerk-Logging

`RetrofitClient` nutzt `HttpLoggingInterceptor` nur im Debug-Build mit `BASIC`-Level. Release-Builds setzen das Netzwerk-Logging auf `NONE`.

Der Header `Authorization` wird explizit redaktiert. Dadurch sollen Bearer-Tokens nicht in Logcat oder Build-/Test-Ausgaben erscheinen. OAuth-Token-Antworten werden nicht auf Body-Level geloggt.

## Standort und Sperrbildschirm

Beim aktiven Stationsalarm bleiben Standortfixes und Bewegungshistorie lokal. Die Opt-in-Fahrterkennung nutzt dagegen `GET /api/v1/stations` über `TraewellingRepository.getNearbyStations`: Die aus dem aktuellen Standort berechneten Boxgrenzen `min_lat`, `max_lat`, `min_lon` und `max_lon` werden an den konfigurierten Träwelling-Server übertragen. Aus ihrer Mitte lässt sich die verwendete Geräteposition ableiten; Bounding-Box-Parameter anonymisieren den Standort nicht. Die manuelle Suche nach Stationen in der Nähe verwendet denselben Repository-Aufruf. Die Erkennungs-UI erklärt die Standortübertragung vor der Aktivierung. Kandidaten, GPS-Historie und Tripcache der Erkennung sind ausschließlich im RAM; es gibt keinen automatischen öffentlichen Check-in.

Auch [GPS-Zeitbeobachtungen und Prognosen](../module/gps-zeiten.md) der aktiven Begleitung bleiben ausschließlich im RAM. Die Erweiterung lädt keine Positionen oder Prognosewerte hoch, speichert sie nicht im Fahrtcache und führt keinen automatischen Status-PUT aus. Das Bearbeitungsformular erhält keine GPS-Schätzwerte.

`lock_screen_details_enabled = false` redaktiert die öffentlichen Anzeigen von Fahrt und Änderungshinweisen und unterdrückt die Live-Update-Promotion-Anfrage. Die Android-Sperrbildschirmeinstellungen bleiben maßgeblich. Der Debug-Interceptor bleibt im BASIC-Modus. `HttpLogSanitizer` redaktiert Standortparameter (`latitude`, `longitude`, `lat`, `lon` und die Bounding-Box-Varianten) vor Logcat; `RetrofitClient` verwendet dafür einen eigenen Logger. Release-Logging bleibt deaktiviert. Ein Gerätetest sollte dennoch die tatsächliche Logcat-Ausgabe prüfen.

## Nativer Träwelling-Streckenabruf

`TransitRouteRepository` gehört genau einem `AuthSession`-Snapshot und fragt ausschließlich `polyline/{statusId}` am konfigurierten HTTPS-Server an. Der Bearer-Token bleibt bei diesem Origin; Redirects, HTTP-Rückfall und Netzwerk-Logging sind deaktiviert. Status-ID und normale Verbindungsdaten sind für den eigenen Träwelling-Server sichtbar. Gerätefixes, Bewegungshistorie und Prognosewerte werden nicht zusätzlich übertragen; weder Transitous noch ein fremder Bahn-/Tramrouter erhält diese Anfrage.

Der begrenzte Formcache bleibt im RAM derselben Sitzung und wird beim Schließen gelöscht. Vollständige Besuchs-/Plan-/Koordinatenbasis und Fahrtgeneration schützen die Übernahme gegen späte Antworten. Größen-, Punkt-, Snap-, Quellenalter- und Mehrdeutigkeitsgrenzen verhindern ungeprüfte Verwendung, zertifizieren aber keine amtliche aktuelle Gleisführung. Native Formen werden nicht im DataStore-/Room-Cache gespeichert. Details: [Externe Schnittstellen](../api/externe-schnittstellen.md) und [GPS-Zeiten](../module/gps-zeiten.md).

## Öffentlicher SEV-Abruf

Die automatische [SEV-Ergänzung](../module/sev-haltestellen.md) fragt Bahnhofskarten mit einem eigenen anonymen HTTP-Client ab. Der Träwelling-Authorization-Interceptor wird nicht übernommen; es werden keine Zugangsdaten, Gerätepositionen oder GPS-Verläufe an bahnhof.de übertragen. Der Aufruf nennt den jeweiligen Bahnhof im URL-Pfad und übermittelt die übliche Netzwerkverbindung. Das Repository hält einen begrenzten Prozesscache; der aktive Fahrtcache kann öffentliche Karten zusätzlich zusammen mit der vollständigen API-Haltfolge im bestehenden DataStore-JSON speichern. Beim Wiederanlauf werden Quellenalter und Maßnahmenzeitraum erneut geprüft. Es gibt weder eine neue Preference noch eine Room-Tabelle und keine Persistenz von Gerätepositionen durch diese Ergänzung.

HTML wird ausschließlich nach eingebetteten JSON-Daten durchsucht. Webseiten-JavaScript wird nicht ausgeführt. Größen-, Host-, Zeit- und Koordinatenprüfungen verhindern, dass ein ungeprüfter Kartenmittelpunkt die Tracking-Koordinate ersetzt. Fehlende oder unsichere Ergebnisse erhalten den bestehenden API-Rückfall.

## Optionale öffentliche Straßenroute

`RoadRouteRepository` lädt für geeignete SEV-Abschnitte Geometrien vom festen HTTPS-Endpunkt `routing.openstreetmap.de/routed-car`. Sein eigener OkHttp-Client verwendet keine Account-Interceptors, Zugangsdaten, Redirects oder HTTP-Logging. Die Anfrage enthält ausschließlich die beiden veröffentlichten und lokal eindeutig zugeordneten Ersatzhaltkoordinaten. Gerätefix, Bewegungshistorie, Träwelling-Status-ID und Prognosezeiten werden nicht übertragen.

Diese Endpunktpaare beschreiben angefragte öffentliche Fahrtabschnitte und sind zusammen mit der üblichen IP-/Verbindungsinformation dem Anbieter sichtbar. Laut [Anbieterhinweis](https://routing.openstreetmap.de/about.html) werden Routenanfragen serverseitig protokolliert. Öffentliche Koordinaten bedeuten daher nicht, dass keine Nutzungsinformation übertragen wird. Die Opt-in-Fahrterkennung mit standortabgeleiteter Stationssuche bleibt davon getrennt.

Antwortgröße, Punktzahl, Snap, Koordinaten und Weglänge werden geprüft; Request-Starts sind begrenzt. Geometrien liegen ausschließlich im begrenzten RAM-Cache und werden weder als GPS-Historie noch als neues DataStore-/Room-Feld gespeichert. Generation, geordnete Besuchspaarung und unveränderte Endpunkte schützen die lokale Übernahme. Ein Pkw-Routenmodell gilt nicht als amtlicher Busweg; fehlerhafte oder mehrdeutige Geometrie führt zum bestehenden Zeitquellenrückfall. Details und Nutzungsgrenzen: [Externe Schnittstellen](../api/externe-schnittstellen.md).

## Verwandte Seiten

- [Umgebungsvariablen](./umgebungsvariablen.md)
- [Tests](../entwicklung/tests.md)
- [API Überblick](../api/ueberblick.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Reisefortschritt](../module/trip-progress.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
- [Externe Schnittstellen](../api/externe-schnittstellen.md)
