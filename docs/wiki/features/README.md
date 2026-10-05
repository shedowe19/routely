# Features: Übersicht

## Zweck

Codebasierte Übersicht des vorhandenen Funktionsumfangs, Stand 05.10.2026. Erreichbare Funktionen und vorhandene Hilfsfunktionen werden getrennt beschrieben.

## Vorhandene Funktionen

| Bereich | Erreichbarer Funktionsumfang |
| --- | --- |
| [Anmeldung](../module/auth.md) | Server-URL und manueller Access-Token, Tokenprüfung, Willkommen-Hinweis und Logout. |
| [Check-in](../module/checkin.md) | Stationssuche, Stationen in der Nähe, Abfahrten mit Gleis/Verspätung/Ausfall, Fahrt und Ausstieg auswählen, Status-Text, Reisegrund und manuelle Zeiten. |
| [Fahrtdetail](../module/status-detail.md) | Haltfolge, Plan-/Echtzeit, Gleise, Ausfälle, Einstieg/Ausstieg und gemeinsamer Fortschritt der aktiven Fahrt. Eigene Fahrt bearbeiten oder löschen. |
| [Stationsalarm](../module/trip-tracking.md) | Geordnete GPS-Halterkennung, Zielhinweis, automatische oder feste Ansageentfernung, gekennzeichneter Fahrplan-Rückfall. |
| [Sprachausgabe](../module/settings.md) | Haltestellen- und Zielansagen; TTS-Engine, Sprache und Stimme konfigurierbar. |
| [Fahrterkennung](../module/ride-recognition.md) | Ausdrücklich aktivierte GPS-Suche nach möglichen Fahrten; Linie prüfen, Ziel wählen und Check-in selbst bestätigen. |
| [Fahrtänderungen](../module/trip-changes.md) | Hinweise zu Gleiswechseln, Haltausfällen/Wiederherstellungen und Verspätungsänderungen ab fünf Minuten. Optional TTS. |
| [Fahrtbenachrichtigung](../module/trip-progress.md) | Linie, nächster Halt, verbleibende Halte, Zielzeit und Quelle; API 36 ProgressStyle, systemabhängige Live-Update-Hervorhebung und Sperrbildschirm-Privatsphäre. |
| [Homescreen-Widget](../module/widget.md) | Linie, nächster Halt/Ziel, Zeit, Gleis und Verspätung aus dem Tracking-Service. |
| [Feed](../module/feed.md) | Freunde-/Global-Feed, Aktualisierung, weitere Seiten, Likes und Einstieg in Profile/Fahrtdetails. |
| [Profile](../module/profile.md) und [Nutzersuche](../module/user-search.md) | Eigenes Profil mit Statistik und letzten Fahrten, fremde Profile mit Historie sowie Folgen/Entfolgen und private Folgeanfragen. |
| [Meldungen](../module/notifications.md) | Benachrichtigungen mit Ungelesen-Badge, Aktualisierung und Gelesenmarkierung. |
| [Darstellung](../module/settings.md) | Hell, Dunkel und AMOLED; gemeinsame Lade-, Fehler- und Leerzustände. |
| [Punkte](./points-enabled.md) | Anzeige des API-Punktesystems einschließlich deaktivierter Punkte. |

## Teilweise vorhandene Grundlagen

- **Teilcache:** Die erste Freunde-/Global-Feed-Seite liegt in Room. Der Tracking-Service speichert die zuletzt geladene aktive Haltfolge und den Besuchsfortschritt für Ausfälle und Wiederanlauf. Eine neue Reise vollständig offline suchen oder starten ist nicht implementiert.
- **OAuth/PKCE und Token-Erneuerung:** [Hilfsfunktionen](../module/auth-pkce.md) sind vorhanden, aber der erreichbare Login bietet nur die Token-Eingabe. Ein automatischer Refresh ist nicht angebunden.
- **Sichtbarkeit:** Datenmodell und Fahrtdetail-ViewModel halten Sichtbarkeitswerte; die Check-in- und Bearbeitungsdialoge bieten keine Auswahl.
- **Profil-Likes:** Die Fahrtkarten zeigen Herzen, ihre Handler im eigenen und fremden Profil sind leer. Likes funktionieren im Feed.
- **Meldungsnavigation:** Tippen markiert eine Meldung gelesen; eine Weiterleitung zum zugehörigen Profil oder Status fehlt.

## Grenzen des aktuellen Begleitmodus

- Die Begleitung hängt an einem Träwelling-Check-in und verfolgt eine Fahrt; eine gespeicherte Reise mit mehreren Etappen, Anschlussüberwachung und Ersatzverbindungen ist nicht implementiert.
- GPS steuert Stationsalarme. Es gibt keine Kartenansicht, keine Fußweg-/Straßennavigation und keinen Reisebetrieb ohne Anmeldung/Check-in.
- Es gibt keine Ticketverwaltung, Wear-OS-App, Pendelstrecken-/Favoritenverwaltung oder gesonderten Dienstmodus.
- Die Transitous-Live-Karte aus PR #35 wurde nicht übernommen; der PR ist geschlossen und ungemergt. Im geprüften App-Code existiert kein Transitous-Client.
- Echtzeitfelder stammen aus der Träwelling-API. GPS-Nähe ist keine genaue Ankunftsprognose; Gerätesignale, Audioausgabe und kurze Halte müssen weiterhin auf echten Fahrten geprüft werden.

## Verwandte Seiten

- [Projekt Überblick](../projekt/ueberblick.md)
- [Screens](../ui/screens.md)
- [Module](../module/README.md)
- [Offene Fragen](../offene-fragen.md)
