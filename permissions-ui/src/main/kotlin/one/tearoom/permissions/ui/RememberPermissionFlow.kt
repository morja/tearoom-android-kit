package one.tearoom.permissions.ui

import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import one.tearoom.permissions.ActivityPlatform
import one.tearoom.permissions.AppPermission
import one.tearoom.permissions.PermissionFlow
import one.tearoom.permissions.PermissionState
import one.tearoom.permissions.SavedFlow

/**
 * The flow of [permission] for the screen this is called from: the system dialog through an Activity
 * Result launcher of the composition, a refresh on every resume, the pause that tells a dialog from
 * a silent answer, and everything open kept across a rotation: the reason or settings path, a dialog
 * whose answer continues the background step, the return from the settings. [onResult] runs once per
 * answer, granted or not, when the reason or the settings path is closed with "Not now", and at once
 * when the feature is started while the permission is already usable, so the feature runs from one place.
 *
 * ```kotlin
 * val location = rememberPermissionFlow(DeviceLocation) { if (it.usable) showDevice() }
 * Button(onClick = location::start) { Text(stringResource(R.string.use_location)) }
 * PermissionSheet(location)
 * ```
 */
@Composable
fun rememberPermissionFlow(permission: AppPermission, onResult: (PermissionState) -> Unit = {}): PermissionFlow {
    val activity = LocalActivity.current ?: error("rememberPermissionFlow needs an activity")
    val result by rememberUpdatedState(onResult)
    // The open sheet, the step a dialog or the settings page is open for and the answers so far (REV-4 finding 2).
    var saved by rememberSaveable { mutableStateOf<Bundle?>(null) }
    // The launcher's callback is set before the flow exists; the holder closes the loop.
    val holder = remember { arrayOfNulls<PermissionFlow>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answer ->
        holder[0]?.onAnswer(answer)
    }
    val flow = remember(permission) {
        PermissionFlow(permission, ActivityPlatform(activity) { launcher.launch(it.toTypedArray()) }) { result(it) }
            .also { it.restore(SavedFlow.from(saved)) }
            .also { holder[0] = it }
    }
    // Every change of the flow shows in its state (an open dialog is busy), so saving on each state keeps it current.
    LaunchedEffect(flow) { flow.state.collect { saved = flow.save().toBundle() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flow.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { flow.paused() }
    return flow
}
