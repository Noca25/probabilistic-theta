package hu.bme.mit.theta.probabilistic.gamesolvers

interface SGSolutionInitializer<N, A> {
    fun initialLowerBound(n: N): Double
    fun initialUpperBound(n: N): Double
    fun isKnown(n: N): Boolean
    fun initialStrategy(): Map<N, A>
}