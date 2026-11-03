# Konfiguration: PreferencesManager

## Zweck

Zentraler Manager für alle App-Einstellungen und persistierte Daten. Nutzt Android DataStore.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`
- `app/src/main/kotlin/de/traewelling/app/util/AuthSession.kt`

## Gespeicherte Werte

### Auth-Daten

| Key             | Flow-Typ        | Beschreibung                          |
| --------------- | --------------- | ------------------------------------- |
| `server_url`    | `Flow<String>`  | Server-URL (Standard: traewelling.de) |
| `access_token`  | `Flow<String?>` | Manuell validierter oder über OAuth-Helfer gespeicherter Bearer-Token |
| `refresh_token` | `Flow<String?>` | OAuth Refresh Token                   |
| `client_id`     | `Flow<String?>` | OAuth Client ID                       |
| `client_secret` | `Flow<String?>` | OAuth Client Secret                   |
| `username`      | `Flow<String?>` | Aktueller Nutzername                  |
| `isLoggedIn`    | `Flow<Boolean>` | Login-Status (Access-Token vorhanden) |
| `auth_session_revision` | intern String | Neue UUID bei Anmeldung, Abmeldung oder Tokenänderung; Altbestand ohne Feld verwendet `legacy` |

`authSession: Flow<AuthSession>` beziehungsweise `getAuthSession()` lesen Server, nicht leeren Token und Revision gemeinsam. `saveValidatedSession` ersetzt eine vollständige validierte Sitzung atomar und entfernt alte OAuth-/Nutzer-/Fahrtwerte sowie den Erkennungs-Opt-in. `clearSession()` bewahrt den Server und allgemeine Einstellungen, entfernt jedoch Token, Refresh-/Clientdaten, Nutzername, aktive Fahrt, Fahrtcache und Erkennungs-Opt-in und erneuert die Revision.

`trackingConfiguration: Flow<TrackingConfiguration>` liest Auth-Snapshot, aktive Status-ID, GPS-Einstellung und Fahrterkennungs-Opt-in gemeinsam aus derselben Preferences-Ausgabe. Activity und Service dürfen diese Werte nicht aus getrennt eintreffenden Flows zu einer scheinbar gültigen fremden Fahrt zusammensetzen.

`clearSessionIfMatches`, `saveUsernameIfMatches` und `saveTokensIfMatches` schreiben nur für den unveränderten erwarteten Snapshot. `saveActiveStatusIdIfMatches` schützt entsprechend verspätete Check-in-Antworten. Diese Prüfung erfolgt im DataStore-`edit`, nicht nur vor einem suspendierenden Aufruf. Revisionen trennen auch Logout und erneuten Login mit demselben Token; sie sind keine Geheimnisse oder GPS-Daten.

`saveActiveStatusIdIfIdleOrSame(expectedSession, statusId)` ergänzt die Check-in-Abschlussgrenze: Sitzung, Anmeldung und bisherige aktive ID werden in derselben `edit`-Transaktion geprüft. Eine andere bereits konfigurierte Begleitung samt Route bleibt erhalten; dieselbe Fahrt darf ihren Cache behalten. Ungültige neue IDs und unlesbare vorhandene IDs werden nicht stillschweigend überschrieben. Erst der finale POST-/Zeitkorrekturstatus entscheidet, ob eine nachgetragene Fahrt überhaupt noch eine zukünftige Ankunft und damit einen Begleitbedarf hat.

### Active Status

| Key                | Flow-Typ     | Beschreibung                                   |
| ------------------ | ------------ | ---------------------------------------------- |
| `active_status_id` | `Flow<Int?>` | ID der aktiven Fahrt (für TripTrackingService) |

### Stationsalarm und Trackingzustand

| Key | Zugriff / Standard | Beschreibung |
| --- | --- | --- |
| `gps_tracking_enabled` | `Flow<Boolean>`, `true` | GPS-Tracking gewünscht; ersetzt keine Standortfreigabe |
| `announcement_radius_meters` | `Flow<Int>`, `0` | `0` für Automatik; feste Werte `300`, `500`, `1000`, `2000` Meter |
| `trip_tracking_state` | `getTrackingState(): String?` | JSON-Zustand der aktiven Fahrt; intern gespeichert |
| `location_permission_requested` | `hasRequestedLocationPermission(): Boolean`, `false` | Merkt eine bereits angeforderte Standortfreigabe für den manuellen Weg zu Android-App-Berechtigungen |

`setAnnouncementRadiusMeters` und der lesende Flow setzen ungültige Radien auf Automatik (`0`) zurück. `saveTrackingState(statusId, stateJson, expectedSession)` schreibt nur, wenn die Status-ID und der übergebene Auth-Snapshot weiterhin passen. Der Tracking-Service liefert diesen Snapshot auch an `clearActiveTracking`; ein abgelöster Service kann damit keine gleich nummerierte Fahrt einer anderen Zugangsgeneration überschreiben oder löschen. Der optionale Sessionparameter dient weiterhin kompatiblen internen Aufrufern; der aktive Service verwendet den Guard.

Ein Wechsel beziehungsweise Löschen der aktiven Status-ID entfernt den Trackingzustand. `clearActiveTracking(statusId)` löscht ihn nur für die passende aktive Fahrt. Auch `clearSession()` entfernt aktive Fahrt und Trackingzustand.

Der Tracking-Cache enthält Status-ID, Check-in, Haltfolge und `TrackingProgress`. Gespeichert werden aktueller Besuch, `gpsEstablished` zur Unterscheidung von GPS- und vorläufigem Zeitcursor sowie erfolgreich eingereihte Ansageschlüssel. Positionen, Bewegungshistorie und lokale GPS-Zeitbeobachtungen/-prognosen werden nicht persistiert. Die [GPS-Zeiterweiterung](../module/gps-zeiten.md) benötigt keine eigene Preference; die bestehende Standortfreigabe und `gps_tracking_enabled` bestimmen ihre Verfügbarkeit. Der Marker einer Standortanfrage ist keine erteilte Berechtigung: Diese wird bei jedem sichtbaren Start erneut geprüft.

Ein TTS-Fehler beziehungsweise Abbruch kann den noch aktuellen Ansageschlüssel wieder freigeben; der Service speichert diesen korrigierten Fortschritt. Der für die Status-Timeline bereitgestellte `trackingLiveState` bleibt dagegen ausschließlich im Prozessspeicher und führt keinen zusätzlichen DataStore-Key ein. Das Tracking-Cacheformat bleibt Version 1.

Die [SEV-Ergänzung](../module/sev-haltestellen.md) erweitert das vorhandene Fahrtcache-JSON optional um `fullStopovers` (vollständige API-Fahrt als Richtungskontext) und `sevMaps` (öffentliche Bahnhofskarten mit Abrufzeit). Es wird kein eigener Preference-Key eingeführt. Alte Version-1-Einträge ohne diese Felder werden mit vorhandener Haltfolge beziehungsweise leerer SEV-Quelle gelesen. Wiederhergestellte Quellen werden gegen Alter, Datum und Richtung erneut aufgelöst; die persistierten Karten enthalten öffentliche Bahnhof-/Ersatzhaltpunkte, keine GPS-Gerätepositionen oder Bewegungshistorie.

Native Bahn-/Tram-Linienzüge und OSRM-Straßen-Geometrien bleiben ausschließlich im RAM, ohne zusätzliche Preference oder Erweiterung des Fahrtcache-JSON. Nach Wiederanlauf muss die zur restaurierten Haltfolge passende Geometrie erneut geladen werden. Ein Download speichert keinen Gerätefix und verlängert keine GPS-Zeitgültigkeit.

### Reisebegleitung

| Key | Flow / Standard | Beschreibung |
| --- | --- | --- |
| `ride_recognition_enabled` | `Flow<Boolean>`, `false` | Ausdrücklicher Opt-in für die sichtbare Fahrtsuche; Kandidaten und Fixes bleiben im RAM. |
| `trip_change_alerts_enabled` | `Flow<Boolean>`, `true` | Änderungsmonitor-Hinweise zur eigenen aktiven Fahrt. |
| `trip_change_speech_enabled` | `Flow<Boolean>`, `true` | Änderungssprache; benötigt zusätzlich aktivierte Hinweise und globale TTS. |
| `live_progress_enabled` | `Flow<Boolean>`, `true` | Fortschrittsdarstellung und systemabhängige Live-Update-Anfrage. |
| `lock_screen_details_enabled` | `Flow<Boolean>`, `true` | Sichtbare Reisedetails; `false` verwendet öffentliche Ersatzanzeigen und unterdrückt Promotion. |
| `public_rail_realtime_enabled` | `Flow<Boolean>`, `true` | Anonyme zusätzliche Bahn-Livedaten über DBF/IRIS; benötigt keine Zugangsdaten. |

Die zugehörigen suspend-Getter und Setter entsprechen den Flow-Namen. Ein aktiver Check-in pausiert die Erkennung, ohne einen Kandidaten als Fahrt zu speichern. Der Trackingcache enthält zusätzlich `TripChangeMonitorState` mit letzten Ereigniswerten zur Deduplizierung. Die frische Vergleichsbasis, Verspätungsreferenzen und Gerätepositionen werden weiterhin nicht persistiert.

`getPublicRailRealtimeEnabled()` und `setPublicRailRealtimeEnabled(Boolean)` steuern die [DBF-Ergänzung](../api/live-datenquellen.md). Ein laufender Zusatzabruf wird vor der Veröffentlichung erneut gegen diese Einstellung geprüft. Erfolgreich gelesene Zeiten/Gleise behalten im bestehenden Fahrtcache ihre Quell- und Abrufmetadaten; alte Einträge ohne Metadaten werden als unbekannt behandelt. Cachewiederherstellung setzt keinen neuen Live-Abrufzeitpunkt.

### TTS-Einstellungen

| Key            | Flow-Typ        | Beschreibung                      |
| -------------- | --------------- | --------------------------------- |
| `tts_enabled`  | `Flow<Boolean>` | TTS aktiviert (Standard: false)   |
| `tts_engine`   | `Flow<String?>` | TTS-Engine-Paketname              |
| `tts_language` | `Flow<String?>` | BCP47 Language Tag (z.B. "de-DE") |
| `tts_voice`    | `Flow<String?>` | Voice-Name                        |

## Konstanten

```kotlin
DEFAULT_SERVER_URL = "https://traewelling.de"
REDIRECT_URI = "traewelling://oauth-callback"
OAUTH_SCOPES = "read-statuses write-statuses read-notifications read-settings write-settings"
```

## Offline-Zugriff

Für nicht-reaktive Kontexte gibt es suspend-Funktionen:

- `getAccessToken(): String?`
- `getServerUrl(): String`
- `getUsername(): String?`
- `getTtsEnabled(): Boolean`
- `getGpsTrackingEnabled(): Boolean`
- `getAnnouncementRadiusMeters(): Int`
- `getTrackingState(): String?`
- etc.

## Lese- und Schreibfehler

`PreferenceStorageRegistry` hält für denselben DataStore einen gemeinsamen `PreferenceStorage`-Owner, auch wenn Activity, ViewModels und Services verschiedene `PreferencesManager`-Instanzen erzeugen. Die Zustände `LOADING`, `READY`, `RETRYING`, `IO_ERROR` und `CORRUPTED` enthalten keine Zugangsdaten oder rohen Fehlermeldungen. `revision` kennzeichnet eine neue bestätigte Bereitschaft, `failureId` den aktuellen Fehlerzyklus.

Die öffentlichen Beobachtungsflows behalten ausschließlich den letzten bestätigten Snapshot für die Darstellung. Suspendierende Getter einschließlich `getAuthSession()`, `getTrackingConfiguration()` und `getActiveStatusId()` lesen dagegen erneut aus DataStore. Schreibzugriffe laufen durch dieselbe Operationsgrenze; die bestehenden Auth-/Status-/Cachebedingungen bleiben innerhalb der tatsächlichen DataStore-Transaktion. Ein gespeicherter UI-Wert genügt nicht als neue Schreib- oder Startberechtigung. Der [DataStore-Vertrag](https://developer.android.com/reference/kotlin/androidx/datastore/core/DataStore) beschreibt erneutes Lesen nach Fehlern und atomare, erst nach Persistenz erfolgreiche Updates.

Vorübergehende IO-Lesefehler erhalten zwei begrenzte Wiederholungen mit 250 und 750 Millisekunden Wartezeit. Danach bleibt der Fehler sichtbar und `retryStorage()` startet einen neuen Prüfversuch. Ein fehlgeschlagener fachlicher Schreibauftrag wird nicht wiederholt. Nach einem Schreibfehler verwendet die manuelle Recovery einen neuen internen `storage_probe_revision`-Wert, damit eine echte erfolgreiche Persistenz vorliegt; ein bloßes Lesen oder gleichwertiges No-op-Update würde dies nicht nachweisen. Cancellation und Validierungs-/Programmierfehler behalten ihre eigene Bedeutung.

Bei beschädigtem Preferences-Inhalt bleibt die Datei bis zur ausdrücklichen Bestätigung unverändert. `resetCorruptedStorage(expectedFailureId)` akzeptiert nur den aktuellen Korruptionszustand. Ein einmaliger autorisierter Handler derselben DataStore-Instanz ersetzt dann den Inhalt, erneuert die Sessionrevision und deaktiviert Fahrterkennung. Dabei gehen lokale Anmeldung, Einstellungen und aktive Begleitung verloren; serverseitige Fahrten bleiben bestehen. Es gibt keinen allgemeinen IO-Reset, keine automatische Defaultdatei, keinen Credentialexport und keine äußere Dateilöschung neben einem lebenden Store. Die [Android-Korruptionsschnittstelle](https://developer.android.com/reference/androidx/datastore/core/CorruptionHandler) bindet den Ersatz an die DataStore-Korruptionsgrenze.

Scheitert der eigentliche Ersatzschreibvorgang, behandelt DataStore dessen unterdrückte IO-Ursache als Schreibfehler. Die Storagegrenze meldet dann `IO_ERROR` und verlangt die Wiederholungsprüfung; die vorige Resetfreigabe bleibt verbraucht. Ergibt das erneute Lesen weiterhin beschädigten Inhalt, benötigt ein weiterer Reset eine neue Bestätigung des neuen Fehlerzyklus.

`MainActivity` zeigt bei unbekanntem oder fehlerhaftem Speicher einen modalen Recoverydialog vor dem Setup-/Navigationsablauf. Bestehende bestätigte Inhalte bleiben im Hintergrund erhalten und sind währenddessen nicht bedienbar. Ein Resetdialog ist an seine `failureId` gebunden. Beide Services beenden bei Speicherfehlern ihre laufende Arbeit ohne Löschen von ID, Cache oder Erkennungs-Opt-in; der Wiederstart benötigt neue Storagegeneration, frische Sitzung/Konfiguration und abgeschlossene alte Servicebereinigung.

## Offene Fragen

- P12 ist implementiert und durch injizierte Storagefehler sowie eine tatsächlich beschädigte DataStore-Testdatei abgesichert; konkrete Ergebnisse stehen unter [Tests](../entwicklung/tests.md). Gerätebedienung, TalkBack, volle Datenträger und reale Prozess-/Service-Neustarts bleiben praktisch zu prüfen.

## Verwandte Seiten

- [Auth](../module/auth.md)
- [Config-Dateien](./config-dateien.md)
- [TripTracking](../module/trip-tracking.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
- [StatusDetail](../module/status-detail.md)
- [Settings](../module/settings.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Fahrtänderungen](../module/trip-changes.md)
- [Reisefortschritt](../module/trip-progress.md)
