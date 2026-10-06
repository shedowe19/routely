# Entwicklung: Deployment

## Zweck

Dokumentiert Prüfung, Signierung und Veröffentlichung einer neuen Android-Version.

## GitHub Actions

`.github/workflows/android.yml` baut Releases manuell per `workflow_dispatch`. Eingaben sind ein neuer `version_name` und optional `version_code`. Ein leeres Codefeld wählt den veröffentlichten Höchstwert + 1; ein expliziter Code muss größer sein. Der historisch belegte Mindeststand ist Code 13 aus dem signierten Lauf für `v1.8.7`.

Der Workflow installiert Android-SDK 36 und Build Tools 35.0.0, führt Unit-Tests vor dem Release-Build aus und signiert erst danach mit GitHub Secrets. APK und `release-version.json` werden als Workflow-Artifact `release-apk` hochgeladen. Die APK heißt `routely-v<version_name>.apk`. Zulässige Eingaben und Toolchain stehen unter [Build](./build.md).

## Unveränderliche neue Veröffentlichung

`.github/scripts/release_guard.py` verwendet ausschließlich Python-Standardbibliotheken. Beide Workflows führen seine Offline-Tests aus. Die globale Concurrency-Gruppe des manuellen Releaseworkflows serialisiert alle Branches und bricht einen laufenden Release nicht ab. Der Checkout ist ausdrücklich `${{ github.sha }}`; der Helfer vergleicht auch die tatsächliche lokale HEAD-SHA.

Vor dem Build werden vorhandene Tags oder Releases mit derselben Bezeichnung abgewiesen. `.github/release-version-floor.json` bindet die 35 bekannten historischen Releases an Tag und Release-ID und bewahrt deren belegten Höchstcode. Alle weiteren veröffentlichten Releases müssen gültige Metadaten enthalten. Die begrenzte, paginierte Historienprüfung wählt einen strikt höheren eindeutigen Code; unbekannte, widersprüchliche oder unvollständige Historie führt zum Abbruch. Authentifizierte API- und Uploadanfragen bleiben bei den GitHub-HTTPS-Origins; eine Metadaten-CDN-Weiterleitung erhält keine Authentifizierung.

Nach Build und Signierung prüft `aapt` Paket-ID, Versionsname und Code der tatsächlichen APK. Metadaten speichern exakte Commit-SHA, Tag, APK-Namen, Größe und SHA-256. Unmittelbar vor der Reservierung werden Version und Historie erneut geprüft. Eine atomare Create-ref-Anfrage reserviert den neuen Tag an der gebauten SHA, eine Create-release-Anfrage den neuen Draft. Keine Update-/Force-/Delete-Operation ersetzt eine bestehende Version.

Die Publikation nutzt ausschließlich die reservierte Release-ID. Zwei Create-only Asset-POSTs laden APK und Metadaten hoch; ein schon vorhandenes Asset führt zum Abbruch. Der Helfer prüft Draftidentität, aufgelöste Tag-SHA, vollständigen Metadateninhalt und den von GitHub gelieferten APK-Digest. Fehlender oder falscher Digest blockiert die Veröffentlichung. Der Versionscode wird vor den Uploads und vor dem abschließenden `draft:false` erneut geprüft. Danach werden Release, Tag und Assets nochmals gelesen und verglichen.

## Grenze nach administrativer Rücknahme

R4 des [Nachreviews](./main-review-2026-10-06.md) bestätigt eine zusätzliche Betriebsgrenze: Nur der Legacy-Höchstcode 13 ist unabhängig von der Releasehistorie dauerhaft gespeichert. Künftige Codes stammen aus derzeit veröffentlichten Release-Metadaten; Drafts werden übersprungen. Wird ein schon veröffentlichter neuer Release mit Code 18 gelöscht oder wieder zum Draft, kann ein neuer Versionsname automatisch Code 14 statt mindestens 19 erhalten, selbst wenn der alte Tag bleibt und die APK bereits installiert ist. Drei Offlineproben bestätigen zwei solche Regressionen und den normalen erhaltenen Verlauf 18→19.

TODO: Künftig ausgegebene Höchstcodes dauerhaft bewahren oder unvollständige zurückgezogene Historie blockieren. Bis zur Korrektur veröffentlichte Metadaten erhalten; bei erforderlicher Rücknahme vor weiterer Veröffentlichung den dauerhaften Floor ausdrücklich auf den bereits ausgegebenen Höchstcode erhöhen. Die automatische Pipeline entfernt keine veröffentlichten Releases. Dieser Randfall benötigt eine administrative Historienänderung und ist kein belegtes Rennen innerhalb der serialisierten regulären Veröffentlichung.

## Fehler nach der Reservierung

Ein Netzwerk-/Upload-/Prüffehler kann einen bereits reservierten Tag oder einen unveröffentlichten Draft mit Teilassets zurücklassen. Der Workflow überschreibt oder entfernt sie nicht automatisch; ein Rerun mit derselben Version wird abgewiesen. Den fehlgeschlagenen Lauf und den Draft zuerst prüfen. Danach entweder eine neue Version wählen oder ausschließlich die nachweislich unveröffentlichte Fehlreservierung manuell bereinigen. Bereits veröffentlichte Tags, Releases und Assets werden durch diesen Ablauf nicht repariert oder ersetzt. Ein Fehler bei der Abschlusskontrolle kann auch nach erfolgreichem `draft:false` auftreten; zuerst den tatsächlichen veröffentlichten Zustand prüfen.

Die neuen Schutzpfade sind mit API-Doubles getestet. In der Befundkorrektur wurde kein signierter Release ausgelöst; der erste reale manuelle Lauf bleibt ein eigener Systemnachweis.

## Prüfung vor einem Release

`.github/workflows/api-compatibility.yml` prüft bei Pushes auf `main`, Pull Requests und manuellem Start Offline-Releaseguard-Tests, Android-Unit-Tests, vollständiges `lintDebug`, Debug-Build und Release-Build einschließlich Release-Lint. Signierungssecrets werden nicht benötigt. Die unsignierte Release-APK steht als `routely-release-unsigned-apk` bereit; Signierung und Veröffentlichung bleiben beim manuellen Workflow.

Der fehlgeschlagene Release-Lauf für `1.7.0` / Code `12` führte zur expliziten Fragment-Abhängigkeit und zusätzlichen Release-Prüfung. Hintergrund und aktuelle Nachweise stehen unter [Build](./build.md) und [Tests](./tests.md).

## Offene Punkte

- TODO: Den ersten realen signierten Lauf nach R1–R3 prüfen; Offline-Tests und unsignierte Builds belegen keine erfolgreiche Signierung oder reale GitHub-Publikation.
- TODO: Play-Store-Release-Prozess dokumentieren, falls ein Store-Deployment vorgesehen ist.

## Verwandte Seiten

- [Build](./build.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Tests](./tests.md)
- [Main-Review und Korrekturen](./main-review-2026-10-06.md)
