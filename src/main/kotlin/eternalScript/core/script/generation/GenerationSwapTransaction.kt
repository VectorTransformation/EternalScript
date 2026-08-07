package eternalScript.core.script.generation

/** One-shot publication and ownership transaction for a generation swap. */
internal class GenerationSwapTransaction(
    private val stateStore: GenerationStateStore,
    private val current: ManagedProjectGeneration,
    private val replacement: ManagedProjectGeneration
) {
    private var state = State.OPEN

    fun publish(canCommit: () -> Boolean): Boolean {
        check(state == State.OPEN) { "A generation swap transaction can only publish once." }
        if (
            !canCommit() ||
            !stateStore.active.compareAndSet(current, replacement)
        ) {
            state = State.REJECTED
            return false
        }
        if (!canCommit() || !replacement.runtime.publish()) {
            stateStore.active.compareAndSet(replacement, current)
            state = State.REJECTED
            return false
        }

        check(stateStore.pendingRetirements.transfer(current)) {
            "The previous script generation already has a retirement owner."
        }
        check(stateStore.pendingCandidates.claim(replacement)) {
            "The published script candidate lost transaction ownership."
        }
        state = State.PUBLISHED
        return true
    }

    fun rollbackPublication() {
        if (state == State.RETIREMENT_CLAIMED || state == State.ROLLED_BACK) return
        stateStore.active.compareAndSet(replacement, current)
        stateStore.pendingRetirements.claim(current)
        state = State.ROLLED_BACK
    }

    fun claimRetirement(): Boolean {
        check(state == State.PUBLISHED) {
            "Retirement can only be claimed after a successful generation publication."
        }
        state = State.RETIREMENT_CLAIMED
        return stateStore.pendingRetirements.claim(current)
    }

    private enum class State {
        OPEN,
        REJECTED,
        PUBLISHED,
        RETIREMENT_CLAIMED,
        ROLLED_BACK
    }
}
