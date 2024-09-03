package hu.bme.mit.theta.probabilistic.gamesolvers.initializers

import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer

class TargetUntargetCombinedInitializer<N, A>(
    val isTarget: (N) -> Boolean,
    val isUntarget: (N) -> Boolean
): SGSolutionInitializer<N, A> {

    override fun initialLowerBound(n: N): Double =
        if(isTarget(n)) 1.0 else 0.0

    override fun initialUpperBound(n: N): Double =
        if(isUntarget(n)) 0.0 else 1.0

    override fun isKnown(n: N) =
        isTarget(n) || isUntarget(n)

    override fun initialStrategy(): Map<N, A> {
        // We only have information about absorbing states
        return mapOf()
    }
}