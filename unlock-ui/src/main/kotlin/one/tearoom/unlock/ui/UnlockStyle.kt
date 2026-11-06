package one.tearoom.unlock.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing of the purchase surface; the only place in this module with sizes. Colours, type and shapes
 * come from the host app's `MaterialTheme`. An app passes its own tokens here, so the sheet sits in
 * the app's rhythm; the defaults follow Material 3 sheets.
 *
 * @param gap inside a group: the buy button and what belongs to it, the lines of feedback.
 * @param groupGap between groups: the title, the app's intro, the purchase, the feedback, the links.
 * @param fullWidthButton the buy button spans the width (sheets); false sits it at the start (cards).
 */
@Immutable
data class UnlockStyle(
    val horizontalPadding: Dp = 24.dp,
    val bottomPadding: Dp = 24.dp,
    val gap: Dp = 8.dp,
    val groupGap: Dp = 12.dp,
    val fullWidthButton: Boolean = true,
)
