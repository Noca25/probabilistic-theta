package hu.bme.mit.theta.prob.analysis

interface BacktrackableGame<N> {
    fun getPreviousNodes(n: N): Collection<N>
}