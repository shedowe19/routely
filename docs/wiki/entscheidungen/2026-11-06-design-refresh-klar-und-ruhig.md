# Entscheidung: Design-Refresh „Klar & ruhig“

## Datum

2026-11-06

## Status

Akzeptiert

## Kontext

Die App nutzte viele parallel wirkende Akzente (Indigo, Teal, Amber, Pink, Grün, Orange),
gemischte Kartenradien und Schatten sowie zwei unterschiedliche Kopfkonzepte. Der Feed zeigte
keine Abfahrts-/Ankunftszeiten, obwohl die Daten vorhanden waren. TealDark-Texte auf dunklen
Flächen waren schwer lesbar. Das Profil verwendete einen Blau-Türkis-Verlauf, der mit dem
restlichen Indigo-Erscheinungsbild brach.

Zwei Richtungen wurden als Compose-Mockup geprüft:

- **A – Klar & ruhig:** neutrale Flächen, wenig Farbe, kompakter Kopf, mehr Inhalt.
- **B – Markant:** großer Markenverlauf, farbige Kartenkanten, lautere Anmutung.

## Entscheidung

Umgesetzt wird Richtung **A „Klar & ruhig“**:

- Tokenbasierte Material3-Schemes für Light, Dark und AMOLED; semantische Success-Farbe über
  `MaterialTheme.successColor`.
- Eigene Typografie-Skala und `Shapes` (Karten 18 dp, Panels 14 dp).
- Kompakter `TraewellingTopAppBar` mit optionaler Markenmarke und weichem Indigo-Verlauf;
  Statusleisten-Symbole folgen der App-Theme-Wahl.
- Feed: segmentierter `FeedTypeSwitcher`, flachere Karten, Zeiten an den Stationen,
  reduzierte Farbe auf Linie/Status.
- Meldungen: dichte Zeilen mit Icon-Kreis, ungelesenem Punkt und Trenner statt großer Karten.
- Profil: Indigo-Verlauf statt Blau-Türkis, gemeinsame Statistikfläche, Balken je
  Verkehrsmittel, Einstellungen/Abmelden im Kopf.
- Check-in: kompakte Datumsauswahl, ruhigere Erkennungskarte, gefülltes Suchfeld.
- Fahrtdetail: harte Farbkonstanten wurden auf Theme-Rollen (`secondary`, `tertiary`,
  `error`, Container) umgestellt; die Timeline bleibt funktional unverändert.

Ergänzend im selben Durchlauf: relative Zeitangaben im Feed, Status-Chips aus API-Echtzeit
(Pünktlich/Verspätung/Ausfall), Dauer im Routenpanel, Skeleton-Ladezustände, Tagesgruppen in
den Meldungen und dezentes haptisches Feedback für Likes und Tabwechsel.

Funktionen, Datenflüsse, API-Aufrufe und Navigationsziele bleiben unverändert.

## Konsequenzen

- Mehr Konsistenz und Lesbarkeit in allen drei Themes; weniger vertikale Verschwendung.
- Neue Screens sollen Theme-Rollen statt eigener `Color(0x...)`-Werte verwenden.
- Die alten Farbkonstanten bleiben vorerst als Kompatibilität erhalten.
- `assembleDebug` und `testDebugUnitTest` laufen; die visuelle Prüfung erfolgte auf einem
  SM-S948B (Android 17) in Light und Dark. AMOLED und TalkBack bleiben offene Geräteprüfungen.

## Alternativen

- **B „Markant“:** stärkerer Markenauftritt, aber weniger Feedfläche und mehr visuelles
  Rauschen; verworfen.
- **Nur Feinschliff:** hätte die inkonsistenten Karten, Farben und den Profilverlauf nicht
  behoben; verworfen.

## Verwandte Seiten

- [Theme](../ui/theme.md)
- [Komponenten](../ui/komponenten.md)
- [Screens](../ui/screens.md)
- [Architektur Entscheidungen](../architektur/entscheidungen.md)
