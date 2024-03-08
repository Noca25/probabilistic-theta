package hu.bme.mit.theta.probabilistic.gamesolvers.initializers

import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer

class ExplicitInitializer<N, A>(
    val lowerBound: Map<N, Double>,
    val upperBound: Map<N, Double>,
    val defaultLower: Double,
    val defaultUpper: Double,
    val convergenceThreshold: Double
): SGSolutionInitializer<N, A> {
    override fun initialLowerBound(n: N): Double =
        lowerBound[n] ?: defaultLower

    override fun initialUpperBound(n: N): Double =
        upperBound[n] ?: defaultUpper

    override fun isKnown(n: N): Boolean =
        initialUpperBound(n) - initialUpperBound(n) <= convergenceThreshold
}