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
            // Enough of a grant stands on its own; one that is not enough is refused or blocked like a missing one,
            // and PermissionState.partial carries the part that is there.
            partial && stage.partialUsable -> PermissionStatus.PARTIAL
            wanted.any { it in rationale } -> PermissionStatus.DENIED
            blocked -> PermissionStatus.BLOCKED
            partial -> PermissionStatus.PARTIAL
            else -> PermissionStatus.NOT_ASKED
        }
    }

    /** What an answer to a request says about the dialog. */
    enum class Answer {
        /** A dialog was shown and answered: granted, or refused once and Android explains from now on. */
        DIALOG,

        /**
         * No dialog: Android stopped explaining after the second refusal (rationale before, none after),
         * or the activity never paused for the request.
         */
        SILENT,

        /**
         * The activity paused, nothing was granted, and Android explains neither before nor after. Either
         * a first dialog dismissed with back or outside (no flag changes), or a blocked permission whose
         * silent answer flashes the permission controller (Android 14) however long it takes. The two
         * cannot be told apart from one answer (Paperboat QA-2, REV-4 finding 3).
         */
        UNSURE,
    }

    /** Classifies one answer from what Android says before and after it. No clock: a silent answer took 1.4 s on a cold start. */
    fun answer(usableNow: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, dialogShown: Boolean): Answer = when {
        usableNow || rationaleAfter -> Answer.DIALOG
        rationaleBefore || !dialogShown -> Answer.SILENT
        else -> Answer.UNSURE
    }

    /**
     * Whether an [answer] means the permission is blocked. Silent always; unsure when a mark from an
     * earlier process already said so ([markedBefore]) or when the last answer for the step was unsure
     * too ([unsureBefore]): a dismissed dialog twice in a row is rare, a blocked permission answers so
     * every time. A first dismissal never blocks; a block found only on the second tap costs one tap.
     */
    fun blocks(answer: Answer, markedBefore: Boolean, unsureBefore: Boolean): Boolean = when (answer) {
        Answer.DIALOG -> false
        Answer.SILENT -> true
        Answer.UNSURE -> markedBefore || unsureBefore
    }

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
