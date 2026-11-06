package one.tearoom.permissions

import androidx.annotation.StringRes

/**
 * The app's string resources for everything the flow of one kind says, in every language the app
 * ships; the library has no text of its own (AGENTS.md rule 1). One value per kind and app.
 *
 * @param reason one sentence in the app's words, what the feature does with the permission; shown
 *   before the system dialog.
 * @param denied after one refusal: names the permission as the reason the feature waits; the control
 *   next to it asks again.
 * @param blocked after the second refusal: names the permission and the way through Android's
 *   settings. `%1$s` is the option to pick there, in the phone's language where Android provides it
 *   (the name of "Allow all the time" for the background location; the permission's group otherwise).
 * @param allow the button under the reason: "Continue", "Allow".
 * @param openSettings the button under [blocked].
 * @param notNow closes the sheet without asking.
 * @param partial one line for a partial grant: what precise, or all photos, would improve. Null when
 *   the kind has no partial grant.
 */
data class PermissionTexts(
    @StringRes val reason: Int,
    @StringRes val denied: Int,
    @StringRes val blocked: Int,
    @StringRes val allow: Int,
    @StringRes val openSettings: Int,
    @StringRes val notNow: Int,
    @StringRes val partial: Int? = null,
)

/** The app's text for a status that needs a word; null while nothing needs saying. */
@StringRes
fun PermissionStatus.textRes(texts: PermissionTexts): Int? = when (this) {
    PermissionStatus.NOT_ASKED -> texts.reason
    PermissionStatus.DENIED -> texts.denied
    PermissionStatus.BLOCKED -> texts.blocked
    PermissionStatus.PARTIAL -> texts.partial
    PermissionStatus.GRANTED, PermissionStatus.NOT_NEEDED -> null
}
