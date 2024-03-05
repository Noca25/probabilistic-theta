package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.StochasticGameSolver
import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.GameRewardFunction
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.ExplicitInitializer

/**
 * Implementation of standard Value Iteration.
 * Assumes all nodes to have at least one available action (absorbing states should have self-loops).
 * Only usable for finite games.
 */
class VISolver<N, A>(
    val tolerance: Double,
    val rewardFunction: GameRewardFunction<N, A>,
    val useGS: Boolean = false,
    val initializer: SGSolutionInitilizer<N, A>,
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