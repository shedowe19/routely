# 🚅 Routely (Optimierte Version)

[![Kotlin](https://img.shields.io/badge/kotlin-1.9.0-blue.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-orange.svg)](https://developer.android.com/jetpack/compose)

Diese Version von **Routely** wurde speziell optimiert, um eine perfekte Brücke zwischen der Träwelling-Webplattform und dem mobilen Erlebnis zu schlagen. Der Fokus lag dabei auf der präzisen Darstellung von manuell korrigierten Reisedaten und einem erstklassigen User Interface.

## ✨ Highlights der Optimierung

### 🔄 Perfekte Synchronisation manueller Edits
Im Gegensatz zur Standardversion priorisiert diese App die im Träwelling-Backend vorgenommenen manuellen Zeitkorrekturen (`manualDeparture` / `manualArrival`). 
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

### 🗺️ Transitous Live-Karte
-   Ein eigener Karten-Tab zeigt geschätzte Positionen öffentlicher Verkehrsmittel im gewählten Ausschnitt. Marker lassen sich nach Verkehrsart filtern und antippen.
-   Die Positionen werden aus Fahrtzeiten und Liniengeometrie berechnet; selbst mit Echtzeit-Fahrtdaten sind sie keine GPS-Ortung. Datenquellen und Kartenurheber sind in der App verlinkt.

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

## Lizenz und externe Daten

Der von Routely entwickelte Quellcode und die Projektdokumentation stehen unter der [Apache License 2.0](LICENSE). Copyright 2026 Routely contributors. Die Lizenz erfasst keine Rechte an externen Bibliotheken, Verkehrs- und Kartendaten oder fremden Logos und Marken. Insbesondere sind die vorhandenen Träwelling-Grafiken und App-Icons nicht durch Routely neu lizenziert.

Die öffentliche [Transitous-API](https://transitous.org/api/) unterliegt eigenen Nutzungsbedingungen: Sie ist für quelloffene, nichtkommerzielle und ressourcenschonende Anwendungen vorgesehen. Die Apache-Lizenz für den App-Code erlaubt keinen davon abweichenden Zugriff auf den Transitous-Dienst. Für andere Einsatzformen muss die Datenanbindung entsprechend ersetzt oder mit Transitous geklärt werden.
