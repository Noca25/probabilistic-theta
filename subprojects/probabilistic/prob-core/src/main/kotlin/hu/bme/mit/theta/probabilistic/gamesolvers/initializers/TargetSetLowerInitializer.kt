package hu.bme.mit.theta.probabilistic.gamesolvers.initializers

import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer

/**
 * Provides an initial lower approximation for computing the probability of reaching a set of target states
 */
class TargetSetLowerInitializer<N, A>(
    val isTarget: (N) -> Boolean
): SGSolutionInitializer<N, A>{
    override fun initialLowerBound(n: N): Double = if(isTarget(n)) 1.0 else 0.0

    override fun initialUpperBound(n: N) = 1.0
    override fun isKnown(n: N): Boolean = isTarget(n)
    override fun initialStrategy(): Map<N, A> {
        return mapOf()
    }

}