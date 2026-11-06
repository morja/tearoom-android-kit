package one.tearoom.permissions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the flow needs from the device: the grants and rationales, a clock, the marks for blocked
 * permissions, the system dialog and the settings page. [ActivityPlatform] is the real one; a test
 * replaces it.
 */
interface PermissionPlatform {
    val sdk: Int
    fun isGranted(permission: String): Boolean

    /** Android would explain the permission: it asked once and was refused. */
    fun showsRationale(permission: String): Boolean

    /** A monotonic clock in milliseconds. */
    fun now(): Long

    /** This install saw a request for [stage] answered without a dialog; the mark outlives the process. */
    fun isMarkedBlocked(stage: PermissionKind): Boolean
    fun markBlocked(stage: PermissionKind, blocked: Boolean)

    /** Opens the system dialog (for the background location on Android 11+ its settings page). The answer comes to [PermissionFlow.onAnswer]. */
    fun launch(permissions: List<String>)

    /** Opens the page in Android's settings where [stage] is changed. */
    fun openSettings(stage: PermissionKind)

    /** The option the settings path names, in the phone's language where Android provides it. */
    fun settingsOption(stage: PermissionKind): String
}

/**
 * The flow of one [AppPermission] for one screen: the user taps the feature ([start]), the flow
 * decides what comes next (the reason, the system dialog, the settings path) and reads the answer;
 * [state] is what the surface shows, [onResult] fires once per answer, granted or not, so the
 * feature runs from one place.
 *
 * Every read asks the system; nothing is cached across starts. The only thing kept is a mark that a
 * request was answered without a dialog, so the next start shows the settings path instead of a
 * dead button; a grant or a new rationale clears it.
 *
 * Get one from [AppPermission.request] (activity) or the kit's `rememberPermissionFlow` (Compose),
 * which also call [refresh] on every resume, [paused] on every pause and feed [onAnswer].
 */
class PermissionFlow(
    val permission: AppPermission,
    private val platform: PermissionPlatform,
    initialAsk: PermissionAsk? = null,
    private val onResult: (PermissionState) -> Unit = {},
) {
    /** The steps whose silent answer this process saw itself; a mark from before is tried once more. Before [_state]: read() needs it. */
    private val blockedSeen = mutableSetOf<PermissionKind>()

    private val _state = MutableStateFlow(read(ask = initialAsk, busy = false))

    /** Everything a permission surface shows. */
    val state: StateFlow<PermissionState> = _state

    /** The texts of the step being asked for now. */
    val texts: PermissionTexts get() = permission.textsFor(_state.value.stage)

    private var rationaleBefore = false
    private var askedAt = 0L

    /** The activity paused since the request went out: a dialog or page stood in front of it. */
    private var dialogShown = false

    /** The step a dialog or settings page is open for; null while nothing is open. */
    private var open: PermissionKind? = null
    private var inSettings = false


    /** The user started the feature. Usable: [onResult] runs at once. Otherwise the reason, the dialog or the settings path. */
    fun start() {
        val current = read()
        if (current.busy) return
        move(current, PermissionPolicy.onStart(current.status, current.stage.partialUsable, permission.explain, current.confirmed))
    }

    /** From the reason: on to the system dialog. Also for a control whose line already says why. */
    fun proceed() {
        val current = read()
        if (current.busy) return
        if (current.usable) finish(current) else launch(current)
    }

    /** Opens Android's settings for the current step; [refresh] on the way back reads what changed. */
    fun openSettings() {
        val current = read()
        inSettings = true
        open = current.stage
        _state.value = current.copy(ask = null, busy = true)
        platform.openSettings(current.stage)
    }

    /** Closes the reason or the settings path without asking. */
    fun dismiss() {
        _state.value = read().copy(ask = null)
    }

    /**
     * Reads the real state again: on every resume, so a permission withdrawn in the settings or a
     * grant made there shows at once. Coming back from [openSettings] counts as an answer.
     */
    fun refresh() {
        if (inSettings) {
            inSettings = false
            val answered = open
            open = null
            continueOrFinish(answered, read(busy = false), fromSettings = true)
            return
        }
        // A dialog is open: its answer comes through onAnswer, which reads then.
        if (open != null) return
        val current = read(busy = false)
        _state.value = if (current.usable) current.copy(ask = null) else current
    }

    /** The activity paused: a system dialog or page came in front of it. From the binding, on every pause. */
    fun paused() {
        if (open != null) dialogShown = true
    }

    /** The system's answer to [PermissionPlatform.launch]. */
    fun onAnswer(result: Map<String, Boolean>) {
        val answered = open
        open = null
        if (answered != null) {
            val granted = permission.granted(platform)
            val rationaleAfter = answered.permissions(platform.sdk).any(platform::showsRationale)
            val usableNow = PermissionPolicy.usable(answered, granted, platform.sdk)
            // A silent answer sets the mark; a dialog that was shown, however it ended, clears a stale one.
            val silent = PermissionPolicy.answeredWithoutDialog(usableNow, rationaleBefore, rationaleAfter, dialogShown, platform.now() - askedAt)
            platform.markBlocked(answered, silent)
            if (silent) blockedSeen += answered else blockedSeen -= answered
        }
        // Without an open step (the screen was rebuilt while the dialog stood) the answer still counts.
        continueOrFinish(answered, read(busy = false), fromSettings = false)
    }

    /** The option the settings path names for the current step. */
    fun settingsOption(): String = platform.settingsOption(_state.value.stage)

    private fun move(current: PermissionState, move: PermissionPolicy.Move) {
        when (move) {
            PermissionPolicy.Move.DONE -> finish(current)
            PermissionPolicy.Move.EXPLAIN -> _state.value = current.copy(ask = PermissionAsk.REASON)
            PermissionPolicy.Move.LAUNCH -> launch(current)
            PermissionPolicy.Move.SETTINGS -> _state.value = current.copy(ask = PermissionAsk.SETTINGS)
        }
    }

    private fun launch(current: PermissionState) {
        val wanted = current.stage.permissions(platform.sdk)
        rationaleBefore = wanted.any(platform::showsRationale)
        askedAt = platform.now()
        dialogShown = false
        open = current.stage
        _state.value = current.copy(ask = null, busy = true)
        platform.launch(wanted)
    }

    /**
     * After an answer for [answered]: done, on to the next step, or the state the surface shows. A
     * blocked answer from the dialog opens the settings path; back from the settings the line on the
     * screen is enough, the sheet stays closed.
     */
    private fun continueOrFinish(answered: PermissionKind?, current: PermissionState, fromSettings: Boolean) {
        if (current.usable) {
            finish(current)
            return
        }
        if (answered != null && current.stage != answered) {
            // The first step came through: the next follows from the same tap, with the reason first where the app wants it.
            val next = PermissionPolicy.onStart(current.status, current.stage.partialUsable, permission.explain, current.confirmed)
            move(current, next)
            if (next == PermissionPolicy.Move.SETTINGS) onResult(_state.value)
            return
        }
        _state.value = if (current.status == PermissionStatus.BLOCKED && !fromSettings) current.copy(ask = PermissionAsk.SETTINGS) else current
        onResult(_state.value)
    }

    private fun finish(current: PermissionState) {
        _state.value = current.copy(ask = null, busy = false)
        onResult(_state.value)
    }

    private fun read(ask: PermissionAsk? = _state.value.ask, busy: Boolean = _state.value.busy): PermissionState {
        val sdk = platform.sdk
        val granted = permission.granted(platform)
        val stage = PermissionPolicy.stage(permission.kind, granted, sdk)
        val rationale = stage.permissions(sdk).filter(platform::showsRationale).toSet()
        val marked = platform.isMarkedBlocked(stage)
        val status = PermissionPolicy.status(stage, granted, rationale, marked, sdk)
        // A mark from before a grant in the settings, or before Android asks again, is stale.
        if (marked && status != PermissionStatus.BLOCKED) platform.markBlocked(stage, false)
        val confirmed = status != PermissionStatus.BLOCKED || stage in blockedSeen
        return PermissionState(permission.kind, stage, status, ask, busy, confirmed)
    }
}

/** Everything granted of all steps, as the platform sees it now. */
internal fun AppPermission.granted(platform: PermissionPlatform): Set<String> =
    steps(platform.sdk).flatMap { it.permissions(platform.sdk) }.filter(platform::isGranted).toSet()
