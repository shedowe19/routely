# Modul: UserProfile

## Zweck

Zeigt Profile anderer Nutzer mit deren Check-in-Historie und Follow-Funktionalität.

## Kontext

Der Nutzer kann Profile anderer Nutzer aufrufen über den Feed oder die Benutzer-Suche. Das Profil zeigt vergangene Fahrten und ermöglicht Follow/Unfollow.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/UserProfileScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/UserProfileViewModel.kt`

## Verhalten

### Lade-Prozess

1. `loadUserProfile(username)` lädt User-Daten und Status-Historie
2. `loadMoreStatuses()` paginiert durch vergangene Check-ins
3. `toggleFollow()` folgt oder entfolgt einem Nutzer

Ein Profilwechsel beendet laufende Profil-/Historien- und Follow-Aufträge, erhöht die Generation und leert den vorherigen Profilzustand. Späte Antworten dürfen nur zum weiterhin angezeigten Nutzer übernommen werden. Pagination wird nach Status-ID dedupliziert; eine laufende Follow-Aktion kann nicht durch einen zweiten Tipp parallel gestartet werden. Beim Konto-/Sitzungswechsel wird das gesamte Feature-ViewModel neu aufgebaut.

### Follow-Logik

- Folgt noch nicht: `POST /api/v1/user/{id}/follow`
- Folgt bereits: `DELETE /api/v1/user/{id}/follow`
- Private Profile zeigen "Angefragt" (followPending) statt "Folgen"

Bei einer offenen privaten Folgeanfrage ist der Button deaktiviert. Der bestehende Unfollow-Endpunkt löscht bestätigte Follow-Beziehungen, keine Pending-Anfrage; die UI bietet deshalb keinen unbelegten Abbruch dieser Anfrage an.

### Auto-Load-More

Wenn der Nutzer in der LazyColumn scrollt und noch mehr Status-Seiten verfügbar sind (via `links.next`), werden automatisch weitere geladen.

### UI-Darstellung

- Nutzerkopf als Gradient-Hero-Card mit Avatar, Benutzername, Bio, Follow-Button und Statistik-Chips
- Statistik-Chips zeigen Distanz, Zeit und Punkte kompakt einzeilig; Kilometer werden mit deutschem Tausenderpunkt formatiert
- Lade-, Fehler- und Empty-States via `StateMessage`
- Sichtbare Fahrten werden über `StatusCard` dargestellt. `onLike = null` deaktiviert die Herz-Aktion ausdrücklich; Like/Unlike bleibt im Feed verfügbar.

## UI-Zustand (UserProfileUiState)

| Feld              | Typ          | Beschreibung                   |
| ----------------- | ------------ | ------------------------------ |
| `isLoading`       | Boolean      | Ladezustand                    |
| `user`            | User?        | Geladene Nutzerdaten           |
| `statuses`        | List<Status> | Check-in-Historie              |
| `hasMore`         | Boolean      | Weitere Seiten verfügbar       |
| `currentPage`     | Int          | Aktuelle Seiten-Nummer         |
| `isFollowLoading` | Boolean      | Follow/Unfollow in Bearbeitung |

### Gemeinsame Statusmutationen

Android-freie Profilcontroller beobachten den revisionsgebundenen `StatusMutation`-Flow. Geänderte bereits geladene Karten werden ersetzt, gelöschte entfernt; unbekannte Karten werden nicht eingefügt. Ältere Profil-/Statusanfragen dürfen nach der Mutation keinen alten Snapshot zurückbringen. Unvollständige erfolgreiche PUT-Antworten erhalten eine aktuelle Seite-1-Verifikation. Ein Wechsel von Sitzung oder angezeigtem Nutzer bleibt eine eigene Grenze.

Das eigene Profil lädt beim erneuten Einfügen der Composition weiterhin über `LaunchedEffect(Unit)`. Das Nutzerprofil überspringt eine bereits erfüllte Ladung desselben Namens; deshalb benötigt besonders die Rückkehr aus einem bearbeiteten eigenen Status dessen unmittelbare Mutationsübernahme. Statusmutationen und ein Refresh desselben Profils beenden keinen abgeschickten Follow-Auftrag; eigene Besitzer und ein gegebenenfalls nachgelagerter Abruf erhalten seinen Abschluss. Ein Followfehler bleibt auch nach erfolgreicher Kartenverifikation sichtbar, bis er ausdrücklich verworfen wird, eine neue Followaktion beginnt oder das Profil wechselt.

## Abhängigkeiten

- **TraewellingRepository**: Für API-Aufrufe (getUserProfile, getUserStatuses, followUser, unfollowUser)
- **PreferencesManager**: Für Auth-Token
- **StateMessage**: Einheitliche UI für Lade-, Fehler- und Empty-States

## Offene Fragen

- D11 ist korrigiert: Profil-GETs tragen die Beziehungsrevision ihres Starts. Eine anschließend bestätigte Follow-/Unfollow-/Privatanfrage überlagert nur deren alte Beziehungsfelder; andere frisch geladene Profilfelder bleiben erhalten. Erst ein nach der Bestätigung gestarteter GET ist für die Beziehung wieder maßgeblich. Profil-/Sitzungswechsel verwerfen die alte Absicht; Fehler und notwendige Statusverifikation behalten ihre eigenen Grenzen. Regression: `UserProfileFollowOrderingTest`.
- Fehler bleiben auch mit vorhandenem Profil sichtbar. Bei einem Paginationfehler pausiert automatisches Nachladen, statt ohne Nutzereingriff denselben fehlgeschlagenen Auftrag zu wiederholen.

## Flutter-Umsetzung

`flutter/lib/features/users/` übernimmt fremde Profile, Historie und Folgen/Anfrage. Verspätete Antworten bleiben an die geöffnete Identität gebunden. Das explizite Backendfeld `userInvisibleToMe` entfernt bereits geladene und offline angezeigte Fahrten sofort und unterbindet Historien-, Cache- und Paginationabrufe. Ein gewöhnlicher Ladefehler ohne diesen belegten Sperrzustand darf passende vorhandene Daten behalten. Weitere Details stehen in der [Flutter-Architektur](../architektur/flutter-migration.md). Die bisherigen Kotlin-Verträge bleiben die Verhaltensreferenz.

## Verwandte Seiten

- [Feed](./feed.md)
- [API Überblick](../api/ueberblick.md)
- [Offene Fragen](../offene-fragen.md)
