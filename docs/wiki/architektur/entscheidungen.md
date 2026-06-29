# Architektur: Entscheidungen

## Zweck

Kurze Zusammenfassung technischer Entscheidungen, für die es eventuell keine eigene ADR gibt.

## Entscheidungen

- **Jetpack Compose als UI Framework**: Gewählt für moderne, deklarative UI-Entwicklung.
- **Coroutines & StateFlow**: Gewählt für asynchrone Aufgaben und reaktives State Management (statt RxJava oder LiveData).
- **Retrofit & Gson**: Gewählt für die Anbindung an die Träwelling JSON-REST-API, wobei auf `@SerializedName` zur strikten Mappung geachtet wird.
- **Room Database**: Gewählt für lokales Caching und Offline-Fähigkeit (`StatusDao`).
- **Globaler NavHost**: Core Tabs (Feed, Check-in, Meldungen, Profil) sind innerhalb eines `HorizontalPager` verpackt für seitliches Swipen, während tiefere Navigation als unabhängige Routen konzipiert ist.
- **App Rename**: Am 28.04.2026 wurde die App von "Träwelling" bzw. "Träwelling Android" zu "Routely" umbenannt. Die zugrunde liegende Plattform und API bleiben weiterhin unter dem Namen "Träwelling" bestehen. Dies dient einer klareren Unterscheidung zwischen dem Client und dem Backend. Für weitere Details, siehe [ADR App Rename](../entscheidungen/2026-04-28-app-rename-routely.md).
- **Settings & Theming**: Einführung eines dedizierten Einstellungsmenüs und Auslagerung von globalen Settings aus dem `ProfileScreen`. Hinzufügen von Dark Mode und AMOLED Themes. Für weitere Details, siehe [ADR Dark Mode & Settings](../entscheidungen/2026-04-29-dark-mode-und-settings.md).

## Verwandte Seiten

- [Entscheidungen](../entscheidungen/README.md)
- **Coil ImageLoader mit User-Agent**: Am 29.06.2026 wurde die App um eine eigene `Application`-Klasse (`TraewellingApplication`) erweitert, die `ImageLoaderFactory` implementiert, um Coil einen angepassten `OkHttpClient` bereitzustellen. Dieser Client fügt den erforderlichen `User-Agent` zu jedem Bild-Request hinzu, um `403 Forbidden` Fehler beim Laden von Profilbildern zu vermeiden.
- **API Modell Updates**: Am 29.06.2026 wurde das `User`-Datenmodell um ein `mastodon`-Unterobjekt (`MastodonInfo`) erweitert, um die neue Struktur (`mastodon.server`) abzubilden und die Deprecation von `mastodonUrl` aus dem API-Changelog zu adressieren.
