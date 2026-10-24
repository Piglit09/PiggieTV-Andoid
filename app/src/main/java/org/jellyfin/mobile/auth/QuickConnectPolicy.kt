package org.jellyfin.mobile.auth

/** Bounded polling policy for one native Jellyfin Quick Connect attempt. */
data class QuickConnectPolicy(
    val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    val totalTimeoutMillis: Long = DEFAULT_TOTAL_TIMEOUT_MILLIS,
    val maxPollAttempts: Int = DEFAULT_MAX_POLL_ATTEMPTS,
    val maxConsecutiveTransientFailures: Int = DEFAULT_MAX_CONSECUTIVE_TRANSIENT_FAILURES,
) {
    init {
        require(pollIntervalMillis > 0) { "Quick Connect poll interval must be positive." }
        require(totalTimeoutMillis > 0) { "Quick Connect timeout must be positive." }
        require(maxPollAttempts > 0) { "Quick Connect poll count must be positive." }
        require(maxConsecutiveTransientFailures > 0) {
            "Quick Connect transient failure budget must be positive."
        }
    }

    internal fun deadlineFrom(startedAtMillis: Long): Long = when {
        startedAtMillis > Long.MAX_VALUE - totalTimeoutMillis -> Long.MAX_VALUE
        else -> startedAtMillis + totalTimeoutMillis
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_TOTAL_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        const val DEFAULT_MAX_POLL_ATTEMPTS = 300
        const val DEFAULT_MAX_CONSECUTIVE_TRANSIENT_FAILURES = 3
    }
}

enum class QuickConnectPhase {
    REQUESTING,
    WAITING_FOR_APPROVAL,
    APPROVED,
    AUTHENTICATING,
    SUCCESS,
    EXPIRED,
    CANCELLED,
    FAILED,
}

data class QuickConnectAttemptSnapshot(
    val phase: QuickConnectPhase,
    val pollAttempts: Int,
    val consecutiveTransientFailures: Int,
    val deadlineMillis: Long,
)

sealed interface QuickConnectPollAction {
    data object Poll : QuickConnectPollAction

    data class Wait(val delayMillis: Long) : QuickConnectPollAction

    data class Stop(val phase: QuickConnectPhase) : QuickConnectPollAction
}

/**
 * Secret-free state controller for one Quick Connect attempt.
 *
 * Network work and sleeping remain with the caller. This controller supplies a deterministic,
 * synchronized bound and guarantees that final authentication can only be claimed once.
 */
class QuickConnectAttemptController(
    private val policy: QuickConnectPolicy = QuickConnectPolicy(),
    startedAtMillis: Long,
) {
    private val lock = Any()
    private val deadlineMillis = policy.deadlineFrom(startedAtMillis)
    private var phase = QuickConnectPhase.REQUESTING
    private var pollAttempts = 0
    private var consecutiveTransientFailures = 0
    private var lastPollAtMillis: Long? = null

    fun markWaitingForApproval(): Boolean = synchronized(lock) {
        if (phase != QuickConnectPhase.REQUESTING) return@synchronized false
        phase = QuickConnectPhase.WAITING_FOR_APPROVAL
        true
    }

    /** Returns whether to poll, wait, or stop without performing any delay or network work. */
    fun nextPoll(nowMillis: Long): QuickConnectPollAction = synchronized(lock) {
        if (phase != QuickConnectPhase.WAITING_FOR_APPROVAL) {
            return@synchronized QuickConnectPollAction.Stop(phase)
        }
        if (deadlineExpired(nowMillis) || pollAttempts >= policy.maxPollAttempts) {
            phase = QuickConnectPhase.EXPIRED
            return@synchronized QuickConnectPollAction.Stop(phase)
        }

        val remainingDelay = lastPollAtMillis?.let { lastPoll ->
            (policy.pollIntervalMillis - elapsedSince(lastPoll, nowMillis)).coerceAtLeast(0L)
        } ?: 0L
        if (remainingDelay > 0L) return@synchronized QuickConnectPollAction.Wait(remainingDelay)

        pollAttempts += 1
        lastPollAtMillis = nowMillis
        QuickConnectPollAction.Poll
    }

    /** Records a successful poll response and moves an approved attempt exactly once. */
    fun recordPollResult(approved: Boolean, nowMillis: Long): Boolean = synchronized(lock) {
        if (phase != QuickConnectPhase.WAITING_FOR_APPROVAL) return@synchronized false
        if (deadlineExpired(nowMillis)) {
            phase = QuickConnectPhase.EXPIRED
            return@synchronized false
        }

        consecutiveTransientFailures = 0
        if (approved) phase = QuickConnectPhase.APPROVED
        true
    }

    /**
     * Returns true while another sequential poll is allowed. Reaching the configured consecutive
     * failure budget ends the attempt as FAILED; reaching time/count bounds ends it as EXPIRED.
     */
    fun recordTransientPollFailure(nowMillis: Long): Boolean = synchronized(lock) {
        if (phase != QuickConnectPhase.WAITING_FOR_APPROVAL) return@synchronized false
        if (deadlineExpired(nowMillis)) {
            phase = QuickConnectPhase.EXPIRED
            return@synchronized false
        }

        consecutiveTransientFailures += 1
        if (consecutiveTransientFailures >= policy.maxConsecutiveTransientFailures) {
            phase = QuickConnectPhase.FAILED
            return@synchronized false
        }
        true
    }

    /** Claims the one allowed final Quick Connect authentication call. */
    fun claimFinalAuthentication(): Boolean = synchronized(lock) {
        if (phase != QuickConnectPhase.APPROVED) return@synchronized false
        phase = QuickConnectPhase.AUTHENTICATING
        true
    }

    fun markSuccess(): Boolean = synchronized(lock) {
        if (phase != QuickConnectPhase.AUTHENTICATING) return@synchronized false
        phase = QuickConnectPhase.SUCCESS
        true
    }

    fun cancel(): Boolean = synchronized(lock) {
        if (phase.isTerminal()) return@synchronized false
        phase = QuickConnectPhase.CANCELLED
        true
    }

    fun fail(): Boolean = synchronized(lock) {
        if (phase.isTerminal()) return@synchronized false
        phase = QuickConnectPhase.FAILED
        true
    }

    fun snapshot(): QuickConnectAttemptSnapshot = synchronized(lock) {
        QuickConnectAttemptSnapshot(
            phase = phase,
            pollAttempts = pollAttempts,
            consecutiveTransientFailures = consecutiveTransientFailures,
            deadlineMillis = deadlineMillis,
        )
    }

    override fun toString(): String = "QuickConnectAttemptController(${snapshot()})"

    private fun deadlineExpired(nowMillis: Long): Boolean = nowMillis >= deadlineMillis

    private fun elapsedSince(earlierMillis: Long, nowMillis: Long): Long = when {
        nowMillis <= earlierMillis -> 0L
        else -> nowMillis - earlierMillis
    }

    private fun QuickConnectPhase.isTerminal(): Boolean = when (this) {
        QuickConnectPhase.SUCCESS,
        QuickConnectPhase.EXPIRED,
        QuickConnectPhase.CANCELLED,
        QuickConnectPhase.FAILED,
        -> true

        else -> false
    }
}
