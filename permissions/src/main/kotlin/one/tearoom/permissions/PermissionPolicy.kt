package one.tearoom.permissions

/**
 * The rules of the flow as pure functions over what the system says, so every branch has a JVM test
 * (AGENTS.md rule 6). [PermissionFlow] reads the system and applies them.
 */
object PermissionPolicy {
    /** What a tap on the feature does. */
    enum class Move {
        /** Usable: run the feature. */
        DONE,

        /** Show the app's reason first. */
        EXPLAIN,

        /** Open the system dialog. */
        LAUNCH,

        /** Show the settings path. */
        SETTINGS,
    }

    /** Whether [kind] alone is enough for its feature, given what is granted. */
    fun usable(kind: PermissionKind, granted: Set<String>, sdk: Int): Boolean {
        val wanted = kind.permissions(sdk)
        return wanted.isEmpty() || wanted.all { it in granted } || (kind.partial(granted, sdk) && kind.partialUsable)
    }

    /** The kind to ask for now: the prerequisite while it is not usable, else [kind] itself. */
    fun stage(kind: PermissionKind, granted: Set<String>, sdk: Int): PermissionKind {
        val first = kind.first(sdk) ?: return kind
        return if (usable(first, granted, sdk)) kind else first
    }

    /**
     * Where [stage] stands. [rationale] holds the permissions Android would explain (it asked once and
     * was refused); [blocked] says this install saw a request answered without a dialog before.
     */
    fun status(stage: PermissionKind, granted: Set<String>, rationale: Set<String>, blocked: Boolean, sdk: Int): PermissionStatus {
        val wanted = stage.permissions(sdk)
        val partial = stage.partial(granted, sdk)
        return when {
            wanted.isEmpty() -> PermissionStatus.NOT_NEEDED
            wanted.all { it in granted } -> PermissionStatus.GRANTED
            // Enough of a grant stands on its own; one that is not enough is refused or blocked like a missing one.
            partial && stage.partialUsable -> PermissionStatus.PARTIAL
            wanted.any { it in rationale } -> PermissionStatus.DENIED
            blocked -> PermissionStatus.BLOCKED
            partial -> PermissionStatus.PARTIAL
            else -> PermissionStatus.NOT_ASKED
        }
    }

    /**
     * A request that Android answered faster than this showed no dialog a person could have read.
     * Weatherfold measured 400 ms as too short on a slow phone (5 November 2026).
     */
    const val SILENT_ANSWER_MILLIS = 1000L

    /**
     * Android answered a request without showing anything, so the permission is blocked. Three signs,
     * any one of them enough, because none holds alone (Paperboat QA-2, 6 November 2026): after the
     * second refusal Android stops explaining ([rationaleAfter] false) although it did before
     * ([rationaleBefore] true); no dialog came at all, the activity never paused ([dialogShown] false);
     * or the answer came within [SILENT_ANSWER_MILLIS]. A fixed permission on Android 14 starts the
     * permission controller for a moment, so the activity may pause for a silent answer, and a cold
     * start can slow that answer past a second; a dialog dismissed with back leaves no flag at all.
     * A silent answer that slips through is tried again on the next tap ([onStart]), so the cost is a tap.
     */
    fun answeredWithoutDialog(usableNow: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, dialogShown: Boolean, elapsedMillis: Long): Boolean =
        !usableNow && !rationaleAfter && (rationaleBefore || !dialogShown || elapsedMillis < SILENT_ANSWER_MILLIS)

    /**
     * What a tap on the feature does for [status]. A partial grant that is enough is done: the app
     * never asks for precise in a loop. A partial grant that is not enough is asked for once more;
     * Android shows its upgrade dialog or answers at once, and the latter marks it blocked.
     *
     * Blocked goes to the settings only when this process saw the silent answer itself
     * ([blockedSeen]). A mark from an earlier process may be stale (Android resets unused apps, a
     * tester clears the flags), so the tap asks Android once more: a dialog proves the mark wrong,
     * a silent answer confirms it and the settings path follows.
     */
    fun onStart(status: PermissionStatus, partialUsable: Boolean, explain: Explain, blockedSeen: Boolean = true): Move = when (status) {
        PermissionStatus.GRANTED, PermissionStatus.NOT_NEEDED -> Move.DONE
        PermissionStatus.PARTIAL -> if (partialUsable) Move.DONE else Move.LAUNCH
        PermissionStatus.NOT_ASKED, PermissionStatus.DENIED -> if (explain == Explain.SHEET) Move.EXPLAIN else Move.LAUNCH
        PermissionStatus.BLOCKED -> if (blockedSeen) Move.SETTINGS else Move.LAUNCH
    }
}
