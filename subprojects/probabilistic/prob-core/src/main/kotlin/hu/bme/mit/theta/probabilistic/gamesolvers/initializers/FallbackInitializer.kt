package hu.bme.mit.theta.probabilistic.gamesolvers.initializers

import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitilizer

class FallbackInitializer<N, A>(
    val lowerBound: Map<N, Double>,
    val upperBound: Map<N, Double>,
    val fallback: SGSolutionInitilizer<N,A>,
    val convergenceThreshold: Double
): SGSolutionInitilizer<N, A> {
    override fun initialLowerBound(n: N): Double {
        return lowerBound[n] ?: fallback.initialLowerBound(n)
    }

    override fun initialUpperBound(n: N): Double {
        return upperBound[n] ?: fallback.initialUpperBound(n)
    }

    override fun isKnown(n: N): Boolean {
        return initialUpperBound(n) - initialUpperBound(n) < convergenceThreshold
    }
}