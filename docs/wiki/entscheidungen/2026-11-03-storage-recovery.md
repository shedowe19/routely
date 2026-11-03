# Sichere Storage-Recovery

## Zweck und Status

Entscheidung vom 03.11.2026: P12 erhält eine gemeinsame Speicherfehlergrenze und eine erreichbare Wiederherstellungsoberfläche. Die Umsetzung wurde mit v1.12.0 aus Commit `94bf23cc13063195376a7e2fdcdc8596b740f8c4` veröffentlicht. Tatsächlich ausgeführte Ergebnisse stehen unter [Tests](../entwicklung/tests.md), der Signatur-/APK-Nachweis unter [Deployment](../entwicklung/deployment.md#verifizierte-veröffentlichung-v1120). Diese Entscheidung ersetzt keine Geräte- oder Restoreprüfung.

## Kontext

Eine Preferences-Datei enthält Anmeldung, Sitzungsrevision, aktive Fahrt, Fahrtcache und Einstellungen. Ein leerer Fehlerersatz könnte einen Lesefehler wie eine Abmeldung oder beendete Fahrt darstellen. Activity, Services und ViewModels erzeugen mehrere Managerinstanzen und benötigen deshalb denselben Fehler- und Wiederherstellungszustand.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/util/PreferenceStorage.kt`
- `app/src/main/kotlin/de/traewelling/app/util/PreferenceStorageRegistry.kt`
- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`
- `app/src/main/kotlin/de/traewelling/app/util/StorageRecoveryPresentation.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/components/StorageRecoveryDialog.kt`
- `app/src/main/kotlin/de/traewelling/app/service/StorageRecoveryServiceBoundary.kt`

## Entscheidung

1. Ein geteilter Owner hält ausschließlich bestätigte Werte für die Darstellung. Direkte Getter lesen frisch; jede fachliche Schreibbedingung bleibt zusätzlich innerhalb der DataStore-Transaktion. Speicherbereitschaft ist ein eigener Zustand vor Setup und Navigation.
2. IO-Lesefehler werden begrenzt wiederholt. Nach fehlgeschlagenem Speichern prüft eine ausdrückliche Recovery mit einem neuen internen Probewert tatsächlich das Schreiben. Fachliche Aktionen und angenommene HTTP-Anfragen werden dabei nicht wiederholt.
3. Ein modaler Dialog sperrt während einer Störung die Bedienung und bewahrt bestehende Inhalte. Wiederhergestellte Bereitschaft erhält eine neue Generation. Anmeldung und Servicekonfiguration werden danach frisch geprüft; eine alte Sitzung oder ein noch beendender Service genügt nicht für einen Neustart.
4. Laufende Begleitung und Fahrterkennung werden bei fehlendem Speicherzugriff im Speicher beendet. Die dauerhafte aktive ID, Route und der Erkennungs-Opt-in bleiben erhalten. Das Widget benennt die Pause.
5. Ein bestätigter Korruptionsreset ist ausschließlich an den aktuellen Fehlerzyklus gebunden. Ein einmaliger Handler derselben DataStore-Instanz ersetzt den beschädigten Inhalt und erzeugt eine neue Sitzungsrevision. Lokale Zugangsdaten, Einstellungen und aktive Begleitung gehen dann verloren; serverseitige Fahrten bleiben bestehen. Eine alte Bestätigung kann später keine Daten löschen.
6. Es wird keine zweite aktive DataStore-Instanz erzeugt und keine Datei neben dem laufenden Store gelöscht. Es gibt keinen Credentialexport oder zusätzliche Kopie der Zugangsdaten. Ein beschädigtes Fahrtcache-JSON bleibt ein gezielter Cache-/Neuabrufpfad und löst keinen Anmeldungsreset aus.

Der [DataStore-Vertrag](https://developer.android.com/reference/kotlin/androidx/datastore/core/DataStore) begründet frische Leseversuche und atomare, erst nach Persistenz erfolgreiche Updates. Die [Korruptionsschnittstelle](https://developer.android.com/reference/androidx/datastore/core/CorruptionHandler) bindet Ersatzdaten an die intern erkannte Korruption; sie darf nicht selbst wieder DataStore aufrufen.

## Folgen und Validierung

- Erfolgreiche Lesezugriffe sind von wieder nachgewiesener Schreibbarkeit getrennt.
- Ein angenommener Check-in beziehungsweise PUT/DELETE bleibt bei anschließendem Storagefehler als Teilerfolg erkennbar; eine unprüfbare Sitzung darf keine fremde Fahrt bereinigen.
- Typisierte Storagefehler zeigen feste verständliche Meldungen. Cancellation und fachliche Validierungsfehler behalten ihre ursprüngliche Bedeutung.
- Injizierte Fehlerfälle prüfen Retrygrenzen, neue/alte Bestätigung, Session-/Cacheerhalt und verspätete Beobachter. Ein echter DataStore-Test mit beschädigter Datei prüft die Byteerhaltung vor der Bestätigung.

Der JVM-Dateitest aktiviert testlokal die unterstützte SDK-26-Fixture, weil Androidstubs sonst `SDK_INT = 0` liefern und einen für Routely nicht unterstützten alten Dateipfad wählen. DataStore-Factory, Serializer und Dateibytes bleiben echt. Nach vollständig beendetem ersten Store wird dieselbe Datei frisch geöffnet und die neue Sitzungsrevision erneut gelesen. Die SDK-Fixture wird auch bei Testabbruch zurückgesetzt. Das ist kein Android-Geräte-, Stromausfall- oder physischer Restorelauf.

## Offene Fragen

- TODO: Auf dem Gerät vollen oder vorübergehend unzugänglichen Speicher, Abbruch und Bestätigung des Resets, Servicepause/Wiederstart und TalkBack prüfen. Cloud-/Geräteübertragung und reale GPS-Wiederverankerung behalten ihre eigenen Praxisbedingungen.

## Verwandte Seiten

- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Settings](../module/settings.md)
- [TripTracking](../module/trip-tracking.md)
- [Bildschirme](../ui/screens.md)
- [Tests](../entwicklung/tests.md)
- [Offene Fragen](../offene-fragen.md)
