package de.traewelling.app.data.model

enum class StatusVisibility(val apiValue: Int, val title: String, val description: String) {
    PUBLIC(0, "Öffentlich", "Für alle sichtbar und in öffentlichen Feeds aufgeführt."),
    UNLISTED(1, "Nicht gelistet", "Über einen direkten Link sichtbar, ohne öffentlichen Feed-Eintrag."),
    FOLLOWERS(2, "Follower", "Nur für deine Follower sichtbar."),
    PRIVATE(3, "Privat", "Nur für dich sichtbar."),
    AUTHENTICATED(4, "Angemeldete Nutzer", "Nur für bei Träwelling angemeldete Personen sichtbar."),
    TRUSTED(5, "Vertraute Personen", "Nur für von dir als vertraut markierte Personen sichtbar.");

    companion object {
        fun fromApiValue(value: Int?): StatusVisibility? = entries.firstOrNull { it.apiValue == value }
    }
}
