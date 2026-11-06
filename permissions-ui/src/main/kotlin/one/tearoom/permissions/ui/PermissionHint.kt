package one.tearoom.permissions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import one.tearoom.permissions.PermissionFlow
import one.tearoom.permissions.PermissionStatus

/**
 * The persistent line on a screen whose feature is on while its permission is missing (the skill:
 * a foreground service without the notification permission runs invisibly, so the main screen says
 * so). Empty while the permission is usable. Otherwise the text for the state (the reason before the
 * first ask, the refusal after one, the settings path after two, what a full grant would add after
 * a partial one) and the one control that helps: "Allow" opens the system dialog at once, since the
 * line has said why; "Open settings" where only the settings help. A block that only a mark from an
 * earlier process remembers reads like a refusal: "Allow" asks Android once more, and the settings
 * path follows at once when it stands.
 */
@Composable
fun PermissionHint(flow: PermissionFlow, modifier: Modifier = Modifier, style: PermissionStyle = PermissionStyle()) {
    val state by flow.state.collectAsState()
    val texts = flow.texts
    // A block only a mark remembers is offered like a refusal: "Allow" asks Android once more, which answers at once when it stands.
    val blocked = state.status == PermissionStatus.BLOCKED && state.confirmed
    val settingsOnly = blocked || (state.status == PermissionStatus.PARTIAL && state.usable)
    val text = when (state.status) {
        PermissionStatus.NOT_ASKED -> stringResource(texts.reason)
        PermissionStatus.DENIED -> stringResource(texts.denied)
        PermissionStatus.BLOCKED -> if (blocked) stringResource(texts.blocked, flow.settingsOption()) else stringResource(texts.denied)
        PermissionStatus.PARTIAL -> texts.partial?.let { stringResource(it) } ?: return
        PermissionStatus.GRANTED, PermissionStatus.NOT_NEEDED -> return
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(style.gap)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (settingsOnly) {
            TextButton(onClick = flow::openSettings) { Text(stringResource(texts.openSettings), maxLines = 1) }
        } else {
            TextButton(onClick = flow::proceed, enabled = !state.busy) { Text(stringResource(texts.allow), maxLines = 1) }
        }
    }
}
