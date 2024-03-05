package hu.bme.mit.theta.probabilistic.gamesolvers.initializers

import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitilizer

/**
 * Provides an initial upper approximation for computing the probability of reaching a set of target states based on
 * which states are known "untargets", meaning that a target cannot be reached from them in any way.
 */
class UntargetSetUpperInitializer<N, A>(
    val isUntarget: (N) -> Boolean
): SGSolutionInitilizer<N, A> {
    override fun initialLowerBound(n: N): Double = 0.0

    override fun initialUpperBound(n: N): Double =
        if(isUntarget(n)) 0.0 else 1.0


    override fun isKnown(n: N): Boolean = isUntarget(n)
}