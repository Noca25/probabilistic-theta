package hu.bme.mit.theta.probabilistic.gamesolvers.initializers

import hu.bme.mit.theta.probabilistic.ExplicitStochasticGame
import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.probabilistic.StochasticGame
import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.almostSureMaxForMDP
import hu.bme.mit.theta.probabilistic.gamesolvers.almostSureMinForMDP

/**
 * Propagates initial values based on almost sure reachability using graph-based computations.
 */
class MDPAlmostSureTargetInitializer<N, A>(
    val mdp: StochasticGame<N, A>,
    val goal: Goal,
    val isTarget: (N) -> Boolean
) : SGSolutionInitializer<N, A> {

    private val sureAvoiding = arrayListOf<N>()
    private val almostSureReaching = arrayListOf<N>()

    private val _materialized = mdp.materialize()
    private val materialized = _materialized.first
    private val matmap = _materialized.second
    private val backmatmap= matmap.entries.associate { it.value to it.key }
    private fun Collection<ExplicitStochasticGame.Node>.onOriginal() =
        this.map { backmatmap[it]!! }
    private fun isMaterializedTarget(n: ExplicitStochasticGame.Node) =
        isTarget(backmatmap[n]!!)
    private val materTargets = materialized.getAllNodes().filter { isMaterializedTarget(it) }

    private fun computeSureAvoiding() {
        sureAvoiding.clear()
        val canReachTarget = materialized.getAllNodes().filter(::isMaterializedTarget).toMutableSet()
        var lastExtension: Collection<ExplicitStochasticGame.Node> = canReachTarget
        do {
            lastExtension = lastExtension.flatMap { it.predecessors }.minus(canReachTarget)
        } while (canReachTarget.addAll(lastExtension))
        if(goal==Goal.MIN) {
            var lastRemoved = canReachTarget.filter { it.outgoingEdges.any {it.end.support.none { it in canReachTarget } } }
            do {
                canReachTarget.removeAll(lastRemoved.toSet())
                lastRemoved = lastRemoved.flatMap { it.predecessors }
                    .filter { it.outgoingEdges.any {it.end.support.none { it in canReachTarget } } }
            } while (lastRemoved.isNotEmpty())
        }
        val avoiding = materialized.getAllNodes().minus(canReachTarget).toMutableSet()
        sureAvoiding.addAll(avoiding.onOriginal())
    }

    private fun computeAlmostSureReaching() {
        almostSureReaching.clear()
        if(goal == Goal.MAX) {
            almostSureReaching.addAll(almostSureMaxForMDP(materialized, materTargets).onOriginal())
        } else {
            almostSureReaching.addAll(almostSureMinForMDP(materialized, materTargets).onOriginal())
        }
    }

    init {
        computeAlmostSureReaching()
        computeSureAvoiding()
    }
    override fun initialLowerBound(n: N): Double {
        return if(n in almostSureReaching) 1.0 else 0.0
    }

    override fun initialUpperBound(n: N): Double {
        return if (n in sureAvoiding) 0.0 else 1.0
    }

    override fun isKnown(n: N): Boolean {
        return n in almostSureReaching || n in sureAvoiding
    }

}