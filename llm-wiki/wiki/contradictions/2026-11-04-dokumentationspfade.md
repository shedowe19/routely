# Dokumentationspfade zeigen auf gelöschtes docs/wiki

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite hält einen Konflikt fest, bei dem drei Quellen nicht
zusammenpassen. Sie entscheidet nichts, sondern macht den Widerspruch und die
nächsten Schritte sichtbar.

## Konflikt

Drei belegbare Aussagen passen nicht zusammen:

1. `README.md:31` und `README.md:44-48` verlinken die Projekt-Dokumentation
   unter `docs/wiki/`.
2. `.github/workflows/wiki-sync.yml` beobachtet `docs/wiki/**`, benennt
   `index.md` in `Home.md` um und synchronisiert den Ordner in ein GitHub-Wiki.
3. Im Arbeitsbaum ist `docs/wiki/` vollständig gelöscht: 65 Dateien,
   etwa 5.443 Zeilen (uncommitteter Stand, `git status`).

Zusätzlich wurde `AGENTS.md` im Arbeitsbaum auf 0 Byte geleert; in `HEAD` hat
es 402 Zeilen mit Wiki-Pflegeanweisungen.

## Was das bedeutet

- Die README-Links sind aktuell tot.
- Der Wiki-Sync-Workflow hat keinen Quellordner mehr und wird bei einem Push
  auf `main` nichts Sinnvolles synchronisieren.
- Die Löschung ist nicht committet. Ein `git checkout -- docs/wiki` würde sie
  rückgängig machen; ein Commit würde sie festschreiben.

## Was nicht geklärt ist

- Ob die Löschung beabsichtigt war (Vorbereitung für `llm-wiki/`) oder ein
  Zwischenstand.
- Ob die alten Inhalte migriert, archiviert oder endgültig verworfen werden
  sollen. Bei der Initialisierung wurde entschieden, sie **nicht** als Quelle
  zu verwenden.
- Ob `AGENTS.md` bewusst geleert wurde.

## Nächster Schritt

Task T-001 in [Tasks](../tasks.md): Pfade in README und CI auf die neue
Dokumentation umstellen oder den alten Ordner wiederherstellen — mit einer
bewussten Entscheidung, nicht implizit.

## Quellen

- `README.md:31`, `README.md:44-48`
- `.github/workflows/wiki-sync.yml`
- `git status --short` (2026-11-04)
- `AGENTS.md` (0 Byte) vs. `git show HEAD:AGENTS.md` (402 Zeilen)

## Verwandte Seiten

- [Aktueller Projektstand](../current-project-state.md)
- [Offene Fragen](../questions/offene-fragen.md)
- [Index](../index.md)
