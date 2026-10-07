# Entscheidung: Aktive Fahrtrevision und getrennte GPS-Ereignisse

## Datum und Status

07.10.2026 — Akzeptiert.

## Zweck und Kontext

Die acht Befunde G8–G10/D9–D11/U8–U9 des [weiteren Main-Reviews](../entwicklung/main-review-2026-10-06.md#weiterer-nachreview-von-main-443d6e1) betreffen bestätigte Änderungen, unterbrochene Anfragen und unabhängig belegte Bewegung. Die Korrekturen erhalten vorhandene Genauigkeits-, Besuchs-, Konto- und Forecastgrenzen.

## Wichtige Dateien

- `data/repository/StatusMutation.kt`, `TraewellingRepository.kt`
- `service/ActiveTripStatusBridge.kt`, `TripTrackingService.kt`
- `service/StationTrackingEngine.kt`, `GpsJourneyTimeEstimator.kt`
- `viewmodel/FeedController.kt`, `UserProfileController.kt`
- `viewmodel/CheckInNavigationGuard.kt`, `CheckInViewModel.kt`
- `ui/navigation/AppNavigation.kt`, `ui/screens/CheckInScreen.kt`

Kotlin-Pfade beziehen sich auf `app/src/main/kotlin/de/traewelling/app/`.

## Entscheidungen

1. Eine aktive Fahrt gehört zu einer bestätigten Statusrevision. Der Service übernimmt passende Änderungen und deren vollständige Besuchsbasis; eine ältere Detailantwort darf weder die Route noch den alten Zielabschluss zurückbringen. Automatische Zielansage und Abschluss werden zusätzlich gegen die gemeinsame Status-Schreibreihenfolge geprüft. Erneute Prüfungen nach Suspensionen schützen den tatsächlichen Effekt, nicht nur den Beginn eines Abrufs. Contentrevision ist vom Like-/Feedepoch getrennt und begrenzt. Frische GETs installieren eine Statusbasis; unbekannte eviktierte Basis wird verifiziert. Der bestätigte Commit invalidiert den passenden alten persistenten Fahrtcache, während neue Basis und Fortschritt unter derselben Status-Schreibreihenfolge gespeichert werden. Die aktive ID bleibt erhalten; fehlgeschlagene lokale Speicherung ist keine prozessübergreifende Garantie.
2. Native Abfahrts-/Lückenbelege kumulieren gerichtete Chainage auf unveränderter Formbasis. Ein frisches Vorwärtspaar bleibt erforderlich; Qualitätsfehler, Sprung, Lücke, Rückwärtsbewegung und echte Formänderung beginnen neue Belege. Replays liefern keine zusätzliche Bewegung.
3. Physische GPS-Abfahrt und zeitliche Prognose sind getrennt. Ein ausstehender Beleg kann den genau geordneten Übergang zwischen nahen Halten überleben. Fehlende Folge-Planankunft oder mehr als 90 Minuten Fahrt sperren weiterhin die ETA; bestätigter Aufenthalt, unterstützte Projektion, Bewegung und Ausfahrtsradius können trotzdem eine tatsächliche Abfahrt belegen. Die Beobachtungszeit bezeichnet den unterstützten Ausfahrtsfix.
4. Eine Mutation darf einen notwendigen Feedabruf abbrechen, muss dessen aktuellen Tab-/Seitenbesitzer aber erhalten und erneut laden. Unvollständige PUT-Antworten benötigen weiterhin aktuelle Seite 1. Bestätigte Followabsicht überlagert ausschließlich ältere Beziehungsfelder, während neue sonstige Profilfelder erhalten bleiben; ein später gestarteter GET wird wieder maßgeblich.
5. Navigation schützt einen angenommenen Check-in in CONFIRM und SUCCESS auch vor System-Zurück. Gewöhnliche Schrittwechsel gehören nur zur sichtbaren Check-in-Page; der sichtbare Main-Eintrag schützt die offene Erstellung auch nach Tabwechsel. Die Herkunft der Zielauswahl bestimmt den Rückweg, ohne eine nie ausgeführte Abfahrtsanfrage vorzutäuschen.

## Konsequenzen und Alternativen

Ein reiner Polling-Fix wäre vor dem alten Ziel zu spät und würde bereits gestartete GETs nicht schützen. Eine größere GPS-Toleranz würde unbelegte Bewegung akzeptieren; die Korrektur erhält deshalb Belege über unterstützte kurze Paare beziehungsweise den Nachbarübergang. Ein bloßes Abschalten des Followbuttons während Refresh würde die allgemeinere Antwortreihenfolge nicht lösen.

Es gibt keine neue produktive Abhängigkeit, Endpunktänderung oder Datenbankmigration. Gerätepositionen bleiben im RAM und werden nicht an Träwelling übertragen. Kontrollierte Coroutine- und Parser→Engine→Estimator-Regressionen prüfen die Codeverträge; sie ersetzen keine Android-Gerätefahrt.

## Offene Fragen

- TODO: API-26–30-System-/Gesten-Zurück, Rotation und Prozessneustart auf dem Gerät prüfen.
- TODO: GPS-Güte, Tunnelrückkehr, TTS, OEM-/Doze-Zustellung sowie große Schrift und TalkBack praktisch prüfen.
- Tatsächlich ausgeführte automatische Nachweise stehen unter [Tests](../entwicklung/tests.md).

## Verwandte Seiten

- [Main-Review](../entwicklung/main-review-2026-10-06.md)
- [TripTracking](../module/trip-tracking.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [Check-in](../module/checkin.md)
- [Feed](../module/feed.md)
- [UserProfile](../module/user-profile.md)
- [Tests](../entwicklung/tests.md)
