# Tasks

> Eine Task = eine Checkbox = ein prüfbares Akzeptanzkriterium.
> Erledigt heißt: geprüft, datiert und nach **Done** verschoben — mit Beleg.
> Tasks werden nie gelöscht, nur verschoben.

## Now

- [ ] **T-001 Dokumentationspfade entscheiden.** Akzeptanz: Entweder verweisen `README.md` und `.github/workflows/wiki-sync.yml` auf `llm-wiki/` (bzw. einen bewusst gewählten Pfad), oder `docs/wiki/` ist wiederhergestellt; `grep -rn "docs/wiki" README.md .github/workflows` liefert nur noch beabsichtigte Treffer.

- [ ] **T-002 OAuth-Lücke schließen.** Akzeptanz: Entweder verarbeitet `MainActivity` `intent.data`, tauscht den Code ein und speichert Refresh-Credentials mit Test, oder Manifest-Filter, `OAuthHelper`, `exchangeCodeForToken` und `refreshAccessToken` sind entfernt; `:app:testDebugUnitTest` und `:app:assembleDebug` laufen grün.

- [ ] **T-003 Geräte-Testmatrix festlegen und ausführen.** Akzeptanz: Eine dokumentierte Matrix (mindestens Android 14 und 15, ein OEM-Gerät) existiert, und ein Lauf belegt Tracking-Start/-Stopp, Notification-Aktion und Widget-Aktualisierung.

## Next

- [ ] **T-004 Feed-Cache bei Logout/Tokenwechsel löschen.** Akzeptanz: Ein Test zeigt, dass nach Logout und erneutem Login die Room-Zeilen des alten Token-Hashes nicht mehr vorhanden sind und der neue Account keinen alten Feed sieht. Quelle: `data/repository/TraewellingRepository.kt:118-125`.

- [ ] **T-005 `targetSdk` und `onTimeout` entscheiden.** Akzeptanz: Eine ADR oder ein Kommentar dokumentiert die Entscheidung zu `targetSdk 34` vs. `compileSdk 36`; das Verhalten von `onTimeout` ist auf Android 15+ einmal belegt.

- [ ] **T-006 Toten Code und ungenutzte Abhängigkeiten entfernen.** Akzeptanz: `api/v1/trains/station/nearby`, `libs.browser` und `TraewellingRed` sind entfernt oder nachweislich verwendet; Build und Tests bleiben grün.

- [ ] **T-007 Navigations-Intent beim Kaltstart puffern.** Akzeptanz: Ein Test zeigt, dass eine `NavigationRequest` mit Sessionrevision, die vor der Auth-Hydration eintrifft, nach der Hydration einmal ausgeführt wird. Quelle: `ui/navigation/AppNavigation.kt:53-61`.

- [ ] **T-008 Benutzernamen-Kodierung vereinheitlichen.** Akzeptanz: Ein Test mit `/`, `?` und `#` im Benutzernamen kann keine fremde Route öffnen; alle Aufrufer kodieren gleich. Quelle: `ui/navigation/AppNavigation.kt:150`, `:207`, `:221`.

- [ ] **T-009 Widget-Aktualität verbessern.** Akzeptanz: Das Widget zeigt entweder einen Aktualisierungszeitpunkt oder wird beim App-Start einmal aktualisiert; ein Test oder eine dokumentierte Geräteprüfung belegt es.

- [ ] **T-010 Lizenz klären.** Akzeptanz: Eine `LICENSE`-Datei ist vorhanden, oder `README.md` sagt ausdrücklich, dass keine Weiterverbreitungslizenz erteilt ist.

## Backlog candidates (unverified)

- Keine `TODO`-, `FIXME`-, `XXX`- oder `HACK`-Marker in `app/src/main/kotlin/` oder `app/src/test/kotlin/` gefunden (Suche am 2026-11-04). Der einzige `XXX`-Treffer war das Datumsformat `dd.MM.yyyy · HH:mm XXX`, kein Marker.

- Offene Punkte ohne Akzeptanzkriterium stehen in
  [Offene Fragen](questions/offene-fragen.md). Sie werden erst zu Tasks, wenn
  eine prüfbare Bedingung formuliert ist.

## Maintenance

- [ ] **M-001 Lint-Pass.** Ausführen, wenn das Wiki um etwa 20 Seiten wächst oder vor einem Release. Akzeptanz: Widersprüche, überholte Aussagen, verwaiste Seiten, fehlende Seiten und Querverweise, gebrochene Links, Fast-Duplikate und offene Fragen geprüft; Befunde als Tasks erfasst; Pass im Log vermerkt.

## Done

Noch keine erledigten Tasks.
