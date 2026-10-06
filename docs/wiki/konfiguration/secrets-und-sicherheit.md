# Konfiguration: Secrets und Sicherheit

## Zweck

Sicherheitsrelevante Vorgaben für die Entwicklung.

## Regeln

- Niemals Test-User-Credentials, JWT Tokens oder Client Secrets in den Code oder das Git-Repository (und schon gar nicht hier ins Wiki!) pushen.
- Lokale `local.properties` verwenden, falls API-Keys für Entwickler-Builds benötigt werden, diese Datei ist standardmäßig in der `.gitignore`.
- Lokale `.env`-Dateien sowie lokale Signing-Dateien (`*.jks`, `*.keystore`) sind über `.gitignore` ausgeschlossen.
- API-Token für manuelle Tests dürfen nur lokal und nicht versioniert verwendet werden. Werte nie in Gradle-Befehle, CI-Logs, Markdown-Dateien oder Screenshots übernehmen.

## Netzwerk-Logging

`RetrofitClient` nutzt `HttpLoggingInterceptor` nur im Debug-Build mit `BASIC`-Level. Release-Builds setzen das Netzwerk-Logging auf `NONE`.

Der Header `Authorization` wird explizit redaktiert. Dadurch sollen Bearer-Tokens nicht in Logcat oder Build-/Test-Ausgaben erscheinen. OAuth-Token-Antworten werden nicht auf Body-Level geloggt.

## Standort und Sperrbildschirm

Beim aktiven Stationsalarm bleiben Standortfixes und Bewegungshistorie lokal. Die Opt-in-Fahrterkennung nutzt dagegen `GET /api/v1/stations` über `TraewellingRepository.getNearbyStations`: Die aus dem aktuellen Standort berechneten Boxgrenzen `min_lat`, `max_lat`, `min_lon` und `max_lon` werden an den konfigurierten Träwelling-Server übertragen. Aus ihrer Mitte lässt sich die verwendete Geräteposition ableiten; Bounding-Box-Parameter anonymisieren den Standort nicht. Die manuelle Suche nach Stationen in der Nähe verwendet denselben Repository-Aufruf. Die Erkennungs-UI erklärt die Standortübertragung vor der Aktivierung. Kandidaten, GPS-Historie und Tripcache der Erkennung sind ausschließlich im RAM; es gibt keinen automatischen öffentlichen Check-in.

Auch [GPS-Zeitbeobachtungen und Prognosen](../module/gps-zeiten.md) der aktiven Begleitung bleiben ausschließlich im RAM. Die Erweiterung lädt keine Positionen oder Prognosewerte hoch, speichert sie nicht im Fahrtcache und führt keinen automatischen Status-PUT aus. Das Bearbeitungsformular erhält keine GPS-Schätzwerte.

`lock_screen_details_enabled = false` redaktiert die öffentlichen Anzeigen von Fahrt und Änderungshinweisen und unterdrückt die Live-Update-Promotion-Anfrage. Die Android-Sperrbildschirmeinstellungen bleiben maßgeblich. Der Debug-Interceptor bleibt im BASIC-Modus. `HttpLogSanitizer` redaktiert Standortparameter (`latitude`, `longitude`, `lat`, `lon` und die Bounding-Box-Varianten) vor Logcat; `RetrofitClient` verwendet dafür einen eigenen Logger. Release-Logging bleibt deaktiviert. Ein Gerätetest sollte dennoch die tatsächliche Logcat-Ausgabe prüfen.

## Öffentlicher SEV-Abruf

Die automatische [SEV-Ergänzung](../module/sev-haltestellen.md) fragt Bahnhofskarten mit einem eigenen anonymen HTTP-Client ab. Der Träwelling-Authorization-Interceptor wird nicht übernommen; es werden keine Zugangsdaten, Gerätepositionen oder GPS-Verläufe an bahnhof.de übertragen. Der Aufruf nennt den jeweiligen Bahnhof im URL-Pfad und übermittelt die übliche Netzwerkverbindung. Das Repository hält einen begrenzten Prozesscache; der aktive Fahrtcache kann öffentliche Karten zusätzlich zusammen mit der vollständigen API-Haltfolge im bestehenden DataStore-JSON speichern. Beim Wiederanlauf werden Quellenalter und Maßnahmenzeitraum erneut geprüft. Es gibt weder eine neue Preference noch eine Room-Tabelle und keine Persistenz von Gerätepositionen durch diese Ergänzung.

HTML wird ausschließlich nach eingebetteten JSON-Daten durchsucht. Webseiten-JavaScript wird nicht ausgeführt. Größen-, Host-, Zeit- und Koordinatenprüfungen verhindern, dass ein ungeprüfter Kartenmittelpunkt die Tracking-Koordinate ersetzt. Fehlende oder unsichere Ergebnisse erhalten den bestehenden API-Rückfall.

## Verwandte Seiten

- [Umgebungsvariablen](./umgebungsvariablen.md)
- [Tests](../entwicklung/tests.md)
- [API Überblick](../api/ueberblick.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Reisefortschritt](../module/trip-progress.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
