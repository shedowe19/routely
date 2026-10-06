# Modul: SEV-Ersatzhaltestellen

## Zweck

Ergänzt mögliche Ersatzverkehrsfahrten um öffentliche Wegbeschreibungen, Quellenlinks und eindeutig zugeordnete physische Haltepunkte von bahnhof.de. Ein Bahnhofname bleibt der API-Bahnhof; nur die lokale GPS-Projektion darf einen belegten Ersatzhalt verwenden. Fehlende, abgelaufene oder mehrdeutige Quellen lassen die vorhandene API-Koordinate bestehen.

## Kontext

`SevStopResolver.isReplacementBus` erkennt Kandidaten mit Bus-Kategorie oder Bus-Modus und einer Linie im Muster `RE` beziehungsweise `RB` mit Nummer, optional mit vorangestelltem `Bus`. Gewöhnliche Bahnfahrten und andere Buslinien lösen keine automatische Ergänzung aus. Dies ist eine gezielte Erkennungsregel für die gemeldeten Bus-RE/RB-Fahrten, keine vollständige Klassifikation sämtlicher Ersatzverkehre.

Die öffentliche Quelle versorgt den aktiven `TripTrackingService` und das Fahrtdetail. Die Ergänzung läuft asynchron; die vorhandene API-/GPS-Verarbeitung wartet nicht auf die Bahnhofskarte. Die geordnete vollständige API-Haltfolge liefert Richtungskontext, auch hinter dem persönlich gewählten Ausstieg. Angefragt werden nur nicht gestrichene Bahnhöfe innerhalb des eigenen Check-ins, bei eindeutig zugeordnetem Einstieg und Ziel.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/data/model/SevModels.kt`
- `app/src/main/kotlin/de/traewelling/app/data/sev/BahnhofSevParser.kt`
- `app/src/main/kotlin/de/traewelling/app/data/sev/BahnhofSevRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/sev/SevStopResolver.kt`
- `app/src/main/kotlin/de/traewelling/app/data/sev/SevJourneyEnricher.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingLiveState.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/StatusDetailViewModel.kt`
- [Öffentliches Abrufwerkzeug](../../../tools/extract_bahnhof_sev.py)
- [Beispielabruf vom 06.10.2026](../../../tools/sev-stops-example.json)

## Verhalten

### Öffentliche Quelle und begrenzter Abruf

`BahnhofSevRepository` lädt `https://www.bahnhof.de/<stations-slug>/karte` mit einem eigenen anonymen OkHttp-Client. Er übernimmt weder Träwelling-Zugangsdaten noch dessen Interceptor. Der Stationsname wird lokal zum Slug normalisiert: deutsche Umlaute werden `ae`, `oe`, `ue`, `ß` wird `ss`, Satzzeichen und Leerzeichen werden Bindestriche. Ein abweichender Website-Slug kann deshalb zu einem fehlenden Ergebnis führen; es wird keine Suchmaschine oder Geocoding-API eingesetzt.

Antworten müssen HTTPS, einem der beiden bahnhof.de-Hosts und dem HTML-Inhaltstyp entsprechen. HTTP-Weiterleitungen werden begrenzt und nur innerhalb derselben erlaubten Hosts verfolgt. Connect- und Read-Timeout betragen jeweils zehn Sekunden, der Call-Timeout je HTTP-Anfrage 20 Sekunden; die maximale Antwortgröße ist acht MiB. Gemeinsame laufende Abrufe derselben Station werden zusammengeführt. Wird der führende Abruf durch dessen eigenen Aufrufer abgebrochen, kann ein weiterhin aktiver anderer Aufrufer neu anfragen. Eigene Coroutine-Abbrüche bleiben wirksam. Der Prozesscache hält höchstens 64 Einträge: erfolgreiche Karten sechs Stunden, fehlgeschlagene beziehungsweise fehlende Ergebnisse 15 Minuten. Cache-Fristen verwenden die monotone Uhr; das Quellenalter prüft separat den tatsächlichen Abrufzeitpunkt.

`SevJourneyEnricher` begrenzt die Stationsmenge auf 64 eindeutige Slugs und führt höchstens drei Stationsabrufe gleichzeitig aus. Eine gesamte Abrufrunde hat 45 Sekunden Zeit; bereits erfolgreiche Stationsergebnisse bleiben auch bei Ablauf erhalten. Ein Fehler dieser optionalen Quelle darf die API-Route nicht ersetzen oder den Standortservice blockieren.

### HTML- und GeoJSON-Auswertung

`BahnhofSevParser` dekodiert JSON-Argumente der gestreamten Next.js-Datensätze. Webseiten-JavaScript wird nicht ausgeführt. Der Parser benötigt einen eindeutig passenden Stations-Slug und prüft die Bahnhofkoordinate als Quellenkontext. Er übernimmt ausschließlich Features mit `type = RAIL_REPLACEMENT_TRANSPORT` und `geometry.type = Point`. GeoJSON enthält **Längengrad, Breitengrad**; in `SevPoint` werden beide Werte benannt gespeichert.

Unbrauchbare Geometrie, nicht endliche oder außerhalb des Wertebereichs liegende Koordinaten, widersprüchliche Feature-IDs und unterschiedliche Karten derselben Station verwerfen die Quelle. Ein allgemeiner Bahnhofsmittelpunkt ist keine Ersatzhaltkoordinate. Richtungsbezeichnungen, Bearbeitungsstände und separate Weg-/Maßnahmenhinweise bleiben erhalten. `properties.version` beschreibt ausschließlich den Bearbeitungsstand und wird nicht zum Gültigkeitsintervall umgedeutet.

### Eindeutige Besuchs-, Richtungs- und Datumszuordnung

Die Auflösung verwendet bevorzugt die Stopover-UUID, sonst Station-ID und geplante Besuchszeiten. Mehrfach vorhandene Besuchsschlüssel und unzureichende Identität erlauben keine Koordinatenkorrektur. Der Karten-Slug muss zum API-Bahnhof passen; dessen Koordinate muss höchstens 1.500 Meter vom veröffentlichten Bahnhofspunkt entfernt sein. Jeder SEV-Punkt muss gültig sein und innerhalb von 3.000 Metern dieses Quellenpunkts liegen. Die Bahnhofkoordinate dient damit nur der Plausibilität, nicht als Ersatz für einen fehlenden SEV-Punkt.

Eine Quelle darf höchstens 24 Stunden alt sein und nicht aus der Zukunft stammen. Datumsprüfungen verwenden `Europe/Berlin`: Sowohl das aktuelle Datum als auch das Datum der geplanten Haltankunft/-abfahrt müssen zu einer auswertbaren Maßnahme passen. Fehlen parsebare Haltzeiten, wird das aktuelle Datum verwendet. Hinweise auf temporäre Halte ohne auswertbaren Zeitraum, widersprüchliche Datumsgrenzen und mehrere unterschiedliche Maßnahmenzeiträume führen zum Rückfall.

Ein einzelner Punkt ohne Richtungsbezeichnung ist verwendbar, wenn die übrigen Prüfungen bestehen. Bei mehreren oder ausdrücklich richtungsabhängigen Punkten durchsucht der Resolver die folgenden nicht gestrichenen Halte der **vollständigen API-Fahrt**. Der erste belegte Richtungsort muss genau einen Kandidaten ergeben. Er verwendet weder die Gegenrichtung aus vorherigen Halten noch die Luftlinie als Richtungsbeweis. Unbekannte Richtungsformate bleiben mehrdeutig.

Die Quelle für Mülheim nennt temporäre Punkte bis 30.10.2026 und eine zusätzliche Oberhausen-Zuordnung nur bis 09.10.2026. Diese Sonderfrist gilt für den zusätzlichen Richtungsort, nicht für den Duisburg-Punkt insgesamt. Feature-Bearbeitungsdatum, Maßnahmenzeitraum und Richtungs-Sonderfrist bleiben getrennt. Wenn die API-Fahrt tatsächlich in Duisburg endet und keine spätere Richtung belegt, wird keiner der dortigen Richtungspunkte als sichere Ankunft erfunden.

### Tracking, Anzeige und Lebensdauer

Der Service lädt Karten in einer getrennten Coroutine. Übernahme erfordert weiterhin dieselbe Status-ID, Service-Generation, Check-in-Grenzen und vollständige Route. Späte Ergebnisse einer vorherigen Fahrt werden verworfen. Der Service prüft die Zuordnung bei API-Updates, Fahrplan-Ticks und frischen GPS-Fixes erneut; Quellenalter und Maßnahmenende können deshalb eine bisherige Korrektur wieder entfernen.

Nur `TripTrackingService.toTrackingStops()` setzt ein eindeutig aufgelöstes Koordinatenpaar in die interne `TrackingStop`-Projektion ein. Die API-Halte und deren Namen, Stations-ID, Stopover-UUID, Zeiten und Gleise bleiben erhalten. Ändert sich ein physischer Punkt, wird die GPS-Zeitbasis invalidiert. Die Stationsengine entfernt die bisherige Ankunftsbestätigung dieses Punkts, erhält aber Besuchsschlüssel und bereits gesprochene Schlüssel. Ein alter Fix wird durch das eintreffende Kartenergebnis nicht erneut als neue Standortbeobachtung verwendet.

`TrackingLiveState.sevStops` liefert besuchsbezogene Hinweise an das Fahrtdetail. Öffentliche Karten und die vollständige API-Haltfolge können zusammen mit dem vorhandenen aktiven Fahrtcache in DataStore liegen; beim Wiederanlauf wird die Zuordnung neu geprüft. Ein Cache mit SEV-Karten übernimmt die frühere Ankunftsbestätigung nicht: Erst ein frischer Fix darf den physischen Halt wieder bestätigen. Alte Cache-Einträge ohne die optionalen Felder bleiben lesbar. Der Quellen-Prozesscache ist davon getrennt. Weder Gerätepositionen noch Standortverläufe werden als Teil der SEV-Ergänzung gespeichert oder hochgeladen. Fahrtwechsel, Zielabschluss und Service-Ende brechen den laufenden SEV-Abruf ab.

Das ViewModel veröffentlicht die API-Antwort vor seiner separaten Anreicherung und verwirft Ergebnisse bei veränderter Fahrt oder Routenbasis. Ein unveränderter noch laufender Abruf wird beim regulären Refresh weiterverwendet. Die aktive Service-Zuordnung hat in der Anzeige Vorrang vor dem Detailabruf. Der Fahrtkopf kennzeichnet Schienenersatzverkehr; vorhandene Hinweise stehen je Halt mit Richtung, ausklappbarer Wegbeschreibung, Quellenlink und gegebenenfalls Rückfallgrund. Bei diesen Buskandidaten werden Bahn-Gleisangaben in Detailansicht, Fahrtbenachrichtigung, Widget und Sprachausgabe unterdrückt. Der Service stellt außerdem reine Gleiswechselereignisse des Änderungsmonitors nicht zu. Die ursprünglichen API-Gleisfelder bleiben unverändert. Einzelheiten: [StatusDetail](./status-detail.md).

Die SEV-Punkte verbessern den Bezugspunkt der Halterkennung. Sie liefern keine Straßenroute und garantieren keine Bus-ETA: Der bestehende GPS-Zeitkorridor verbindet Halte gerade und kann kurvige Straßenfahrten weiterhin ablehnen. Ungeeignete GPS-Zeitdaten fallen auf API-/Planzeit zurück; SEV-Zuordnung ist kein Anlass, Genauigkeits- oder Bewegungsgrenzen zu lockern.

## Abhängigkeiten

Vorhandenes Gson, OkHttp und Coroutines sowie die Träwelling-Haltfolge. Keine neue Android-Bibliothek, Preference, Room-Tabelle, Retrofit-Route oder RIS::Stations-Freischaltung. Der öffentliche Abruf übermittelt Bahnhofslugs und übliche Verbindungsdaten, aber keine Gerätepositionen und keinen Träwelling-Token. Die HTML-Struktur ist eine Website-Ausgabe ohne zugesicherten API-Vertrag.

## Validierung und offene Fragen

Der Abruf vom 06.10.2026 belegte fünf öffentliche Punkte für Essen, Mülheim und Duisburg; der [Quellenabgleich](./gps-zeiten.md) enthält Koordinaten und Lagepläne. Der aktuelle automatisierte Prüflauf und dessen Grenzen stehen unter [Tests](../entwicklung/tests.md); ein Live-Quellenabruf allein belegt keine reale Busansage oder GPS-Prognosegüte.

- TODO: Die automatische Ergänzung mit realem RE1-Busersatzverkehr testen, insbesondere Einstieg an der Kruppstraße, Mülheimer Richtungswechsel, kurz hintereinander liegende Halte, Signalverlust und Wiederanlauf mit Cache.
- TODO: Duisburgs tatsächlichen Ankunftspunkt anhand der konkreten vollständigen API-Fahrt und vor Ort verifizieren. Bei fehlender Richtung muss die Anzeige die unbestätigte Position beibehalten.
- TODO: Weitere Bahnhofs-Slugs, Richtungsformulierungen, ungewöhnliche Maßnahmenzeiträume und andere SEV-Linien prüfen. Die Bus-RE/RB-Regel ist bewusst begrenzt.
- TODO: Änderungen der Website-Struktur, dauerhafte Nutzungsbedingungen, regelmäßige Abrufe und Akku-/Netzwerkverbrauch bewerten. Nach Ende einer Maßnahme dürfen alte öffentliche Punkte keine GPS-Korrektur mehr liefern.

## Verwandte Seiten

- [GPS-Zeiten](./gps-zeiten.md)
- [TripTracking](./trip-tracking.md)
- [StatusDetail](./status-detail.md)
- [Datenmodell](../daten/datenmodell.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Externe Schnittstellen](../api/externe-schnittstellen.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Tests](../entwicklung/tests.md)
- [Offene Fragen](../offene-fragen.md)
