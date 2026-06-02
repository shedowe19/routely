# Modul: Auth - OAuth2 PKCE Flow

## Zweck

Detaillierte Dokumentation des OAuth2-Authentifizierungsablaufs mit PKCE (Proof Key for Code Exchange).

## Kontext

Die App unterstützt OAuth2 mit PKCE für sichere Autorisierung. Der Ablauf ist in `OAuthHelper.kt` implementiert.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/util/OAuthHelper.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/AuthViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/data/api/OAuthApiService.kt`

## PKCE-Ablauf

### 1. Code Verifier generieren

```kotlin
fun generateCodeVerifier(): String {
    val bytes = ByteArray(64)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
```

### 2. Code Challenge ableiten (S256)

```kotlin
fun generateCodeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
```

### 3. Authorization URL bauen

```kotlin
fun buildAuthorizationUrl(serverUrl, clientId, redirectUri, scopes, codeChallenge, state): String {
    // Fügt code_challenge und code_challenge_method=S256 hinzu
}
```

### 4. Token Exchange

Nach Authorization-Code-Erhalt:

```kotlin
POST /oauth/token
grant_type=authorization_code
code_challenge=S256
code_verifier=<original>
```

## PreferencesManager Keys für Auth

| Key             | Typ    | Beschreibung          |
| --------------- | ------ | --------------------- |
| `server_url`    | String | Träwelling-Server URL |
| `access_token`  | String | Bearer Token          |
| `refresh_token` | String | Refresh Token         |
| `client_id`     | String | OAuth Client ID       |
| `client_secret` | String | OAuth Client Secret   |

## Offene Fragen

- TODO: PKCE wird bei allen Login-Typen verwendet?
- TODO: Refresh-Token-Flow dokumentieren

## Verwandte Seiten

- [Auth](./auth.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
