# Konfiguration: Umgebungsvariablen

## Zweck

Dokumentation von verwendeten Secrets, Properties oder API Keys.

## Variablen

Aktuell werden in Gradle `versionCode` und `versionName` per Property bezogen (`project.findProperty`).

| Variable                   | Zweck                                    | Erforderlich | Hinweis                                                                                            |
| -------------------------- | ---------------------------------------- | ------------ | -------------------------------------------------------------------------------------------------- |
| `OAuth Client ID / Secret` | Authentifizierung an der Traewelling-API | Ja           | Wert nicht dokumentieren, wird oft vom User zur Laufzeit oder via lokale properties bereitgestellt |

## Verwandte Seiten

- [Secrets und Sicherheit](./secrets-und-sicherheit.md)
