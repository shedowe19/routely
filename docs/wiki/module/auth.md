# Modul: Auth

## Zweck

Dieses Modul verwaltet die Benutzerauthentifizierung und Session-Verwaltung innerhalb der App.

## Kontext

Die Authentifizierung ist der initiale Einstiegspunkt für den Nutzer, um personalisierte Funktionen wie Check-Ins und Feeds zu nutzen.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/viewmodel/AuthViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/AuthRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/AuthSessionStore.kt`
- `app/src/main/kotlin/de/traewelling/app/util/AuthSession.kt`
- `app/src/main/kotlin/de/traewelling/app/util/SessionViewModelStore.kt`
- `app/src/main/kotlin/de/traewelling/app/data/api/ApiServerUrl.kt`
- `app/src/main/kotlin/de/traewelling/app/data/api/TraewellingApiService.kt` (enthält auch `OAuthApiService`)
- `app/src/main/kotlin/de/traewelling/app/util/OAuthHelper.kt`

## Verhalten

Der erreichbare `SetupScreen` bietet ausschließlich den manuellen Token-Login:

1. `AuthViewModel.loginWithToken()` lässt `AuthRepository` eine gültige HTTPS-Server-URL ohne Zugangsdaten, Query oder Fragment und einen nicht leeren Token prüfen. Erst nach erfolgreichem `GET /api/v1/auth/user` mit vorhandenem Nutzername werden Server, Token und Nutzer atomar gespeichert. OAuth und PKCE werden dabei nicht verwendet; eine fehlerhafte Anmeldung erzeugt keine vorläufige Sitzung.
2. Beim App-Start wird ein gespeicherter Token erneut geprüft. Nur HTTP 401/403 löscht genau die weiterhin passende Sitzung; Netzwerkfehler, Rate-Limit, Serverfehler und unvollständige Antworten lassen sie bestehen. Verspätete Antworten dürfen eine neuere Sitzung weder ändern noch löschen.
3. Logout löscht zunächst die passende lokale Sitzung und versucht danach mit deren altem Snapshot die API-Abmeldung. Ein Netzfehler kann die lokale Abmeldung nicht verhindern; ein inzwischen erfolgter neuer Login wird nicht gelöscht.

Scheitert bereits die lokale Persistenz der Abmeldung, bleibt die gespeicherte Sitzung erhalten und die UI meldet den Fehler. Ein bloßer erfolgreicher Netzwerkaufruf darf diesen Speicherfehler nicht als lokale Abmeldung darstellen.

`AuthSession` enthält Server, Token und eine Revisionskennung als atomaren DataStore-Snapshot. Jede neue validierte Anmeldung, Abmeldung oder Tokenänderung erneuert diese Kennung; auch Abmelden und erneutes Anmelden mit denselben Zugangsdaten bilden verschiedene Generationen. Neue Anmeldung und Logout entfernen alte Refresh-/Clientdaten, aktive Fahrt, Fahrtcache und den Fahrterkennungs-Opt-in. Vergleichende Schreiboperationen prüfen die erwartete Session in derselben DataStore-Transaktion.

`MainActivity` verwaltet Feature-ViewModels in einem über Rotation erhaltenen `SessionViewModelStore`. Logout oder neue Zugangsgeneration leert dessen Store und beendet die alten ViewModel-Aufträge; die Navigation wird ebenfalls pro Revision neu aufgebaut. Auth- und Settings-ViewModel bleiben Activity-bezogen. Diese Trennung verhindert sichtbare Profildaten, Vorschläge und Fahrtzustände des vorherigen Kontos.

`OAuthHelper` enthält PKCE-/URL-Hilfsfunktionen. `AuthRepository.exchangeCodeForToken()` und `refreshAccessToken()` können Token-Paare austauschen beziehungsweise erneuern, sind aber nicht an den erreichbaren Anmeldeablauf angebunden. `UserProfileViewModel.refresh()` lädt Profildaten erneut und löst keinen Refresh-Token-Flow aus. Einzelheiten stehen unter [OAuth/PKCE](./auth-pkce.md).

Das `AuthViewModel` propagiert den aktuellen Authentifizierungsstatus (eingeloggt / nicht eingeloggt) an die UI.

### SetupScreen UI

Der initiale `SetupScreen` nutzt einen Gradient-Hero mit Routely-Branding, Feature-Chips und eine Login-Card für Server-URL und manuellen Access-Token. Fehlermeldungen werden innerhalb der Card als getönter Error-Hinweis dargestellt. Echte Tokenwerte dürfen nicht dokumentiert werden.

Der Token-Hilfelink prüft die eingegebene Serveradresse vor dem Browserstart. Ungültige URL oder fehlende Browser-App zeigen einen Hinweis in der Card, statt den Setup-Ablauf abstürzen zu lassen.

## Abhängigkeiten

- **Retrofit**: Für Tokenvalidierung und Abmeldung (`TraewellingApiService`), optional die vorhandenen OAuth-Helfer.
- **DataStore**: Für die lokale Persistenz der Authentifizierungs-Token (`PreferencesManager`).

## Offene Fragen

- TODO: OAuth-Anmeldung einschließlich Callback-Verarbeitung und automatischer Token-Erneuerung an einen erreichbaren UI-Ablauf anbinden, falls dieser Login unterstützt werden soll.

## Flutter-Umsetzung

`flutter/lib/data/app_store.dart` hält einen sicheren Sitzungsdatensatz und trennt schreibende UI von lesender Hintergrund-Engine. Gespeicherte Generationen werden vor/nach API-Requests erneut geprüft. Der validierte Android-Import bestätigt die Übernahme vor dem Löschen alter Credentials. Weitere Details stehen in der [Flutter-Architektur](../architektur/flutter-migration.md). Die bisherigen Kotlin-Verträge bleiben die Verhaltensreferenz.

Der sichere Flutter-Schreibpfad prüft erwartete aktive Status-ID und Operationsgültigkeit auch nach dem asynchronen Speichercommit. Ist eine Auswahl inzwischen überholt, stellt er den aktuellen Sitzungsdatensatz innerhalb derselben Warteschlange wieder her; ein verspäteter Schreibabschluss darf die neue aktive Auswahl nicht dauerhaft ersetzen. Eine verzögert abgeschlossene Speicherung dient als Regression für diesen Randfall.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Module Übersicht](./README.md)
- [OAuth/PKCE](./auth-pkce.md)
- [Offene Fragen](../offene-fragen.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
