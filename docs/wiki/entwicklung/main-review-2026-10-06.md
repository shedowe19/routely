# Main-Review vom 06.10.2026 nach der Streckenmigration

## Zweck und Prüfstand

Erneute Prüfung des vollständigen Main-Stands `9415e290309bb3e3d2e108e6ba9ca42f4fe03243` nach Einbindung nativer Fahrtformen und Bibliotheksmigration. Alle 220 versionierten Dateien und Dateimodi des lokalen Checkouts stimmen mit Remote-Tree `0cdc4aeaef1e4b17795e4eb0d0d5fcca486b9eba` überein. Die lokale Checkpoint-Historie unterscheidet sich; geprüft wurde der identische Dateiinhalt.

Sechs unabhängige Reviewer untersuchten Oberfläche/Bedienung, GPS/Geometrie, Android-Laufzeit/Audio/Energie, Sicherheit/API, Daten/ViewModels und Build/Release/Tests. Quellcode, bestehende Regressionen und die betroffenen Wiki-Verträge wurden gegengeprüft. Der ursprüngliche Review veröffentlichte ausschließlich Dokumentation. Die nachstehend historisch belegten Befunde wurden anschließend auf Nutzerauftrag vollständig korrigiert; der aktuelle Umsetzungsstand folgt nach den Befundtabellen.

P1 bezeichnet hier einen Fehler, der die aktive Begleitung falsch abschließen kann. P2 betrifft falsche Daten, fehlende Funktionen oder unzureichend abgesicherte Veröffentlichungen. P3 betrifft weniger dringliche Anzeige- beziehungsweise derzeit nicht erreichbare Helferpfade. Dies sind Review-Prioritäten, keine gemessenen Häufigkeiten.

## Nachweise und Grenzen

Der eigene [CI-Lauf des geprüften Main 37523081347](https://github.com/shedowe19/routely/actions/runs/37523081347) ist erfolgreich. Heruntergeladene Berichte bestätigen 571 Tests in 37 Klassen, ohne Fehlschläge, Fehler oder übersprungene Tests. Vollständiges Debug-Lint meldet 0 Fehler und 53 Warnungen; Debug-APK und unsignierte Release-APK wurden gebaut. Die Warnungen betreffen KTX (15), Versionshinweise (14), Icons (12), Texte (5), veraltete SDK-Abfragen (4) sowie Ziel-API, Akkuhinweis und TOML (je 1). Die SDK-36-kompatiblen Versionspins sind keine automatisch zu behebenden Fehler.

Zusätzlich wurden zwei temporäre JUnit-Klassen gegen unveränderten Produktivcode ausgeführt. Drei Schutzassertions schlugen wie vorhergesagt fehl: zwei für GPS-Sprung/Abschluss/Ansage und eine für den Zielrücksetzpfad im Texteditor. Der Gradle-Lauf kompilierte erfolgreich und endete nach 17 Sekunden wegen dieser drei Assertionfehler. Die temporären Tests wurden anschließend aus dem Repository entfernt. Dieser Befundnachweis ist getrennt von der grünen bestehenden Suite; dauerhafte Regressionen sind Teil der jeweiligen nachfolgenden Fehlerkorrektur.

Es gab keine schreibenden Live-API-Aufrufe, keine Release-Veröffentlichung, keine Verwendung echter Kontotokens und keine Geräteprüfung. Synthetische Positionsfolgen beweisen einen Codepfad, rekonstruieren aber keine frühere Nutzerfahrt. Weitere Befunde beruhen auf konkreten Quell-/Request-Reihenfolgen oder primären API-/Plattformverträgen; ihre Häufigkeit auf dem Gerät bleibt unklar.

## GPS und Android-Begleitung

| ID / Priorität | Auslöser und Auswirkung | Quellbeleg | Empfohlene Korrektur |
| --- | --- | --- | --- |
| G1 / P1 | Nach zwei langsamen Fixes am bestätigten Einstieg erscheint ein scheinpräziser Fix mehr als zwei Kilometer entfernt am Ziel nur zwei Sekunden später. Ein weiterer Zielfix kann die Fahrt abschließen und vorher die Zielansage reservieren. Beide Auswirkungen wurden mit temporären Tests bestätigt. | [StationTrackingEngine](../../../app/src/main/kotlin/de/traewelling/app/service/StationTrackingEngine.kt), Zeilen 245–272 und 703–722: `isReliable` prüft Alter/Genauigkeit/Koordinaten; die normale `leftArrival`-Bedingung kann die getrennten Recovery-/Rail-Sprungschutzpfade umgehen. | Physische Übergänge und Ankunftsbelege gegen eine gemeinsame plausible Bewegungsfolge prüfen. Lange echte GPS-Lücken weiterhin getrennt wiederverankern; alte Ansage-/Abschlussbelege nicht aus einem verworfenen Sprung übernehmen. |
| G2 / P2 | Eine vorübergehend fehlgeschlagene Registrierung bei Fused Location Provider löscht den Callback. Nach Behebung der Störung registriert der laufende Service keinen Ersatz; GPS bleibt bis zum erneuten sichtbaren Activity-Start aus. | [TripTrackingService](../../../app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt), Zeilen 989–1009 sowie 515–532. | Begrenzter Retry mit Backoff und Sitzungs-/Generationsprüfung, nur bei weiterhin autorisiertem GPS. Entzogene Freigaben nicht durch automatische Wiederanfragen übergehen. |
| G3 / P2 | Frische Standortdaten werden monoton auf ihr Alter geprüft, anschließend aber mit `Location.time` sortiert/dedupliziert. Eine rückwärts springende Uhr kann neue Fixes am alten Wasserzeichen blockieren; unterschiedliche Provideruhren können die Wallclock-Altersprüfung verletzen. | Service, Zeilen 915–927; Engine, Zeilen 245–258 und 867–871; [Android Location](https://developer.android.com/reference/android/location/Location#getTime()). | Monotone Beobachtungsreihenfolge und Alter von darstellbaren Ereigniszeiten trennen. Uhrsprünge dürfen keine vorherige Bewegungsbasis oder Prognose wiederbeleben. |
| G4 / P2 | Wechsel der TTS-Engine A→B während der Fahrt betrifft die Testansage in Settings, aber nicht die bestehende Serviceinstanz. Stationsansagen bleiben auf A; auch ein Initialisierungsfehler bleibt ohne Wiederanlauf dauerhaft unbehandelt. | Service, Zeilen 183–186, 253–254 und 1133–1138; [SettingsViewModel](../../../app/src/main/kotlin/de/traewelling/app/viewmodel/SettingsViewModel.kt), Zeilen 45–54 und 136–140. | TTS-Konfiguration im Service beobachten, die Engine generationsgebunden erneuern und Initialisierungsfehler sichtbar behandeln. Wartende Ansagen dabei erneut auf ihre aktuelle Relevanz prüfen. |

Die bestehende Session-/Generationsbindung nativer Formen, deren RAM-Cache und begrenztes Parsing ergaben keinen weiteren bestätigten Blocker. WakeLock-Freigaben bei Wechsel, Stop, Destroy und Timeout sind nachvollziehbar abgesichert. Daraus folgt keine Garantie für tatsächliche Samsung-/Doze-/Audiozustellung.

## Datenänderungen und Oberfläche

| ID / Priorität | Auslöser und Auswirkung | Quellbeleg | Empfohlene Korrektur |
| --- | --- | --- | --- |
| D1 / P2 | Editor für Ziel A öffnen, nur Text ändern; anderer Client setzt B; der 30-Sekunden-Refresh übernimmt B, während das Formular A behält. Speichern sendet A als vermeintlich bewusst geändertes Ziel und überschreibt B. Der Requestpfad wurde per temporärem Test bestätigt. | [StatusDetailViewModel](../../../app/src/main/kotlin/de/traewelling/app/viewmodel/StatusDetailViewModel.kt), Zeilen 111–145 und 329–350; [StatusEditRequest](../../../app/src/main/kotlin/de/traewelling/app/viewmodel/StatusEditRequest.kt), Zeilen 9–16. | Originalbesuch des Editors explizit speichern; eine Zieländerung ausschließlich gegenüber diesem Edit-Anfangswert bestimmen. Konflikte gegebenenfalls anzeigen. |
| D2 / P2 | Ein vor `markAsRead` begonnener GET liefert seine alte ungelesene Meldung erst nach erfolgreich beendetem Read-POST. `pendingReads` ist bereits entfernt; die Antwort darf die Meldung wieder als ungelesen veröffentlichen. Auch parallele normale Count-Requests besitzen keine eigene Antwortgeneration. | [NotificationViewModel](../../../app/src/main/kotlin/de/traewelling/app/viewmodel/NotificationViewModel.kt), Zeilen 50–78, 104–125 und 154–160. | Lesemutationen mit der Listenantwortversion abgleichen oder bestätigte Markierungen bis zu einem neueren Snapshot überlagern. Count-Requests separat ordnen beziehungsweise zusammenführen. |
| D3 / P2 | Zwei verschiedene Stations-IDs innerhalb von etwa 200 Metern werden bereits wegen eines gemeinsamen Ortsworts zusammengelegt. Synthetisches Beispiel: „Teststadt Markt“ `(51,7)` und „Teststadt Rathaus“ `(51.0005,7)`; die zweite ID verschwindet aus Check-in und Fahrterkennung. | [TraewellingRepository](../../../app/src/main/kotlin/de/traewelling/app/data/repository/TraewellingRepository.kt), Zeilen 155–181. | Nur gleiche Stationsidentitäten oder belegte gemeinsame Identifier deduplizieren. Name und Nähe allein belegen keine austauschbaren Halte. |
| U1 / P2 | Die Check-in-Felder „Zeiten anpassen“ ersetzen POST-`departure`/`arrival`, obwohl diese die geplanten Besuche identifizieren. Eine Ankunftskorrektur ohne exakten Planzeitmatch wird abgelehnt; eine geänderte Abfahrt kann beim eindeutig vorhandenen Einstieg nur ignoriert werden. | [CheckInViewModel](../../../app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInViewModel.kt), Zeilen 352–374; [Upstream CheckinService](https://github.com/Traewelling/traewelling/blob/4d602796da8409017314cc771b1127d169155f02/app/Services/Checkin/CheckinService.php#L172), Zeilen 172–203; Hydrator desselben Commits, Zeilen 69–81. | Beim Erstellen die ausgewählten Planmarker unverändert senden. Manuelle Zeiten erst über den unterstützten Statuseditor/PUT setzen oder die irreführende Eingabe entfernen. |
| U2 / P2 | Am Ausstieg bevorzugt die Timeline das Echtzeit-Abfahrtsgleis der Weiterfahrt statt des Ankunftsgleises. Beispiel: Ankunft Gleis 2, Weiterfahrt Gleis 4 ergibt Gleis 4 am eigenen Ziel; passende Plan-Gleise fehlen ebenfalls im Rückfall. | [StatusDetailScreen](../../../app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt), Zeilen 999–1002. | Den vorhandenen ereignisbezogenen `trackingPlatform`-Helfer nutzen oder Ankunft/Abfahrt ausdrücklich getrennt beschriften. |
| U3 / P2 | Die Abfahrtsauswahl zeigt bevorzugt Planzeit und nur positive Verspätung. Plan 18:00, Echtzeit 17:57 erscheint als 18:00 ohne Verfrühungshinweis. Der negative Modellwert existiert bereits. | [CheckInScreen](../../../app/src/main/kotlin/de/traewelling/app/ui/screens/CheckInScreen.kt), Zeilen 344–347 und 368; [Models](../../../app/src/main/kotlin/de/traewelling/app/data/model/Models.kt), Zeilen 223–225. | Echtzeit neben Plan beziehungsweise auch negative Abweichungen anzeigen; Darstellung separat testen. |
| U4 / P3 | Nach erfolgreichem Löschen im Detail bleibt eine bereits geladene Feedkarte sichtbar. Erneutes Öffnen führt bis zum manuellen Refresh zu 404; RAM-/Room-Feed werden nicht invalidiert. | Detail-ViewModel, Zeilen 247–266; [FeedScreen](../../../app/src/main/kotlin/de/traewelling/app/ui/screens/FeedScreen.kt), Zeilen 32–35; Repository, Zeilen 108–111. | Erfolgreiche Änderungen/Löschungen an den Feed melden und den sitzungsgebundenen Cache synchronisieren. |

## Sicherheit und Release

| ID / Priorität | Auslöser und Auswirkung | Quellbeleg | Empfohlene Korrektur |
| --- | --- | --- | --- |
| S1 / P2 | `allowBackup=true` ohne Ausschlüsse nimmt bei aktivierter Systemsicherung reguläre DataStore-/Datenbankdateien auf. Darin stehen Zugangsdaten sowie privater Fahrt-/Feedcache. Auch die tatsächlich zusammengeführten Debug-/Release-Manifeste enthalten keine ergänzenden Regeln. | [Manifest](../../../app/src/main/AndroidManifest.xml), Zeile 24; [PreferencesManager](../../../app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt), Zeile 16; [AuthSession](../../../app/src/main/kotlin/de/traewelling/app/util/AuthSession.kt), Zeilen 15–24; [Android Auto Backup](https://developer.android.com/identity/data/autobackup). | `datastore/traewelling_prefs.preferences_pb` und sensible Datenbankdateien aus Cloud-/D2D-Sicherung ausschließen: alte `fullBackupContent`-Regeln und neue `dataExtractionRules`. Einstellungen bei Bedarf getrennt sichern. Der Befund behauptet weder unverschlüsselte Cloudablage noch fremden App-Zugriff. |
| S2 / P3 | Der derzeit nicht an die UI angebundene Refresh-Helfer löscht einen bestehenden Refresh-Token, wenn die erfolgreiche Antwort keinen neuen enthält. Das ist laut OAuth-Vertrag eine zulässige Antwort; ein nächster Refresh kann dann nicht mehr starten. | [AuthRepository](../../../app/src/main/kotlin/de/traewelling/app/data/repository/AuthRepository.kt), Zeile 91; AuthSession, Zeile 61; [RFC 6749 §6](https://www.rfc-editor.org/rfc/rfc6749#section-6). | Ausschließlich beim Refresh den bisherigen Refresh-Token behalten, falls kein Ersatz ausgegeben wird. OAuth-Fehlercodes statt pauschal jedes HTTP 400 als verlorene Autorisierung behandeln. |
| R1 / P2 | Die Releasevalidierung erlaubt eine bereits veröffentlichte Versionsbezeichnung. Beispiel `1.8.7`/Code 14 wird akzeptiert, obwohl `v1.8.7` bereits existiert. Die Releaseaction aktualisiert bestehende Releases und überschreibt Dateien standardmäßig; neuer APK-Inhalt kann unter einem alten Quelltag landen. | [android.yml](../../../.github/workflows/android.yml), Zeilen 32–39 und 103–108; [Releaseaction-Vertrag](https://github.com/softprops/action-gh-release/tree/v2#usage). | Vorhandene Tags/Releases vor dem Build abweisen, Dateien nicht überschreiben und Releasejobs serialisieren. Keine solche Veröffentlichung wurde im Review ausgeführt. |
| R2 / P2 | Der Versionscode wird nur auf Zahlenbereich geprüft, Default ist 1. Ein neuer Versionsname mit Code 1 wird akzeptiert, obwohl der signierte Lauf für 1.8.7 bereits Code 13 nutzte; eine reguläre Updateinstallation ist damit blockiert. | Workflow, Zeilen 10–13 und 36–39; [signierter Lauf 37477231988](https://github.com/shedowe19/routely/actions/runs/37477231988). | Monotone Versionsquelle und Abgleich mit dem veröffentlichten Höchstwert vor Signierung. Parallel gestartete Releases dürfen keinen gleichen/niedrigeren Code erhalten. |
| R3 / P2 | `target_commitish` fehlt. Bei Branch-Dispatch oder einem während des Builds fortgeschriebenen Main kann der neue Tag auf den aktuellen Defaultbranch statt auf den tatsächlich gebauten Commit zeigen. | Workflow, Zeilen 103–108; [Releaseaction-Eingaben](https://github.com/softprops/action-gh-release/tree/v2#inputs). | Den Tag ausdrücklich an `${{ github.sha }}` binden und die Tagidentität prüfen. Ein erfolgreicher Build allein belegt diese Bindung nicht. |

## Korrekturstand aller 16 Befunde

Sechs parallel arbeitende Implementierer und unabhängige Gegenprüfungen ergänzen die folgenden Korrekturen. Die ursprünglichen Quellzeilen oben beziehen sich ausschließlich auf den historischen Prüfcommit; heutige Dateien haben andere Zeilennummern.

| Befund | Eingebaute Korrektur | Dauerhafte Prüfung |
| --- | --- | --- |
| G1 | Gemeinsamer Kurzsprungschutz vor Ankunft, Abfahrt, Cursor, Ansage und Abschluss; verworfener Fix ersetzt keine plausible Referenz. Lange Lücken verwenden weiterhin eigene Wiederverankerung. | `StationTrackingJumpTest`, bestehende Engine-/Tunnel-/Railfälle |
| G2 | Drei begrenzte Registrierungsretrys nach 2/5/15 Sekunden; Callback-, Fahrt-, Generations- und Freigabeprüfung; sichtbarer Fehler. | `LifecycleRetryBudgetTest`, Service-Gegenprüfung |
| G3 | Alter und Reihenfolge aus monotoner Providerzeit; aktuelle Ereigniszeit minus Fixalter; Uhrsprung entfernt Bewegungs-/ETA-Basis, erhält Besuch und Ansagekeys. Erkennung verwirft alte asynchrone Suchergebnisse. | `TrackingLocationObservationTest`, Sprung-/Clockfälle, Gegenprüfung beider Services |
| G4 | Engine/Enable/Sprache/Stimme reaktiv; 10-Sekunden-Initfrist, begrenzter Wiederanlauf und Versuchstokens; wartende Ansagen erneut besuchsbezogen prüfen. | `SpeechInitializationLifecycleTest`, Service-Gegenprüfung |
| D1 | Zielvergleich gegen Editor-Anfangsstatus; ausdrückliche manuelle Ankunftsabsicht getrennt von Providerrefresh. | `StatusEditRequestTest` |
| D2 | Bestätigte Einzel-/Alle-Lesemarkierungen überlagern alte GETs; Countanfragen besitzen eigene Reihenfolge und Mutex. | `NotificationControllerTest` mit virtueller Coroutine-Zeit |
| D3 | Deduplizieren anhand belegter Stationsidentität; verschiedene interne IDs bleiben trotz Nähe oder gemeinsamem Ortsnamen erhalten. | `NearbyStationIdentityTest` |
| U1 | Geplante Besuchsmarker unverändert im POST; manuelle Istzeiten danach im PUT derselben Sitzung; Teilfehler und unvollständige akzeptierte Erstellung erlauben keinen zweiten POST. | `CheckInSubmissionTest`, Repositorytests |
| U2 | Am Einstieg das Abfahrtsgleis, am Ziel das Ankunftsgleis und passende Planwerte mit dem gemeinsamen ereignisbezogenen Helfer verwenden. | Gemeinsamer `trackingPlatform`-Vertrag und Modelltests |
| U3 | Parsebare Echtzeit, abweichende Planzeit und auch negative Abweichung anzeigen. | `DepartureTimePresentationTest` |
| U4 | Erfolgreiche Statusmutationen invalidieren RAM-/Room-Basis ihrer Credentialpartition; Events und Feedübernahme bleiben revisionsgebunden. Alte GETs werden verworfen. DELETE-Nachlauffehler warnt, beendet Busy und navigiert genau einmal. | Repository-Mutationsfälle, `FeedControllerTest`, `DeletedStatusCompletionTest` |
| S1 | Altes und neues Backupregelwerk schließen DataStore sowie Datenbanken mit Journals/WAL/SHM aus Cloud-/Geräteübertragung aus. | XML-/zusammengeführte Manifestprüfung, Android-Lint |
| S2 | Nicht ersetzten Refresh-Token erhalten; Sitzung nur bei streng belegtem `invalid_grant` in passender Authrevision entfernen. | `AuthRepositoryTest` einschließlich ungültiger/temporärer Fehlerantworten |
| R1 | Vorhandene Tags/Releases ablehnen; global serialisieren; neuen Tag/Draft reservieren und Assets nur create-only auf eigener Release-ID hochladen. | Offline-Releaseguardtests mit Konflikten, Teilfehlern und API-Doubles |
| R2 | Durable Legacy-Untergrenze 13 plus validierte künftige Versionsassets; auto Höchstcode + 1, explizit nur größer; unklare Historie stoppt. | Offline-Releaseguardtests einschließlich neuer Historie und Rennen |
| R3 | Checkout/Tag am exakten Dispatchcommit, APK-Manifest/Hash und Metadaten prüfen; veröffentlichte Identität erneut lesen. | Offline-Releaseguardtests mit falscher SHA, Manifest-/Digestfehlern und alter Draftidentität |

Die Gegenprüfung behob zusätzlich bestätigte Folgefehler bei erfolgreicher alter Mutation nach erneutem Login mit gleichen Credentials, erfolgreicher PUT-Antwort ohne brauchbaren Statusbody, akzeptierter POST-Antwort ohne Status-ID sowie lokalem DELETE-Cleanupfehler. `kotlinx-coroutines-test` ist ausschließlich eine Testabhängigkeit; produktive Bibliothekspins und SDK-Ziele bleiben gleich.

Aktuelle ausgeführte Ergebnisse stehen unter [Tests](./tests.md). Geräte-, Restore- und signierte Live-Releaseprüfungen bleiben getrennt; es gab keine schreibenden Träwelling-Tests und keine Nutzung echter Kontotokens. Architekturentscheidungen stehen in der [ADR Befundkorrekturen](../entscheidungen/2026-10-06-befundkorrekturen.md).

## Sinnvolle Verbesserungen nach den Fehlerkorrekturen

- Unveränderte Notification-/Widget-Payloads unterdrücken. Der Service sendet sie derzeit bei jedem 10-Sekunden-Tick und GPS-Update bis alle drei Sekunden erneut; GPS-/Ansagefrequenz muss hierfür nicht sinken. Kein Akkugewinn wurde gemessen.
- Eine Diagnoseansicht für letzte Fixzeit, Genauigkeit, tatsächliche Geometrie-/Zeitquelle und TTS-/Registrierungszustand ergänzen. Der bestehende Prognose-Ablehnungsgrund ist ein Ausgangspunkt; eine automatisch gespeicherte Geräte-GPS-Historie ist damit nicht beschlossen.
- Virtuelle Coroutine-Requestprüfungen für Meldungen und Feed sowie Editor-Anfangssnapshot-Regressionen sind jetzt ergänzt. Instrumentierte Compose-/Service-/Permission-/TTS-Prüfungen bleiben sinnvolle eigene Erweiterungen.
- Nach stabiler Laufzeit Release-Verkleinerung durch R8/Resource-Shrinking prüfen, einschließlich Gson-/Room-/Reflexionsverträglichkeit. Aktuelle unsignierte Release-APK: ungefähr 16,3 MB. Kein neuer gemessener Ressourcenblocker wurde gefunden.
- Bekannte UI-Lücken bleiben OAuth-Anbindung/Erneuerung, Sichtbarkeitsauswahl und Meldungsnavigation; diese sind schon dokumentiert und keine neue Regression der Bibliotheksmigration.

## Offene Fragen

- Alle 16 Codebefunde sind korrigiert und gezielt abgesichert. Keine Schutzgrenze wurde zugunsten alter unrealistischer Testpositionen gelockert.
- TODO: System-/Compose-/Backup-Restore-Prüfungen und ersten neuen signierten Release ergänzen; die optionalen Ausbauideen sind keine verbleibenden Codebefunde dieses Reviews.
- TODO: Reale GPS-/ETA-Güte, Samsung-Sperrbildschirm, Display-aus-/Doze-/Audiozustellung und Ressourcenverbrauch weiterhin auf einem Gerät prüfen. Der Review liefert hierfür keine Garantie.

## Verwandte Seiten

- [Tests](./tests.md)
- [Deployment](./deployment.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [TripTracking](../module/trip-tracking.md)
- [StatusDetail](../module/status-detail.md)
- [Check-in](../module/checkin.md)
- [Notifications](../module/notifications.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Offene Fragen](../offene-fragen.md)
