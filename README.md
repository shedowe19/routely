# 🚅 Routely (Optimierte Version)

[![Kotlin](https://img.shields.io/badge/kotlin-2.3.21-blue.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-orange.svg)](https://developer.android.com/jetpack/compose)

Diese Version von **Routely** wurde speziell optimiert, um eine perfekte Brücke zwischen der Träwelling-Webplattform und dem mobilen Erlebnis zu schlagen. Der Fokus lag dabei auf der präzisen Darstellung von manuell korrigierten Reisedaten und einem erstklassigen User Interface.

## ✨ Highlights der Optimierung

### 🔄 Perfekte Synchronisation manueller Edits
Die App berücksichtigt im Träwelling-Backend vorgenommene manuelle Zeitkorrekturen (`manualDeparture` / `manualArrival`). Frische, eindeutig zugeordnete GPS-Zeiten haben Vorrang; ohne nutzbare GPS-Zeit folgen manuelle Korrektur, API-Echtzeit und Fahrplan.
-   **Konsistente Daten:** Zeitkorrekturen werden global synchronisiert – von der Übersichtskarte bis hin zum tiefsten Haltestellenverlauf.
-   **Visuelles Feedback:** Geplante Zeiten werden bei Abweichungen durchgestrichen, während die manuellen/realen Zeiten farblich hervorgehoben werden (Rot für Verspätung, Grün für Pünktlichkeit/Verfrühung).

### 📍 Intelligente Timeline & Travel-Badges
Der Haltestellenverlauf wurde komplett neu gestaltet, um maximale Orientierung zu bieten:
-   **STARTHALTESTELLE & ENDSTATION:** Dynamische Markierungen, die sich automatisch anpassen, falls die ursprünglichen Start- oder Zielbahnhöfe entfallen.
-   **DEIN EINSTIEG & DEIN ZIEL:** Premium-Badges in Gold/Amber mit Icons, die deine persönliche Reise innerhalb der Zuglinie markieren.
-   **Disruptions-Management:** Entfallene Haltestellen werden rot durchgestrichen und mit einem prominenten "HALT ENTFÄLLT" Badge versehen.

### ⚡ Live-Status & Performance
-   Ein dezenter **Live-Indikator** in der TopAppBar zeigt dir bei Fahrten am aktuellen Tag sofort an, dass du dich gerade im "Live-Modus" befindest.
-   Optimierte Ladezeiten für umfangreiche Haltestellenlisten durch effizientes Daten-Merging im ViewModel.

### 🧭 Reisebegleitung
-   GPS-Fortschritt und lokale Zeitprognosen mit API-/Fahrplan-Rückfall bei fehlendem Empfang.
-   Geeignete Träwelling-Streckenverläufe für Bahn und Tram statt ausschließlich gerader Haltverbindungen; Quellenqualität und geordnete Besuchsbindung werden lokal geprüft.
-   Haltestellenansagen, aktive Hinweise auf Fahrtänderungen und Reisefortschritt in Notification und Widget.
-   Optionale Fahrterkennung und SEV-Ersatzhaltestellen aus öffentlichen Bahnhofskarten.

Funktionsgrenzen und Voraussetzungen stehen bei der [Reisebegleitung](docs/wiki/module/trip-tracking.md) und den [GPS-Zeiten](docs/wiki/module/gps-zeiten.md).

## 🛠 Tech Stack

-   **Sprache:** Kotlin
-   **UI:** Jetpack Compose (Material 3)
-   **Architektur:** MVVM mit StateFlow
-   **Networking:** Retrofit & OkHttp
-   **Image Loading:** Coil

---
*Entwickelt für die Träwelling-Community.*

## Projekt-Wiki

Die interne Projektdokumentation (für Entwickler und Agenten) befindet sich unter:

- [Projekt-Wiki](docs/wiki/index.md)
