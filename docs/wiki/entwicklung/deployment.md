# Entwicklung: Deployment

## Zweck

Dokumentiert Prüfung, Signierung und Veröffentlichung einer neuen Android-Version.

## GitHub Actions

`.github/workflows/android.yml` baut Releases manuell per `workflow_dispatch`. Eingaben sind ein neuer `version_name` und optional `version_code`. Ein leeres Codefeld wählt den höchsten veröffentlichten oder dauerhaft verbrauchten Code + 1; ein expliziter Code muss größer sein. Der historisch belegte Mindeststand ist Code 13 aus dem signierten Lauf für `v1.8.7`.

Der Workflow installiert Android-SDK 36 und Build Tools 35.0.0, führt Unit-Tests vor dem Release-Build aus und signiert erst danach mit GitHub Secrets. APK und `release-version.json` werden als Workflow-Artifact `release-apk` hochgeladen. Die APK heißt `routely-v<version_name>.apk`. Zulässige Eingaben und Toolchain stehen unter [Build](./build.md).

## Unveränderliche neue Veröffentlichung

`.github/scripts/release_guard.py` verwendet ausschließlich Python-Standardbibliotheken. Beide Workflows führen seine Offline-Tests aus. Die globale Concurrency-Gruppe des manuellen Releaseworkflows serialisiert alle Branches und bricht einen laufenden Release nicht ab. Der Checkout ist ausdrücklich `${{ github.sha }}`; der Helfer vergleicht auch die tatsächliche lokale HEAD-SHA.

Vor dem Build werden vorhandene Tags oder Releases mit derselben Bezeichnung abgewiesen. `.github/release-version-floor.json` bindet die 35 bekannten historischen Releases an Tag und Release-ID und bewahrt deren belegten Höchstcode. Alle weiteren veröffentlichten Releases müssen gültige Metadaten enthalten. Die begrenzte, paginierte Historienprüfung wählt einen strikt höheren eindeutigen Code; unbekannte, widersprüchliche oder unvollständige Historie führt zum Abbruch. Authentifizierte API- und Uploadanfragen bleiben bei den GitHub-HTTPS-Origins; eine Metadaten-CDN-Weiterleitung erhält keine Authentifizierung.

Nach Build und Signierung prüft `aapt` Paket-ID, Versionsname und Code der tatsächlichen APK. Metadaten speichern exakte Commit-SHA, Tag, APK-Namen, Größe und SHA-256. Unmittelbar vor der Reservierung werden Version und Historie erneut geprüft. Eine atomare Create-ref-Anfrage reserviert den neuen Tag an der gebauten SHA, eine Create-release-Anfrage den neuen Draft. Keine Update-/Force-/Delete-Operation ersetzt eine bestehende Version.

Die Publikation nutzt ausschließlich die reservierte Release-ID. Zwei Create-only Asset-POSTs laden APK und Metadaten hoch; ein schon vorhandenes Asset führt zum Abbruch. Der Helfer prüft Draftidentität, aufgelöste Tag-SHA, vollständigen Metadateninhalt und den von GitHub gelieferten APK-Digest. Fehlender oder falscher Digest blockiert die Veröffentlichung. Der Versionscode wird vor den Uploads und vor dem abschließenden `draft:false` erneut geprüft. Danach werden Release, Tag und Assets nochmals gelesen und verglichen.

## Dauerhaft verbrauchte Versionscodes

Vor Build und Signierung reserviert die Preflight-CLI einen annotierten Tag `routely-version-code/<code>`. Dessen begrenzter JSON-Inhalt bindet Repository, Code, Versionsname, exakte Commit-SHA sowie `GITHUB_RUN_ID` und `GITHUB_RUN_ATTEMPT`. Das Tagobjekt allein ist keine Reservierung; erst Create-ref beansprucht den Code atomar. Die Pipeline aktualisiert oder löscht diesen Claim niemals. Reserve und Publish müssen ihren exakt passenden Claim derselben Workflowausführung nachweisen und weiterhin über allen anderen verbrauchten beziehungsweise veröffentlichten Codes liegen.

Auch fehlgeschlagene Builds und Signierungen verbrauchen Code und Versionsname. Ein erneuter Lauf braucht einen neuen Namen und höheren Code; der Claim bleibt erhalten. Ein später gelöschter oder wieder zum Draft gemachter Release gibt seinen Code nicht frei. Die Codewahl liest alle validierten Claims zusätzlich zu Legacyfloor und veröffentlichten Assets. Bei einem nicht erfassten älteren `v*`-Tag ohne weiterhin belegte veröffentlichte Versionshistorie stoppt sie, statt einen alten Floor zu erraten. Matching-Refs werden als begrenztes vollständiges Präfixarray gelesen; Releasehistorie bleibt paginiert und begrenzt. `git describe --match 'v*'` nimmt keine Claimtags in den Changelog auf.

Claims müssen dauerhaft erhalten bleiben. Create-only in diesem Helfer ist kein zusätzlich eingerichtetes GitHub-Ruleset gegen Administratorlöschung. Werden gezielt Claim und sämtliche weiteren Versionsnachweise entfernt, kann die gelöschte Historie nicht rekonstruiert werden. Die R4-Korrektur schützt Release-Rücknahme bei erhaltenem Claim und stoppt bei fehlendem eigenen Claim vor weiterer Publikation.

## Fehler nach der Reservierung

Ein Netzwerk-/Upload-/Prüffehler kann einen bereits reservierten Tag oder einen unveröffentlichten Draft mit Teilassets zurücklassen. Der Workflow überschreibt oder entfernt sie nicht automatisch; ein Rerun mit derselben Version wird abgewiesen. Den fehlgeschlagenen Lauf und den Draft zuerst prüfen. Danach eine neue Version mit höherem Code wählen. Der verbrauchte Codeclaim wird auch bei einer nachweislich unveröffentlichten Fehlreservierung nicht bereinigt. Bereits veröffentlichte Tags, Releases und Assets werden durch diesen Ablauf nicht repariert oder ersetzt. Ein Fehler bei der Abschlusskontrolle kann auch nach erfolgreichem `draft:false` auftreten; zuerst den tatsächlichen veröffentlichten Zustand prüfen.

Die neuen Schutzpfade sind mit API-Doubles getestet. Während der historischen Befundkorrektur wurde kein signierter Release ausgelöst. Der inzwischen vorhandene reale Lauf wurde am 03.11.2026 wie unten beschrieben unabhängig geprüft.

## Verifizierte Veröffentlichung v1.9.1

Die offene Frage nach einem realen signierten Release nach R1–R4 ist seit der Prüfung vom 03.11.2026 geschlossen. Der erfolgreiche [Workflowlauf 38074500896](https://github.com/shedowe19/routely/actions/runs/38074500896) vom 10.10.2026 enthält erfolgreiche Schritte für Tests/Build, Signierung, Codeclaim, Reservierung und geprüfte Publikation. Der [veröffentlichte Release v1.9.1](https://github.com/shedowe19/routely/releases/tag/v1.9.1), sein Tag und der annotierte Claim `routely-version-code/18` verweisen auf Commit `9b975497f2124d56e2336861d4213e6dae3d3ed2`. Der Claim nennt denselben Workflowlauf und Versuch 1.

Die tatsächlich heruntergeladene öffentliche APK wurde zusätzlich lokal geprüft:

- `apksigner verify --verbose` bestätigt APK-Signaturen v2 und v3, ein Signer.
- `aapt dump badging` bestätigt Paket `de.traewelling.app`, Versionsname `1.9.1`, Code `18` und compileSdk `36`.
- Dateigröße: `16459643` Bytes. SHA-256: `49cf77a95ee3193f085faa855052b1c1f2b413d0919bb2699c27fd85399ba69e`.
- Größe und SHA-256 stimmen mit `release-version.json` sowie dem von GitHub gelieferten APK-Asset-Digest überein; Version, Tag und Commit der Metadaten passen ebenfalls.

Damit sind Signatur, Manifestidentität, veröffentlichte Assets und Commitbindung dieses Releases belegt. Die Prüfung installiert die APK nicht auf einem Gerät und ist keine neue Veröffentlichung der aktuellen lokalen Änderungen. Administrativer Löschschutz für Tags und Claims bleibt eine Repository-Governance-Entscheidung; der Create-only-Helfer richtet kein Ruleset ein.

## Prüfung vor einem Release

### Vorbereitung v1.10.0 vom 03.11.2026

Der neue Minor-Release bündelt Betreiberanzeige, Sichtbarkeitsauswahl und Meldungsnavigation sowie die gezielten Korrekturen von Stopover-Datenverlust, paralleler Refreshrotation und fehlenden Android-Systemseiten. Der unveränderte lokale Code bestand 949 Android-Unit-Tests, 65 Offlineprüfungen, 16 lesende API-Anfragen, vollständiges Debug-Lint sowie Debug-/Release-Build. Reviews bestätigten den Stand ohne blockierende Befunde; praktische Geräteprüfungen und P12 bleiben getrennt offen.

Für diese Veröffentlichung ist der vorhandene manuelle Signierworkflow mit `version_name=1.10.0` und leerem `version_code` vorgesehen. Remoteprüfung und Codeclaims weisen vor dem Start als Höchstcode 18 aus; der Workflow muss den nächsten gültigen Code selbst reservieren. Exakter Commit, erfolgreiche CI, tatsächliche Publikation und heruntergeladene APK werden vor einer Erfolgsmeldung erneut geprüft. Diese Vorbereitung ist noch kein Veröffentlichungsnachweis.

`.github/workflows/api-compatibility.yml` prüft bei Pushes auf `main`, Pull Requests und manuellem Start Offline-Releaseguard-Tests, Android-Unit-Tests, vollständiges `lintDebug`, Debug-Build und Release-Build einschließlich Release-Lint. Signierungssecrets werden nicht benötigt. Die unsignierte Release-APK steht als `routely-release-unsigned-apk` bereit; Signierung und Veröffentlichung bleiben beim manuellen Workflow.

Der fehlgeschlagene Release-Lauf für `1.7.0` / Code `12` führte zur expliziten Fragment-Abhängigkeit und zusätzlichen Release-Prüfung. Hintergrund und aktuelle Nachweise stehen unter [Build](./build.md) und [Tests](./tests.md).

## Offene Punkte

- Der erste reale signierte Lauf nach R1–R4 ist durch den obigen APK-/Tag-/Claim-/Assetnachweis geprüft. Weitere Releases benötigen jeweils denselben unabhängigen Nachweis.
- Produktentscheidung: Ein Play-Store-Release ist im bestehenden Workflow nicht vorgesehen; die verifizierte Verteilung erfolgt als GitHub-APK. Für einen späteren neuen Play-Upload verlangt die am 03.11.2026 gelesene [offizielle Target-API-Vorgabe](https://developer.android.com/google/play/requirements/target-sdk) seit 31.08.2026 mindestens API 36; das derzeitige `targetSdk = 34` erfüllt dies nicht. Eine Storeeinführung benötigt daher zuerst eine ausdrücklich geplante Targetmigration einschließlich Foreground-Service-/Berechtigungsprüfung sowie AAB-/Signierungs-/Storekonfiguration. `compileSdk = 36` allein ersetzt die Targetmigration nicht. Ein vorhandener Storezugang oder Upload wird nicht behauptet.

## Verwandte Seiten

- [Build](./build.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Tests](./tests.md)
- [Main-Review und Korrekturen](./main-review-2026-10-06.md)
