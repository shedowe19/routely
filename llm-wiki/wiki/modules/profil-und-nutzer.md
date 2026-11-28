# Profile und Nutzersuche

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Profile zeigen eigene und fremde Träwelling-Konten, Statistiken und Status-
Listen. Die Nutzersuche findet Konten und führt in deren Profil.

## Eigenes Profil

- Lädt `auth/user`-Daten, Statistiken und die eigene Statusliste.
- Statistiken: Dauer als `Xh Ym`, Distanz mit deutschem Zahlenformat und
  `km`.
- Kategorien werden lokal ins Deutsche übersetzt; die Übersetzung ist in
  Profil- und Statusdetail-Screen dupliziert.
- Ein Refresh ersetzt die Ansicht erst nach erfolgreicher Antwort.

## Fremdes Profil

- Route `userProfile/{username}`.
- Lädt Profil und paginierte Statusliste.
- Folgen/Entfolgen ist optimistisch und wird über den Mutations-Ledger
  verarbeitet.
- Ein laufender Follow wird durch einen Refresh nicht abgebrochen; der Refresh
  wird zurückgestellt.
- Ein älterer Profil-GET darf einen bestätigten Follow/Unfollow nicht
  rückgängig machen. Frische Profilfelder werden trotzdem übernommen.
- Ein abgelehnter Follow-Fehler bleibt über einen Refresh hinweg sichtbar.

## Nutzersuche

- Route `userSearch`; Suchbegriff wird an `api/v1/user/search/{query}`
  gesendet.
- Ergebniszeilen führen ins Profil.
- Der Screen hat keinen eigenen Cache; jede Suche ist eine Netzwerkantwort.

## Navigation und Kodierung

- Feed, Suche und Statusdetail übergeben Benutzernamen unkodiert an
  `userProfile/...`; der Benachrichtigungs-Screen nutzt `Uri.encode`.
- Der Notification-Resolver akzeptiert nur `[A-Za-z0-9_]{1,25}`. Andere
  Aufrufer verlassen sich auf die Servergrammatik.

## Tests

`ProfileMutationControllerTest` — Update/Delete/Like in Profil-Listen,
Stale-GETs, Follow- und Karten-Mutationen nebeneinander.
`UserProfileFollowOrderingTest` — Follow-Reihenfolge, private Anfragen,
Profilwechsel, alte Sessions.
Es gibt keine UI-Tests für die Screens.

## Risiken

- Profil- und Statusdetail-Listen teilen den Mutations-Ledger mit dem Feed;
  Änderungen an der Reihenfolge betreffen alle drei.
- Die Kategorie-Übersetzung ist dupliziert und kann auseinanderlaufen.
- Unkodierte Benutzernamen sind nur sicher, solange die Servergrammatik keine
  Sonderzeichen zulässt.

## Verwandte Seiten

- [Feed und Statusdetails](feed-und-status.md)
- [UI und Design](../frontend/ui-und-design.md)
- [Träwelling-API](../api/traewelling-api.md)
- [Index](../index.md)
