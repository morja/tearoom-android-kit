package one.tearoom.permissions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import one.tearoom.permissions.PermissionAsk
import one.tearoom.permissions.PermissionFlow
import one.tearoom.permissions.PermissionState

/**
 * The default surface of the permission flow: a Material 3 bottom sheet that shows whenever the flow
 * asks for something ([PermissionState.ask]), drawn from the host app's `MaterialTheme`. Before the
 * system dialog it says the app's reason with "Continue" and "Not now"; for a blocked permission it
 * names the settings path with "Open settings" and "Not now". Put it once on the screen next to the
 * control that starts the feature; it is empty while nothing is asked.
 *
 * An app with a surface of its own reads the same state and calls the same methods (ADR-0004: flows
 * in the kit, surfaces in the apps).
 *
 * @param intro the app's own words above the kit's text, for example what the feature shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    flow: PermissionFlow,
    modifier: Modifier = Modifier,
    style: PermissionStyle = PermissionStyle(),
    intro: @Composable ColumnScope.(PermissionState) -> Unit = {},
) {
    val state by flow.state.collectAsState()
    val ask = state.ask ?: return
    val texts = flow.texts
    val width = if (style.fullWidthButton) Modifier.fillMaxWidth() else Modifier

    ModalBottomSheet(onDismissRequest = flow::dismiss, modifier = modifier) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = style.horizontalPadding)
                .padding(bottom = style.bottomPadding),
            verticalArrangement = Arrangement.spacedBy(style.groupGap),
        ) {
            intro(state)
            when (ask) {
                PermissionAsk.REASON -> {
                    Text(stringResource(texts.reason), style = MaterialTheme.typography.bodyLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(style.gap)) {
                        Button(onClick = flow::proceed, modifier = width) { Text(stringResource(texts.allow), maxLines = 1) }
                        TextButton(onClick = flow::dismiss) { Text(stringResource(texts.notNow), maxLines = 1) }
                    }
                }
                PermissionAsk.SETTINGS -> {
                    Text(stringResource(texts.blocked, flow.settingsOption()), style = MaterialTheme.typography.bodyLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(style.gap)) {
                        Button(onClick = flow::openSettings, modifier = width) { Text(stringResource(texts.openSettings), maxLines = 1) }
                        TextButton(onClick = flow::dismiss) { Text(stringResource(texts.notNow), maxLines = 1) }
                    }
                }
            }
        }
    }
}
