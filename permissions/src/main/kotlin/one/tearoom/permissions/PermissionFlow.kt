package one.tearoom.permissions

import android.os.Bundle
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the flow needs from the device: the grants and rationales, the marks for blocked permissions,
 * the system dialog and the settings page. [ActivityPlatform] is the real one; a test replaces it.
 */
interface PermissionPlatform {
    val sdk: Int
    fun isGranted(permission: String): Boolean

    /** Android would explain the permission: it asked once and was refused. */
    fun showsRationale(permission: String): Boolean

    /** This install saw a request for [stage] answered without a dialog; the mark outlives the process. */
    fun isMarkedBlocked(stage: PermissionKind): Boolean
    fun markBlocked(stage: PermissionKind, blocked: Boolean)

    /** Opens the system dialog (for the background location on Android 11+ its settings page). The answer comes to [PermissionFlow.onAnswer]. */
    fun launch(permissions: List<String>)

    /** Opens the page in Android's settings where [stage] is changed. */
    fun openSettings(stage: PermissionKind)

    /** The option the settings path names, in the phone's language where Android provides it; empty where it has none. */
    fun settingsOption(stage: PermissionKind): String

    /** One of the app's strings, for the fallback of [settingsOption]. */
    fun text(@StringRes id: Int): String
}

/**
 * The flow of one [AppPermission] for one screen: the user taps the feature ([start]), the flow
 * decides what comes next (the reason, the system dialog, the settings path) and reads the answer;
 * [state] is what the surface shows, [onResult] fires once per answer, granted or not, so the
 * feature runs from one place. A reason or settings path that the user closes with "Not now"
 * ([dismiss]) counts as an answer too, so a chain of permissions goes on.
 *
 * Every read asks the system; nothing is cached across starts. The only thing kept is a mark that a
 * request was answered without a dialog, so the next start shows the settings path instead of a
 * dead button; a grant or a new rationale clears it.
 *
 * The first read waits for the first use of [state], so the flow may be created as a property of an
 * activity, before its context is attached. Get one from [AppPermission.request] (activity) or the
 * kit's `rememberPermissionFlow` (Compose), which also call [refresh] on every resume, [paused] on
 * every pause, feed [onAnswer], and keep [save] across a rotation.
 */
class PermissionFlow(
    val permission: AppPermission,
    private val platform: PermissionPlatform,
    initialAsk: PermissionAsk? = null,
    private val onResult: (PermissionState) -> Unit = {},
) {
    /** The steps whose silent answer this process saw itself; a mark from before is tried once more. */
    private val blockedSeen = mutableSetOf<PermissionKind>()

    /** The steps whose last answer was unsure (see [PermissionPolicy.Answer.UNSURE]); a second one in a row blocks. */
    private val unsure = mutableSetOf<PermissionKind>()

    private var restoredAsk: PermissionAsk? = initialAsk

    // Read on first use, not in the constructor: an activity property is created before attachBaseContext.
    private val _state: MutableStateFlow<PermissionState> by lazy { MutableStateFlow(read(ask = restoredAsk, busy = open != null)) }

    /** Everything a permission surface shows. */
    val state: StateFlow<PermissionState> get() = _state

    /** The texts of the step being asked for now. */
    val texts: PermissionTexts get() = permission.textsFor(_state.value.stage)

    private var rationaleBefore = false
    private var markedBefore = false

    /** The activity paused since the request went out: a dialog or page stood in front of it. */
    private var dialogShown = false

    /** The step a dialog or settings page is open for; null while nothing is open. */
    private var open: PermissionKind? = null
    private var inSettings = false

    /** The open reason or settings path came from [start] and has not been answered through [onResult] yet. */
    private var askPending = false

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
        askPending = false
        if (current.usable) finish(current) else launch(current)
    }

    /** Opens Android's settings for the current step; [refresh] on the way back reads what changed. */
    fun openSettings() {
        val current = read()
        askPending = false
        inSettings = true
        open = current.stage
        _state.value = current.copy(ask = null, busy = true)
        platform.openSettings(current.stage)
    }

    /** Closes the reason or the settings path without asking. Counts as an answer when nothing was reported for it yet. */
    fun dismiss() {
        val closed = read().copy(ask = null)
        _state.value = closed
        if (askPending) {
            askPending = false
            onResult(closed)
        }
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
            val answer = PermissionPolicy.answer(usableNow, rationaleBefore, rationaleAfter, dialogShown)
            val blocked = PermissionPolicy.blocks(answer, markedBefore, answered in unsure)
            // A silent answer sets the mark; a dialog that was shown clears a stale one; an unsure one waits for the next.
            if (blocked) platform.markBlocked(answered, true) else if (answer == PermissionPolicy.Answer.DIALOG) platform.markBlocked(answered, false)
            if (blocked) blockedSeen += answered else blockedSeen -= answered
            if (answer == PermissionPolicy.Answer.UNSURE && !blocked) unsure += answered else unsure -= answered
        }
        // Without an open step (the screen was rebuilt while the dialog stood) the answer still counts.
        continueOrFinish(answered, read(busy = false), fromSettings = false)
    }

    /**
     * The option the settings path names for the current step: Android's name in the phone's language,
     * else the app's [PermissionTexts.option] (Android 10 has no name for "Allow all the time").
     */
    fun settingsOption(): String =
        platform.settingsOption(_state.value.stage).ifEmpty { texts.option?.let(platform::text).orEmpty() }

    /**
     * What a rotation must keep: the open reason or settings path, the step a dialog or settings page
     * is open for, and the answers so far. The bindings store it ([SavedFlow.toBundle]) and pass it to [restore].
     */
    fun save(): SavedFlow {
        val steps = permission.steps(platform.sdk)
        return SavedFlow(
            ask = _state.value.ask,
            open = open?.let(steps::indexOf) ?: -1,
            inSettings = inSettings,
            rationaleBefore = rationaleBefore,
            markedBefore = markedBefore,
            askPending = askPending,
            blockedSeen = blockedSeen.map(steps::indexOf),
            unsure = unsure.map(steps::indexOf),
        )
    }

    /**
     * Takes back what [save] kept, in the rebuilt screen. A dialog or settings page that was open
     * stays open: its answer, or the return from the settings, continues where the old screen stopped.
     */
    fun restore(saved: SavedFlow?) {
        saved ?: return
        val steps = permission.steps(platform.sdk)
        open = steps.getOrNull(saved.open)
        inSettings = saved.inSettings && open != null
        rationaleBefore = saved.rationaleBefore
        markedBefore = saved.markedBefore
        askPending = saved.askPending
        // The screen went away while the dialog stood: something stood in front of it.
        dialogShown = open != null
        saved.blockedSeen.mapNotNull(steps::getOrNull).let(blockedSeen::addAll)
        saved.unsure.mapNotNull(steps::getOrNull).let(unsure::addAll)
        restoredAsk = saved.ask
        _state.value = read(ask = restoredAsk, busy = open != null)
    }

    private fun move(current: PermissionState, move: PermissionPolicy.Move) {
        when (move) {
            PermissionPolicy.Move.DONE -> finish(current)
            PermissionPolicy.Move.EXPLAIN -> ask(current, PermissionAsk.REASON)
            PermissionPolicy.Move.LAUNCH -> launch(current)
            PermissionPolicy.Move.SETTINGS -> ask(current, PermissionAsk.SETTINGS)
        }
    }

    private fun ask(current: PermissionState, ask: PermissionAsk) {
        askPending = true
        _state.value = current.copy(ask = ask)
    }

    private fun launch(current: PermissionState) {
        val wanted = current.stage.permissions(platform.sdk)
        rationaleBefore = wanted.any(platform::showsRationale)
        markedBefore = platform.isMarkedBlocked(current.stage)
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
            if (next == PermissionPolicy.Move.SETTINGS) report(_state.value)
            return
        }
        _state.value = if (current.status == PermissionStatus.BLOCKED && !fromSettings) current.copy(ask = PermissionAsk.SETTINGS) else current
        report(_state.value)
    }

    private fun finish(current: PermissionState) {
        _state.value = current.copy(ask = null, busy = false)
        report(_state.value)
    }

    private fun report(state: PermissionState) {
        askPending = false
        onResult(state)
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
        return PermissionState(permission.kind, stage, status, ask, busy, confirmed, partial = stage.partial(granted, sdk))
    }
}

/**
 * What [PermissionFlow.save] keeps across a rotation, as plain values; steps are indexes into the
 * permission's steps. [toBundle] and [from] carry it through the saved state of the bindings.
 */
data class SavedFlow(
    val ask: PermissionAsk?,
    val open: Int,
    val inSettings: Boolean,
    val rationaleBefore: Boolean,
    val markedBefore: Boolean,
    val askPending: Boolean,
    val blockedSeen: List<Int>,
    val unsure: List<Int>,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("ask", ask?.name)
        putInt("open", open)
        putBoolean("settings", inSettings)
        putBoolean("rationale", rationaleBefore)
        putBoolean("marked", markedBefore)
        putBoolean("pending", askPending)
        putIntArray("seen", blockedSeen.toIntArray())
        putIntArray("unsure", unsure.toIntArray())
    }

    companion object {
        fun from(bundle: Bundle?): SavedFlow? = bundle?.let {
            SavedFlow(
                ask = it.getString("ask")?.let(PermissionAsk::valueOf),
                open = it.getInt("open", -1),
                inSettings = it.getBoolean("settings"),
                rationaleBefore = it.getBoolean("rationale"),
                markedBefore = it.getBoolean("marked"),
                askPending = it.getBoolean("pending"),
                blockedSeen = it.getIntArray("seen")?.toList().orEmpty(),
                unsure = it.getIntArray("unsure")?.toList().orEmpty(),
            )
        }
    }
}

/** Everything granted of all steps, as the platform sees it now. */
internal fun AppPermission.granted(platform: PermissionPlatform): Set<String> =
    steps(platform.sdk).flatMap { it.permissions(platform.sdk) }.filter(platform::isGranted).toSet()
