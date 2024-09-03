package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.RangeSolution
import hu.bme.mit.theta.probabilistic.StochasticGameSolver

class SGBVISolver<N, A>(
    val threshold: Double,
    val msecOptimalityThreshold: Double = 1e-12,
    val useGS: Boolean = false,
): StochasticGameSolver<N, A> {

    fun solveWithRange(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): RangeSolution<N, A> {
        require(analysisTask.discountFactor == 1.0) {
            "Discounted reward computation with BVI is not supported (yet?)"
        }

        val game = analysisTask.game
        val goal = analysisTask.goal
        val rewardFunction = analysisTask.rewardFunction

        // This should result in an exception if the game is infinite
        val allNodes = game.getAllNodes()

        //TODO: if a generic reward func is used, check infinite reward loops before analysis if possible
        // (at least precompute a list of non-zero reward transitions and nodes -> check during deflation)

        val lInit = allNodes.associateWith(initializer::initialLowerBound)
        val uInit = allNodes.associateWith(initializer::initialUpperBound)
        val unknownNodes = game.getAllNodes().filter { uInit[it]!!-lInit[it]!! > threshold }
        var lCurr = lInit
        var uCurr = uInit
        val strategy = initializer.initialStrategy().toMutableMap()
        do {
            lCurr = bellmanStep(game, lCurr, goal, rewardFunction, gaussSeidel = useGS).result
            val uStep =
                bellmanStep(game, uCurr, goal, rewardFunction, gaussSeidel = useGS)
            uCurr = uStep.result
            strategy.putAll(uStep.strategyUpdate!!)
            val deflationResult = deflate(game, uCurr, lCurr, goal, rewardFunction, msecOptimalityThreshold)
            uCurr = deflationResult.result
            strategy.putAll(deflationResult.strategyUpdate!!)
            val maxDiff = unknownNodes.maxOfOrNull { uCurr[it]!! - lCurr[it]!! } ?: 0.0
        } while (maxDiff > threshold)
        return RangeSolution(lCurr, uCurr, strategy)
    }

    override fun solve(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Map<N, Double> {
        val (l, u) = solveWithRange(analysisTask, initializer)
        return l.keys.associateWith { (u[it]!!+l[it]!!)/2 }
    }

    override fun solveWithStrategy(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Pair<Map<N, Double>, Map<N, A>> {
        val rangeSolution = solveWithRange(analysisTask, initializer)
        val u = rangeSolution.upper
        val l = rangeSolution.lower
        return l.keys.associateWith { (u[it]!!+l[it]!!)/2 } to rangeSolution.strategy!!
    }
}