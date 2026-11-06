package one.tearoom.permissions.ui

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
import one.tearoom.permissions.PermissionAsk
import one.tearoom.permissions.PermissionFlow
import one.tearoom.permissions.PermissionState

/**
 * The flow of [permission] for the screen this is called from: the system dialog through an Activity
 * Result launcher of the composition, a refresh on every resume, and the open reason or settings
 * path kept across a rotation. [onResult] runs once per answer, granted or not, and at once when the
 * feature is started while the permission is already usable, so the feature runs from one place.
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
    var savedAsk by rememberSaveable { mutableStateOf<String?>(null) }
    // The launcher's callback is set before the flow exists; the holder closes the loop.
    val holder = remember { arrayOfNulls<PermissionFlow>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answer ->
        holder[0]?.onAnswer(answer)
    }
    val flow = remember(permission) {
        PermissionFlow(
            permission,
            ActivityPlatform(activity) { launcher.launch(it.toTypedArray()) },
            initialAsk = savedAsk?.let(PermissionAsk::valueOf),
        ) { result(it) }.also { holder[0] = it }
    }
    LaunchedEffect(flow) { flow.state.collect { savedAsk = it.ask?.name } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flow.refresh() }
    return flow
}
