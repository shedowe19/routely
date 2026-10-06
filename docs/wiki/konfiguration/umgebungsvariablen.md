# Konfiguration: Umgebungsvariablen

## Zweck

Dokumentation von verwendeten Secrets, Properties oder API Keys.

## Variablen

Aktuell werden in Gradle `versionCode` und `versionName` per Property bezogen (`project.findProperty`).

| Variable                   | Zweck                                    | Erforderlich | Hinweis                                                                                            |
| -------------------------- | ---------------------------------------- | ------------ | -------------------------------------------------------------------------------------------------- |
| `versionName` / `versionCode` | APK-Version | Optional lokal | Gradle-Properties; Standard `1.0.0` / `1`. Der manuelle Release-Workflow verlangt und validiert beide Werte. |
| `OAuth Client ID / Secret` | Vorhandene OAuth-Helfer | Nein für den aktuellen Login | Keine Gradle-/Environment-Anbindung. Der erreichbare manuelle Token-Login verwendet diese Werte nicht. |
| `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD` | Release-Signierung | Nur im manuellen Release-Workflow | GitHub-Actions-Secrets; Werte niemals dokumentieren. |

API-Server und manueller Access-Token sind Laufzeiteingaben des [Auth-Moduls](../module/auth.md), keine Build-Umgebungsvariablen. `RELEASE_VERSION_NAME` und `RELEASE_VERSION_CODE` sind interne Jobvariablen aus validierten Workflow-Eingaben; die Shell übernimmt sie als gequotete Argumente. `WIKI_TOKEN` wird ausschließlich im Wiki-Sync-Workflow benötigt, nicht in der Android-App.

## Verwandte Seiten

- [Secrets und Sicherheit](./secrets-und-sicherheit.md)
- [Build](../entwicklung/build.md)
- [Auth](../module/auth.md)
