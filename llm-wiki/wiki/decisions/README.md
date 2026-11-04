# Entscheidungen (ADR-Index)

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Architekturentscheidungen, die aus Code, Commit-Historie und Tests belegbar
sind. Jede ADR hält Kontext, Entscheidung, Folgen und Alternativen fest.

## Liste

| Datum | Titel | Status |
|---|---|---|
| 2026-10-06 | [Native Streckenverläufe für Bahn und Tram](2026-10-06-native-streckenverlaeufe.md) | accepted |
| 2026-10-10 | [Anonyme DBF/IRIS-Echtzeit](2026-10-10-anonyme-dbf-live-daten.md) | accepted |
| 2026-11-03 | [Sichere Storage-Recovery](2026-11-03-storage-recovery.md) | accepted |

## Regeln

- Eine ADR wird nur angelegt, wenn die Entscheidung im Code oder in der
  Commit-Historie belegbar ist.
- Spätere Änderungen überschreiben keine alte ADR; sie wird als `superseded`
  markiert und durch eine neue ersetzt.
- Vermutungen gehören unter „Offene Fragen“, nicht in eine ADR.

## Vorlage

[adr-template.md](adr-template.md)

## Verwandte Seiten

- [Überblick](../overview.md)
- [Index](../index.md)
