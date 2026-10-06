# Modul: Feed

## Zweck

Zeigt die Timeline der Status-Einträge von abonnierten Nutzern oder global.

## Kontext

Der Feed ist die soziale Hauptkomponente der App nach dem Login.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/FeedScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/FeedViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/FeedController.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/StatusMutation.kt`
- `app/src/main/kotlin/de/traewelling/app/data/local/StatusDao.kt`

## Verhalten

Lädt Statuslisten vom Backend. Das persönliche Dashboard verwendet numerische Pagination; der aktuelle globale `statuses`-Vertrag ist unpaginiert. Die UI lädt weitere Seiten nur bei geliefertem `links.next`. Room (`StatusDao`) speichert Seite 1 getrennt nach persönlichem/globalem Feed sowie Server-/Tokenzuordnung. Ein SHA-256-Digest im Partitionstyp enthält keinen Klartexttoken. `(id, type)` ist der zusammengesetzte Primärschlüssel; die neue erste Seite ersetzt ihre Partition transaktional, auch bei einer erfolgreichen leeren Liste. Historische unpartitionierte Einträge werden nicht als kontobezogener Rückfall verwendet.

Offline-Rückfall gilt ausschließlich für Seite 1 bei Netzwerkfehlern oder HTTP 408/429/5xx und nur für die weiterhin passende Sitzung. Authfehler, unvollständige Antworten und Coroutine-Abbrüche dürfen keinen früheren privaten Feed wieder anzeigen. Weitere Seiten verwenden keinen Seite-1-Cache; die Cacheantwort hat keine weiteren Paginationlinks. Sitzungsprüfungen vor und nach Netzwerk-/Room-Verarbeitung verwerfen späte Antworten nach einem Konto- oder Revisionswechsel.

Der Cache speichert außerdem den ursprünglichen Seitenindex `position`. Das nach Abfahrt sortierte Dashboard bleibt dadurch offline in derselben Reihenfolge; Status-ID beziehungsweise Erstellungsfolge werden nicht zur Ersatzsortierung.

Die Feed-Einträge werden über `StatusCard` dargestellt. Die Karte nutzt die Verkehrsmittel-Farbe (`TransportColors`) als Akzent, zeigt die Route in einem getönten Panel und bietet am Ende einen sichtbaren Details-Hinweis. Lade-, Fehler- und Empty-States verwenden `StateMessage`, damit der Feed dieselbe visuelle Zustandsdarstellung wie Check-in und StatusDetail nutzt.

Refresh und Feedwechsel ersetzen den bisherigen Ladeauftrag und erhöhen eine Generation; alte Antworten überschreiben den neuen Feed nicht. Pagination läuft nicht gleichzeitig mit Refresh und dedupliziert nach Status-ID. Pro Status ist höchstens ein Like-/Unlike-Auftrag offen. Nicht likbare Status werden übersprungen; bei einem API-Fehler wird nur noch die passende optimistische Änderung zurückgenommen, ohne einen inzwischen aktualisierten Serverwert pauschal zurückzusetzen.

### Erfolgreiche Statusänderungen und Löschungen

`FeedViewModel` bleibt der Android-Lifecycle-Adapter mit demselben Anwendungskonstruktor. Der Android-freie `FeedController` erhält Scope, API-Gateway, Auth-Snapshot-Lieferant und `StatusMutationEvents.events`. Der Repository-Bus ist ein begrenzter, nicht persistierter `SharedFlow` ohne Replay; Ereignisse enthalten Status-ID und Authrevision, keine Zugangsdaten. Die Subscription bleibt auch außerhalb des Feed-Tabs aktiv.

Nach erfolgreichem PUT oder DELETE invalidiert das Repository die persönliche und globale Seite-1-Cachepartition der ursprünglichen Server-/Tokenzuordnung. Cache-Mutex und kontobezogene Änderungsepochen verhindern, dass ein zuvor begonnener GET die alte Antwort später veröffentlicht oder zurück in Room schreibt. Eine neue Authrevision mit denselben Zugangsdaten hebt diese Invalidierung nicht auf. Schlägt die lokale Cachelöschung fehl, bleibt der betroffene Offline-Rückfall im Prozess gesperrt; ein bestätigter Serverauftrag wird dadurch nicht zu einem erneut auszuführenden Schreibauftrag. Die begrenzten Schutzregister wechseln bei Überlauf zu einem konservativ gesperrten Offline-Rückfall.

Der Controller übernimmt Ereignisse nur bei Übereinstimmung mit seiner anfänglich gebundenen und der weiterhin aktuellen Authrevision. Er verwirft die laufende GET-Generation, entfernt bei `Deleted` die vorhandene Karte und ersetzt bei `Updated` ausschließlich einen bereits geladenen Status. Ereignisse legen keine unbekannte Karte im aktuellen Feed an. Laufende Like-Optimismen bleiben beim Ersatz erhalten; eine spätere Like-Fehlerantwort kann eine gelöschte Karte nicht wieder anlegen.

Ein HTTP-2xx-PUT ohne vertrauenswürdigen Status derselben ID invalidiert den Cache ebenfalls. Dafür meldet das Repository `Invalidated`, statt ein `Updated` zu erfinden. Der Controller entfernt die veraltete Karte und lädt den aktuellen Feed zur Verifikation erneut. Das Detail zeigt dabei den Hinweis auf die unvollständige Änderungsantwort. Fehlgeschlagene HTTP-Schreibaufträge veröffentlichen keine erfolgreiche Mutation. Eine späte bereits bestätigte Antwort einer alten Sitzung invalidiert ihre ursprüngliche Cachezuordnung, publiziert jedoch kein Ereignis für das neue Konto.

`FeedControllerTest` prüft diese Antwortreihenfolgen mit virtueller Coroutine-Zeit und verzögerten API-Gateways, einschließlich Sitzungstausch, Löschung während GET/Like und Verifikation einer unvollständigen PUT-Antwort. Die Tests benötigen keine Android-Instanz oder Zeit-Sleeps; siehe [Tests](../entwicklung/tests.md).

## Abhängigkeiten

- **Room**: Für das Caching der Feed-Daten.
- **TraewellingApiService**: Zum Laden neuer Feed-Seiten (`/api/v1/dashboard`).
- **StateMessage**: Einheitliche UI für Lade-, Fehler- und Empty-States.

## Offene Fragen

- TODO: D5/D8 des [Nachreviews](../entwicklung/main-review-2026-10-06.md) beheben: ein alter GET kann einen erfolgreichen Like überlagern; eine alte erfolgreiche PUT-Antwort kann nach einem neueren PUT den RAM-Text zurücksetzen. GET-/Statusmutationsschutz und korrekte Room-Invalidation decken diese beiden zusätzlich reproduzierten Reihenfolgen nicht ab.


## Verwandte Seiten

- [Datenbank](../daten/datenbank.md)
- [StatusDetail](./status-detail.md)
- [Main-Review](../entwicklung/main-review-2026-10-06.md)
- [Tests](../entwicklung/tests.md)
- [Module Übersicht](./README.md)
