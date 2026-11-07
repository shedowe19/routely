# Log

## [2026-11-04] bootstrap | LLM-Wiki für Routely initialisiert

- Command: Nutzerauftrag, ein LLM-Wiki für dieses Repository einzurichten: README lesen, alle relevanten Dateien modulweise inventarisieren, Wiki unter `llm-wiki/` aufbauen, keine Quellcode-Änderungen, keine Secrets, kein Commit.
- Type: bootstrap
- Areas affected: gesamtes Repository (App, Daten, Services, UI, CI, Tests, Dokumentation)
- Files changed: `llm-wiki/**`, `AGENTS.md` (nur der markierte LLM-WIKI-Block)
- Wiki pages updated: keine (Erstinitialisierung)
- New wiki pages: 38 Seiten unter `llm-wiki/wiki/` — Index; Überblick und Projektstand; Tasks, Log, Changelog; Architektur (3); API (2); Module (13); UI (1); Daten (2); Sicherheit (1); Build/Release (1); Teststrategie (1); Entscheidungen (5); Offene Fragen (1); Widersprüche (2). Vollständige Liste im [Index](index.md).
- New TODOs: T-001 bis T-010, M-001
- New bugs: keine bestätigten Defekte; ein Dokumentationswiderspruch und mehrere offene Fragen erfasst
- New decisions: [Native Streckenverläufe](decisions/2026-10-06-native-streckenverlaeufe.md), [Anonyme DBF/IRIS-Echtzeit](decisions/2026-10-10-anonyme-dbf-live-daten.md), [Sichere Storage-Recovery](decisions/2026-11-03-storage-recovery.md)
- Open questions: O-1 bis O-14 in [Offene Fragen](questions/offene-fragen.md)
- Next steps: T-001 (Dokumentationspfade) und T-002 (OAuth-Lücke) entscheiden; danach T-004 (Cache-Löschung) und T-003 (Geräte-Testmatrix)
- Verification: `node`-Linkprüfung über alle 38 Wiki-Seiten: alle relativen Links auflösen, jede Seite aus `index.md` erreichbar, keine Waise, elf Tasks mit Akzeptanzkriterium, genau ein LLM-WIKI-Markerpaar, Secret-Muster ohne Treffer — Ergebnis PASS.

## Hinweise zur Erhebung

- README, Manifest, Gradle-Dateien, CI-Workflows, Release-Guard, Test-Fixtures und alle Hauptpakete wurden gelesen. Große Dateien wie `TripTrackingService.kt` wurden über modulweise Subagenten-Notizen mit Quellenangaben erschlossen.
- Die frühere Dokumentation unter `docs/wiki/` war im Arbeitsbaum gelöscht. Auf ausdrückliche Nutzerentscheidung wurde sie nicht als Quelle übernommen; der Zustand ist als Widerspruch dokumentiert.
- Kein Build und kein Testlauf wurde ausgeführt; Befehle und Ergebnisse stammen aus der CI-Konfiguration. Diese Grenze steht in [Teststrategie](testing/teststrategie.md).
