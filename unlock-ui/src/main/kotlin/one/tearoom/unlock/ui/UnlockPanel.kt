package one.tearoom.unlock.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import one.tearoom.unlock.AppUnlock
import one.tearoom.unlock.UnlockMessage
import one.tearoom.unlock.UnlockState
import one.tearoom.unlock.textRes

/**
 * The purchase controls of the unlock flow, for a place inside the app's own surface (a settings card,
 * a page); [UnlockSheet] wraps them in a bottom sheet. Owned: the thanks line. Otherwise the buy button
 * with the price, the app's [actions] under it, Play's feedback (a pending payment, why there is no
 * price, the last purchase or restore), then "Try again" while there is no price and "Restore purchase".
 *
 * The button keeps its place while the price loads or is missing, so the sheet does not jump; the
 * reason stands under it. Texts come from [AppUnlock.texts], colours and type from `MaterialTheme`.
 *
 * @param intro the app's own words above the purchase: what the unlock includes, a note.
 * @param actions buttons that belong to the purchase, under the buy button (e.g. "Continue without").
 */
@Composable
fun UnlockPanel(
    unlock: AppUnlock,
    modifier: Modifier = Modifier,
    style: UnlockStyle = UnlockStyle(),
    intro: @Composable ColumnScope.(UnlockState) -> Unit = {},
    actions: @Composable ColumnScope.(UnlockState) -> Unit = {},
) {
    val context = LocalContext.current
    val state by unlock.state(context).collectAsState()
    val texts = unlock.texts
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier, verticalArrangement = Arrangement.spacedBy(style.groupGap)) {
        intro(state)

        if (state.unlocked) {
            Text(stringResource(texts.unlocked), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        // The purchase: the buy button and the app's own actions next to it.
        Column(verticalArrangement = Arrangement.spacedBy(style.gap)) {
            val label = when {
                state.price != null -> stringResource(texts.buy, state.price.orEmpty())
                state.loadingPrice -> stringResource(texts.loadingPrice)
                else -> stringResource(texts.buyWithoutPrice)
            }
            Button(
                onClick = { context.activity()?.let(unlock::buy) },
                enabled = state.canBuy,
                modifier = if (style.fullWidthButton) Modifier.fillMaxWidth() else Modifier,
            ) {
                Text(label, maxLines = 1)
            }
            actions(state)
        }

        // Feedback from Google Play. A pending payment shows once; the message repeats it.
        val notes = listOfNotNull(
            texts.pending.takeIf { state.pending },
            state.priceProblem?.takeIf { !state.loadingPrice }?.textRes(texts),
        )
        val message = state.message?.takeIf { !(it == UnlockMessage.PENDING && state.pending) }
        if (notes.isNotEmpty() || message != null) {
            Column(verticalArrangement = Arrangement.spacedBy(style.gap)) {
                for (note in notes) Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = muted)
                message?.let { Text(stringResource(it.textRes(texts)), style = MaterialTheme.typography.bodyMedium) }
            }
        }

        // One per line, so long translations fit on a narrow phone.
        Column {
            if (state.priceProblem != null && !state.loadingPrice) {
                TextButton(onClick = { unlock.refresh(context) }) { Text(stringResource(texts.retry), maxLines = 1) }
            }
            TextButton(onClick = { unlock.restore(context) }, enabled = !state.busy) {
                Text(stringResource(texts.restore), maxLines = 1)
            }
        }
    }
}

/** The activity behind a Compose context; Play's purchase sheet needs one. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
