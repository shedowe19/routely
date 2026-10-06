# Entscheidung: Geordnete Mutationen und dauerhafte Versionsclaims

## Datum und Status

06.10.2026 — Akzeptiert.

## Zweck und Kontext

Die zwölf zusätzlichen Befunde des [Main-Nachreviews](../entwicklung/main-review-2026-10-06.md) betreffen belegte Bewegung, Formularabsicht, verspätete Antworten und verlorene Veröffentlichungsnachweise. Die Umsetzung schließt diese Pfade sowie konkret bestätigte Folgefälle der unabhängigen Gegenprüfung. Sie erweitert weder Standortübertragung noch automatische Check-ins und verändert keine produktiven Bibliothekspins.

## Wichtige Dateien

- `service/GpsJourneyTimeEstimator.kt`, `TrackingRouteGeometry.kt`, `TrackingLocationObservation.kt`, `TripTrackingService.kt`
- `viewmodel/FeedController.kt`, `NotificationController.kt`, `StatusDetailPresentation.kt`, `StatusDetailViewModel.kt`
- `viewmodel/CheckInNavigationGuard.kt`, `CheckInViewModel.kt`, `SettingsSpeechInitializer.kt`, `SettingsViewModel.kt`
- `data/repository/StatusMutation.kt`, `TraewellingRepository.kt`
- `.github/scripts/release_guard.py`, `.github/workflows/android.yml`

Kotlin-Pfade beziehen sich auf `app/src/main/kotlin/de/traewelling/app/`.

## Entscheidungen

1. Abfahrt nutzt kumulierte Bewegung seit dem bestätigten inneren Aufenthaltsanker und einen frischen geordneten Vorwärtsübergang. Eine ungenaue neue Beobachtung muss einen transienten Wiederverankerungsbeleg unterbrechen. Endpunktzulässigkeit wird gegen den vollständigen Linienzug geprüft; benachbarte innere Kanten ersetzen keinen ausgeschlossenen realen Endpunkt. Ein bestätigter Anker knapp vor dem Ursprung erlaubt nur bei eindeutiger innerer Unterstützung Kettenlänge null, keine mehrdeutige Kurvenzuordnung.
2. Sichtbarkeit ist ausdrückliche Editierabsicht. Eine unveränderte Sichtbarkeit wird ausgelassen. Composition-Dispose beendet nur ihre eigene Beobachtungslease; Entwurf und abgeschickte Mutation gehören zum erhaltenen Fachzustand derselben Sitzung und Status-ID. Aktuelle VM-Zustände sperren auch einen sehr schnellen Zurücktipp vor der nächsten UI-Recomposition. Bestätigte Löschbereinigung ist von der angezeigten Fahrt getrennt; Navigation gehört ausschließlich zur aktuellen Composition.
3. PUT/DELETE/Like/Unlike desselben Credentialdigests und derselben Status-ID werden prozessweit durch 64 gemeinsame Mutexstreifen geordnet. Warten bleibt abbrechbar; bereits abgeschickte HTTP-Schreibaufträge behalten ihre Sperre bis Antwort und Cache-/Eventübernahme. Der HTTP-Calltimeout beträgt 60 Sekunden. Like-Absichten überlagern ältere GETs; Bodyless-Erfolg behält die notwendige aktuelle Seite-1-Verifikation über weitere Mutationen hinweg. Profil- und Feedverbraucher übernehmen ausschließlich revisionspassende Mutationen in bereits passende Karten und sperren alte Antworten.
4. MarkAll bestätigt nur vor dem Auftrag bekannte IDs. Ein GET-Start ist kein Beleg des späteren Serversnapshotzeitpunkts. Nach Erfolg wird der Serverstand neu geladen und alte Listen werden verworfen; neue Meldungen erhalten keine globale Gelesen-Markierung. Rollback erhält mindestens die sichtbar ungelesene Menge zusätzlich zur vorherigen Zählerbasis.
5. Settings entdecken installierte TTS-Servicepakete unabhängig vom erfolgreichen Synthesestart. Initfehler und zehn Sekunden ohne Rückmeldung bieten sichtbaren Wiederanlauf; Generationen sperren alte Callbacks. Schalter besitzen eine gelabelte gemeinsame Aktion, Auswahlfelder und Tokenanzeige eigene Beschriftungen.
6. Jeder neue Android-Versionscode wird vor Build/Signierung atomar als annotierter `routely-version-code/<code>`-Claim verbraucht. Code, Versionsname, Commit sowie Workflowlauf/-versuch werden zusammen validiert. Fehlbuild und administrative Release-Rücknahme geben diese Werte nicht frei. Ältere nicht belegte Versionshistorie stoppt die Pipeline. Claims werden durch den Helfer weder aktualisiert noch gelöscht.

## Konsequenzen und Alternativen

Vollständige PUT-Antworten werden nicht nur nach Ankunftsreihenfolge sortiert: Requestgeneration allein beweist keine Servercommitfolge. Prozessweite Serialisierung hält diese Reihenfolge ohne unbeschränktes Register. Hashkollisionen können unabhängige Schreibaufträge zusätzlich serialisieren. Lokale Cachearbeit besitzt keine garantierte HTTP-Zeitfrist; Android-Prozessende kann jede In-Memory-Fertigstellung unterbrechen.

Ein fehlgeschlagener Releaseversuch verbraucht Code und Versionsname. Wiederholung verwendet eine neue Version statt Claimlöschung. Der separate Claim schützt zurückgezogene Releases; er ist kein neu eingerichtetes GitHub-Ruleset. Wenn Administratoren sämtliche Claim-, Release- und Tagnachweise löschen, ist die gelöschte Historie nicht rekonstruierbar.

Regressionen verwenden plausible synthetische GPS-Folgen, virtuelle Coroutinezeit und Offline-API-Doubles. Keine echte Nutzerfahrt, FLP-/TTS-Zustellung, Rotation, TalkBack-, Doze-, Backup-Restore- oder signierte Veröffentlichung wird dadurch bewiesen.

## Abhängigkeiten und offene Fragen

Es gibt keine neue produktive Bibliothek oder Datenbankmigration. Profilcontroller und TTS-Initialisierung besitzen Android-freie Testgrenzen; die UI behält ihre bisherigen Anwendungskonstruktoren. Claims verwenden GitHub-Git-Tagobjekte und Create-ref mit vorhandenen `contents: write`-Rechten.

- TODO: Geräte-/OEM-/TalkBack-/Rotation-/Restore-Matrix und ersten signierten Workflow praktisch ausführen.
- TODO: Weitere Ausbauideen des Reviews getrennt bewerten; sie sind keine verbleibenden bestätigten Codebefunde.

## Verwandte Seiten

- [Main-Review](../entwicklung/main-review-2026-10-06.md)
- [Tests](../entwicklung/tests.md)
- [Deployment](../entwicklung/deployment.md)
- [Feed](../module/feed.md)
- [StatusDetail](../module/status-detail.md)
- [Notifications](../module/notifications.md)
- [Settings](../module/settings.md)
