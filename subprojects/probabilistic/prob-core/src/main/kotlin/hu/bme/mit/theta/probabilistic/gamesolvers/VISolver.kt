package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.GameRewardFunction
import hu.bme.mit.theta.probabilistic.StochasticGameSolver

/**
 * Implementation of standard Value Iteration.
 * Assumes all nodes to have at least one available action (absorbing states should have self-loops).
 * Only usable for finite games.
 */
class VISolver<N, A>(
    val rewardFunction: GameRewardFunction<N, A>,
    val initializer: SGSolutionInitializer<N, A>,
    val tolerance: Double,
    val useGS: Boolean = false,
): StochasticGameSolver<N, A> {
    override fun solve(analysisTask: AnalysisTask<N, A>): Map<N, Double> {
        val game = analysisTask.game
        val goal = analysisTask.goal


        val allNodes = game.getAllNodes() // This should result in an exception if the game is infinite
        var curr = allNodes.associateWith { initializer.initialLowerBound(it) }
        do {
            val stepResult =
                bellmanStep(game, curr, goal, rewardFunction, analysisTask.discountFactor, useGS)
            val maxChange = stepResult.maxChange
            curr = stepResult.result
        } while (maxChange > tolerance)
        return curr
    }
}