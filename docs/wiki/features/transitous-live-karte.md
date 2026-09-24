# Transitous Live-Karte

## Zweck

Die Live-Karte zeigt öffentliche Verkehrsmittel in einem wählbaren Kartenausschnitt. Die Fahrzeugmarker geben **geschätzte** Positionen wieder. Sie sind keine GPS-Ortung und eignen sich nicht als genaue Standortangabe eines Fahrzeugs.

## Kontext

Die Karte ist ein eigener Tab der angemeldeten App. Sie verwendet eine native MapLibre-Karte mit dem OpenFreeMap-Stil „Liberty“ und fragt die Transitous-API direkt ab. Träwelling-Check-ins, Fahrten und OAuth bleiben ein getrennter Datenfluss.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/navigation/AppNavigation.kt`: Karten-Tab in der Hauptnavigation.
- `app/src/main/kotlin/de/traewelling/app/ui/screens/LiveMapScreen.kt`: Karte, Bedienelemente, Marker und Anzeige der Datenquellen.
- `app/src/main/kotlin/de/traewelling/app/viewmodel/LiveMapViewModel.kt`: Sichtbarkeitssteuerung, Abfragen und Karten-Zustand.
- `app/src/main/kotlin/de/traewelling/app/data/transitous/TransitousLiveMapClient.kt`: begrenzte Transitous-Anfrage ohne Träwelling-Bearer-Token.
- `app/src/main/kotlin/de/traewelling/app/data/transitous/TransitousModels.kt`: Antwortmodelle, gültige Kartengrenzen und Marker.
- `app/src/main/kotlin/de/traewelling/app/data/transitous/TransitousVehicleEstimator.kt`: dekodiert Liniengeometrie und schätzt Markerpositionen.

## Verhalten

1. MapLibre zeichnet die Karte aus dem [OpenFreeMap-Liberty-Stil](https://openfreemap.org/quick_start/). Verschieben und Zoomen bestimmen den geografischen Ausschnitt.
2. Ab Zoomstufe 9 fragt die App mit dem sichtbaren Bereich, der Zoomstufe und einem auf ungefähr drei Minuten begrenzten Zeitfenster `GET https://api.transitous.org/api/v6/map/trips` ab. Für die Serveranfrage begrenzt sie den Ausschnitt auf maximal einen Breitengrad und einen Längengrad um die Kartenmitte. Nach Kartenbewegungen wartet sie 900 ms; zwischen Anfragen liegen mindestens 10 Sekunden. Solange der Tab ausgewählt und die App im Vordergrund ist, erfolgt etwa alle 60 Sekunden eine Aktualisierung. Beim Wechsel des Tabs oder Pausieren der App stoppt der Timer; anstehende und laufende Transitous-Anfragen werden abgebrochen. Die App begrenzt die Anzahl gleichzeitig angezeigter Marker auf 300.
3. Für eine Fahrt wird die Position entlang der gelieferten Liniengeometrie und zwischen ihren Abfahrts- und Ankunftszeiten geschätzt. Auch wenn die Fahrt `realTime = true` hat, ist der Marker keine direkt gemessene Fahrzeugposition: Das Feld bezieht sich auf Echtzeitinformationen zur Fahrt bzw. ihren Zeiten.
4. Ein Verkehrsmodus-Filter grenzt die Darstellung ein. Antippen eines Markers zeigt Linie, Start, Ziel, geschätzte Position und gegebenenfalls Verspätung im Detailfeld. Die optionale Standorttaste zentriert die Karte nach erteilter Android-Standortberechtigung; die Karte funktioniert auch ohne diese Berechtigung.
5. Ein sichtbarer Quellenhinweis verlinkt die [Transitous-Datenquellen](https://transitous.org/sources/), [OpenFreeMap](https://openfreemap.org/) und den [OpenStreetMap-Copyright-Hinweis](https://www.openstreetmap.org/copyright); er nennt OpenMapTiles und OpenStreetMap-Mitwirkende ausdrücklich. Fehlen Daten für eine Region oder sind sie veraltet, zeigt die App nur die verfügbaren Fahrten an.

## Abhängigkeiten und Grenzen

- Die [Transitous-API](https://transitous.org/api/) stellt je nach Verkehrsverbund unterschiedliche Fahrplandaten und Echtzeitinformationen bereit; ein Marker ist deshalb keine Zusage, dass ein Fahrzeug dort tatsächlich fährt.
- Die Karte benötigt eine Internetverbindung zu Transitous und zu den OpenFreeMap-Kacheln.
- Transitous verlangt einen identifizierbaren `User-Agent`, sichtbare Quellennennung sowie eine ressourcenschonende und nichtkommerzielle Nutzung. Bei großem Anfragevolumen verlangt die Nutzungsrichtlinie vorab eine Abstimmung. Routely-Code und selbst erstellte App-Grafiken stehen unter Apache-2.0; die Transitous-Daten und der API-Dienst fallen nicht unter diese Projektlizenz.
- Der Kartenstil trägt eigene OpenMapTiles/OpenStreetMap-Attribution; diese darf bei Anpassungen der Kartenoberfläche nicht verdeckt werden.

## Offene Fragen

- TODO: Bei erwartbar hoher Nutzung das Anfragevolumen vorab mit Transitous abstimmen.

## Verwandte Seiten

- [Features Übersicht](./README.md)
- [Externe Schnittstellen](../api/externe-schnittstellen.md)
- [Screens](../ui/screens.md)
- [Tests](../entwicklung/tests.md)
