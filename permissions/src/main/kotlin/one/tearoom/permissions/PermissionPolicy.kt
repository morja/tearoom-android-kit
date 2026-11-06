package one.tearoom.permissions

/**
 * The rules of the flow as pure functions over what the system says, so every branch has a JVM test
 * (AGENTS.md rule 6). [PermissionFlow] reads the system and applies them.
 */
object PermissionPolicy {
    /** A request that Android answered faster than this showed no dialog: the permission is blocked. */
    const val SILENT_ANSWER_MILLIS = 1000L

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
     * Android answered a request without showing anything, so the permission is blocked. After the
     * second refusal Android stops explaining ([rationaleAfter] false) although it did before
     * ([rationaleBefore] true). In a later process, or after "Don't allow" in the settings, nothing
     * was ever explained: then the silent answer shows in its speed, under a second, where a person
     * needs longer to read a dialog (Weatherfold QA on the emulator, 5 November 2026: 400 ms was too
     * short for a slow phone).
     */
    fun answeredWithoutDialog(usableNow: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, elapsedMillis: Long): Boolean =
        !usableNow && !rationaleAfter && (rationaleBefore || elapsedMillis < SILENT_ANSWER_MILLIS)

    /**
     * What a tap on the feature does for [status]. A partial grant that is enough is done: the app
     * never asks for precise in a loop. A partial grant that is not enough is asked for once more;
     * Android shows its upgrade dialog or answers at once, and the latter marks it blocked.
     */
    fun onStart(status: PermissionStatus, partialUsable: Boolean, explain: Explain): Move = when (status) {
        PermissionStatus.GRANTED, PermissionStatus.NOT_NEEDED -> Move.DONE
        PermissionStatus.PARTIAL -> if (partialUsable) Move.DONE else Move.LAUNCH
        PermissionStatus.NOT_ASKED, PermissionStatus.DENIED -> if (explain == Explain.SHEET) Move.EXPLAIN else Move.LAUNCH
        PermissionStatus.BLOCKED -> Move.SETTINGS
    }
}
