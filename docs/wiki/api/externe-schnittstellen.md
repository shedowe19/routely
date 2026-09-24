# API: Externe Schnittstellen

## Zweck

Dokumentation der externen APIs, mit denen die App kommuniziert.

## Träwelling API

Die primäre externe Schnittstelle für Konto, Check-ins und Fahrten ist die Träwelling REST-API.

- **Basis-URL**: Die App verwendet die im `PreferencesManager` gespeicherte Träwelling-Serveradresse für ihre Retrofit-Konfiguration.
- **Authentifizierung**: OAuth 2.0. Endpunkte erfordern einen Bearer-Token, der via `OAuthApiService` (`POST /oauth/token`) geholt und erneuert wird.
- **Verkehrsdaten**: Die Check-in- und Fahrtenansichten verwenden die von Träwelling gelieferten Daten. Der direkte Transitous-Zugriff der Live-Karte ist davon getrennt.

Besonderheiten beim Umgang mit den von Träwelling gelieferten Verkehrsdaten:

- **Duplikate**: Suchantworten können doppelte Bahnhöfe oder Stationen enthalten. Auch bei Abfahrten können doppelte `tripId`-Einträge auftreten; die Anwendung behandelt diese im jeweiligen Kontext.
- **HafasTripId**: Wird für `getTrip` benötigt.
- **Plattform-Präfixe**: Bei manchen DB-Bahnhöfen werden interne Plattform-IDs mit dem Sektor-Code `9` (z.B. `91` für Gleis 1) zurückgegeben. Diese müssen vor der Anzeige gestrippt werden.

## Transitous / MOTIS 2

Die [Live-Karte](../features/transitous-live-karte.md) fragt die öffentliche Transitous-API unter `https://api.transitous.org/` direkt ab. Der Endpunkt `GET /api/v6/map/trips` liefert Fahrtabschnitte mit Liniengeometrie, Fahrtzeiten und gegebenenfalls angepassten Echtzeitzeiten. Die Anfrage enthält den sichtbaren geografischen Ausschnitt (`min`, `max`), Zoomstufe und ein begrenztes Zeitfenster (`startTime`, `endTime`). Sie benötigt **keinen Träwelling-OAuth-Token**.

Der Endpunkt liefert keine gemessene GPS-Position des Fahrzeugs. Die angezeigte Position wird aus Fahrtzeit und Liniengeometrie geschätzt. `realTime` kennzeichnet Echtzeitinformationen zu einer Fahrt, **nicht** eine gemessene Fahrzeugposition. Die Datenverfügbarkeit unterscheidet sich nach Region und Verkehrsunternehmen.

Die [Transitous-Nutzungsregeln](https://transitous.org/api/) verlangen einen `User-Agent` mit App-Name, Version und Kontaktmöglichkeit, einen sichtbaren Link zu den [Datenquellen](https://transitous.org/sources/) und die Beachtung der [OpenStreetMap-Namensnennung](https://www.openstreetmap.org/copyright). Die API ist für quelloffene, nichtkommerzielle und ressourcenschonende Projekte vorgesehen. Bei vielen Nutzern oder Anfragen ist vorab Kontakt mit Transitous vorgesehen. Es ist kein festes numerisches Anfragelimit veröffentlicht.

## Verwandte Seiten

- [API Überblick](./ueberblick.md)
- [Interne Schnittstellen](./interne-schnittstellen.md)
- [Transitous Live-Karte](../features/transitous-live-karte.md)
