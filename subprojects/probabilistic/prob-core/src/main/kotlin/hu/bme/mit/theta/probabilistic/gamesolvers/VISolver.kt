package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.StochasticGameSolver

/**
 * Implementation of standard Value Iteration.
 * Assumes all nodes to have at least one available action (absorbing states should have self-loops).
 * Only usable for finite games.
 */
class VISolver<N, A>(
    val tolerance: Double,
    val useGS: Boolean = false,
): StochasticGameSolver<N, A> {

    override fun solve(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Map<N, Double> {
        return solveWithStrategy(analysisTask, initializer).first
    }

    override fun solveWithStrategy(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Pair<Map<N, Double>, Map<N, A>> {
        val game = analysisTask.game
        val goal = analysisTask.goal
        val rewardFunction = analysisTask.rewardFunction

        val allNodes = game.getAllNodes() // This should result in an exception if the game is infinite
        var curr = allNodes.associateWith { initializer.initialLowerBound(it) }
        val unknownNodes = allNodes.filterNot(initializer::isKnown)
        val strategy = initializer.initialStrategy().toMutableMap()
        do {
            val stepResult =
                bellmanStep(game, curr, goal, rewardFunction, analysisTask.discountFactor, useGS, unknownNodes)
            val maxChange = stepResult.maxChange
            strategy.putAll(stepResult.strategyUpdate!!)
            curr = stepResult.result
        } while (maxChange > tolerance)
        return curr to strategy
    }
}