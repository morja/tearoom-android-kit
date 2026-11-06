package one.tearoom.permissions.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing of the permission surfaces; the only place in this module with sizes. Colours, type and
 * shapes come from the host app's `MaterialTheme`. An app passes its own tokens here; the defaults
 * follow Material 3 sheets.
 *
 * @param gap inside a group: the text and the buttons under it.
 * @param groupGap between groups: the app's intro, the text, the buttons.
 * @param fullWidthButton the main button spans the width (sheets); false sits it at the start (a line on a screen).
 */
@Immutable
data class PermissionStyle(
    val horizontalPadding: Dp = 24.dp,
    val bottomPadding: Dp = 24.dp,
    val gap: Dp = 8.dp,
    val groupGap: Dp = 12.dp,
    val fullWidthButton: Boolean = true,
)
