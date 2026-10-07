# Projekt: Ziele

## Zweck

Beschreibt die Hauptziele und Besonderheiten dieses optimierten Clients.

## Kontext

Warum wurde diese Version optimiert?

## Ziele

- **Weitere Geräte**: Das Routely-Design und die bisherigen Kernfunktionen werden gemeinsam mit Flutter entwickelt. Android/iOS erhalten eigene Hintergrund- und Sperrbildschirmadapter; Desktop und Web begleiten die Fahrt im Vordergrund.
- **Konsistente Reisezeiten**: Berücksichtigt im Backend vorgenommene Zeitkorrekturen (`manualDeparture` / `manualArrival`). In der eigenen aktiven Begleitung haben frische eindeutig zugeordnete GPS-Zeiten Vorrang; bei fehlender geeigneter Beobachtung folgen manuelle Zeit, API-Echtzeit und Plan. Die Quellen werden gekennzeichnet und Prognosen nicht automatisch zurückgeschrieben.
- **Intelligente Timeline & Travel-Badges**: Hervorhebung von Start-/Zielstationen, Entfällen (Disruptions) und personalisierte Visualisierungen der Reiseroute.
- **Performance & Live-Status**: Bessere Ladezeiten durch Daten-Merging und dezente Indikatoren bei Live-Fahrten.

## Verwandte Seiten

- [Projekt Überblick](./ueberblick.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
