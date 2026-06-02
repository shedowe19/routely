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

## Verwandte Seiten

- [Umgebungsvariablen](./umgebungsvariablen.md)
- [Tests](../entwicklung/tests.md)
- [API Überblick](../api/ueberblick.md)
