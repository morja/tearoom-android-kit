package one.tearoom.unlock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import one.tearoom.unlock.AppUnlock
import one.tearoom.unlock.UnlockState

/**
 * The default purchase surface: a Material 3 bottom sheet with a [title], the app's [intro], and the
 * controls of [UnlockPanel], drawn from the host app's `MaterialTheme`. Show it while the app's own
 * state says so and close it in [onDismiss]; the message Play left is cleared when the sheet goes.
 *
 * An app with another layout passes its own [body] in place of the panel and still runs the same
 * flow through [unlock] (ADR-0004: flows in the kit, surfaces in the apps).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockSheet(
    unlock: AppUnlock,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    style: UnlockStyle = UnlockStyle(),
    intro: @Composable ColumnScope.(UnlockState) -> Unit = {},
    actions: @Composable ColumnScope.(UnlockState) -> Unit = {},
    body: @Composable ColumnScope.() -> Unit = { UnlockPanel(unlock, style = style, intro = intro, actions = actions) },
) {
    val context = LocalContext.current
    DisposableEffect(unlock) { onDispose { unlock.clearMessage(context) } }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            Modifier.padding(horizontal = style.horizontalPadding).padding(bottom = style.bottomPadding),
            verticalArrangement = Arrangement.spacedBy(style.groupGap),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            body()
        }
    }
}
