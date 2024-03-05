package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.probabilistic.StochasticGame

interface SGSolutionInitilizer<N, A> {
    fun initialLowerBound(n: N): Double
    fun initialUpperBound(n: N): Double
    fun isKnown(n: N): Boolean
}