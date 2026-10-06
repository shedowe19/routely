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
- **Träwelling-API-Migration vom 05.10.2026**: Station und Haltidentität werden getrennt behandelt. Verbraucher verwenden gemeinsame Modellhelfer für `station`, Plan-/Echtzeit und Stopover-UUID statt auslaufender flacher Felder. Die bestehenden Endpunkte bleiben erhalten; ungenutzte neue APIs werden nicht ohne passende App-Funktion ergänzt. Details: [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md).
- **GPS-Zeiten als lokale Anzeige vom 05.10.2026**: Der bereits etablierte Besuchscursor, gerichtete Fixfolge und geplante Fahrintervalle stützen die Zeitprognose. Ein gemeinsamer Resolver wählt GPS, manuelle Zeit, parsebare API-Echtzeit und Plan. Die frühere synthetische Verspätungsvererbung entfällt; Providerdaten und Bearbeitungswerte werden nicht durch Prognosen ersetzt. Bei ungeeignetem Verlauf erfolgt konservativ der Rückfall, statt eine Restzeit aus Luftlinie und Momentangeschwindigkeit zu bilden. Details: [GPS-Zeiten](../module/gps-zeiten.md).

- **Stabile GPS-Zeiten und rechtzeitiger Einstiegshinweis vom 06.10.2026**: Der Bewegungsbeleg nutzt ein zeitlich begrenztes Beobachtungsfenster statt einer zu kurzen festen Callbackanzahl. Bereits belegte Prognosen können bei passenden frischen Positionen durch Bremsen und geordnete Haltübergänge erhalten bleiben; ihr Ablauf wird dabei nicht verlängert. Eine Einstiegsansage bei nutzbarem GPS erfordert bestätigtes stationäres Warten innerhalb des bevorstehenden API-/manuellen Abfahrtsfensters. Uhrzeit allein bewegt oder beendet keinen GPS-Besuch. Der Fahrtdetailzustand übernimmt API-Status und passend zugeordnete Stopover-Grenzen gemeinsam, damit kein roher Zwischenzustand die GPS-Besuchsidentität kurzzeitig verliert. Details: [GPS-Zeiten](../module/gps-zeiten.md) und [TripTracking](../module/trip-tracking.md).

- **Coil ImageLoader mit User-Agent**: Am 29.06.2026 wurde die App um eine eigene `Application`-Klasse (`TraewellingApplication`) erweitert, die `ImageLoaderFactory` implementiert, um Coil einen angepassten `OkHttpClient` bereitzustellen. Dieser Client fügt den erforderlichen `User-Agent` zu jedem Bild-Request hinzu, um `403 Forbidden` Fehler beim Laden von Profilbildern zu vermeiden.
- **Display-aus-Begleitung vom 06.10.2026**: Der sichtbare Tracking-Foreground-Service wird nur während einer bestätigten aktiven Fahrt um einen generationsgebundenen partiellen CPU-WakeLock ergänzt. Jeder Erwerb ist auf 120 Sekunden begrenzt und wird alle 60 Sekunden erneuert; alle Stop-/Destroy-Pfade geben ihn ausdrücklich frei. Es wird weder das Display gehalten noch außerhalb der aktiven Fahrt ein Dauerlock angefordert. Da gewöhnliches Doze WakeLocks und Netzwerk beschränkt, liest die Einstellungs-Card den Android-Ausnahmestatus und bietet ausschließlich nach Nutzertipp die Systemanfrage an. Eine Ausnahme wird nicht in App-Preferences simuliert und kann weitere System-/OEM-Beschränkungen oder Force-Stop nicht aufheben. Details: [TripTracking](../module/trip-tracking.md), [Settings](../module/settings.md) und [Gerätetests](../entwicklung/tests.md).
- **API Modell Updates**: Am 29.06.2026 wurde das `User`-Datenmodell um ein `mastodon`-Unterobjekt (`MastodonInfo`) erweitert. Die Prüfung vom 05.10.2026 unterscheidet zwischen dem entfernten `LightUserResource.mastodonUrl` und dem weiterhin gültigen Feld im vollständigen User-/Auth-Modell; `mastodon.server` ist ebenfalls vorhanden.

## Verwandte Seiten

- [Entscheidungen](../entscheidungen/README.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
