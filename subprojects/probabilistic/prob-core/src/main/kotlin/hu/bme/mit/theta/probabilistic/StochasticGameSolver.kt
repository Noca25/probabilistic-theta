package hu.bme.mit.theta.probabilistic

import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer

interface StochasticGameSolver<N, A> {
    fun solve(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Map<N, Double>
    fun solveWithStrategy(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Pair<Map<N, Double>, Map<N, A>>
}

class AnalysisTask<N, A>(
    val game: StochasticGame<N, A>,
    val goal: (Int) -> Goal,
    val rewardFunction: GameRewardFunction<N, A>,
    val discountFactor: Double = 1.0
)

data class RangeSolution<N, A>(
    val lower: Map<N, Double>,
    val upper: Map<N, Double>,
    val strategy: Map<N, A>? = null
)

