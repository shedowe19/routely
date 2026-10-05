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

Beim aktiven Stationsalarm bleiben Standortfixes und Bewegungshistorie lokal. Die neue Opt-in-Fahrterkennung nutzt dagegen `trains/station/nearby`: Für diese Anfrage werden aktuelle Koordinaten an den konfigurierten Träwelling-Server übertragen. Die UI erklärt diesen Zweck vor der Aktivierung. Kandidaten, GPS-Historie und Tripcache der Erkennung sind ausschließlich im RAM; es gibt keinen automatischen öffentlichen Check-in.

`lock_screen_details_enabled = false` redaktiert die öffentlichen Anzeigen von Fahrt und Änderungshinweisen und unterdrückt die Live-Update-Promotion-Anfrage. Die Android-Sperrbildschirmeinstellungen bleiben maßgeblich. Der Debug-Interceptor bleibt im BASIC-Modus. `HttpLogSanitizer` redaktiert Standortparameter (`latitude`, `longitude`, `lat`, `lon` und die Bounding-Box-Varianten) vor Logcat; `RetrofitClient` verwendet dafür einen eigenen Logger. Release-Logging bleibt deaktiviert. Ein Gerätetest sollte dennoch die tatsächliche Logcat-Ausgabe prüfen.

## Verwandte Seiten

- [Umgebungsvariablen](./umgebungsvariablen.md)
- [Tests](../entwicklung/tests.md)
- [API Überblick](../api/ueberblick.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Reisefortschritt](../module/trip-progress.md)
