package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.*

class SGBVISolver<N, A>(
    val threshold: Double,
    val lowerInitializer: SGSolutionInitializer<N, A>,
    val upperInitilizer: SGSolutionInitializer<N, A>,
    val useGS: Boolean = false,
    val msecOptimalityThreshold: Double = 1e-12,
//    // uses (virtual) merging of MECs instead of deflation if only one goal is present
//    val allowMerging: Boolean = false
): StochasticGameSolver<N, A> {

    fun solveWithRange(analysisTask: AnalysisTask<N, A>): RangeSolution<N> {
        require(analysisTask.discountFactor == 1.0) {
            "Discounted reward computation with BVI is not supported (yet?)"
        }

        val game = analysisTask.game
        val goal = analysisTask.goal


        // This should result in an exception if the game is infinite
        val allNodes = game.getAllNodes()
        val initPlayer = game.getPlayer(game.initialNode)
        val initGoal = goal(initPlayer)
//        if(allowMerging && allNodes.all {
//            goal(game.getPlayer(it)) == initGoal
//        }) {
//            return solveWithMerging(game, initGoal)
//        }

        //TODO: if a generic reward func is used, check infinite reward loops before analysis if possible
        // (at least precompute a list of non-zero reward transitions and nodes -> check during deflation)

        val lInit = game.getAllNodes().associateWith(lowerInitializer::initialLowerBound)
        val uInit = game.getAllNodes().associateWith(upperInitilizer::initialUpperBound)
        var lCurr = lInit
        var uCurr = uInit
        do {
            lCurr = bellmanStep(game, lCurr, goal, gaussSeidel = useGS).result
            uCurr = bellmanStep(game, uCurr, goal, gaussSeidel = useGS).result
            uCurr = deflate(game, uCurr, lCurr, goal, msecOptimalityThreshold)
            val maxDiff = allNodes.maxOfOrNull { uCurr[it]!! - lCurr[it]!! } ?: 0.0
        } while (maxDiff > threshold)
        return RangeSolution(lCurr, uCurr)
    }

//    private fun solveWithMerging(game: StochasticGame<N, A>, initGoal: Goal): RangeSolution<N> {
//        TODO()
//    }

    override fun solve(analysisTask: AnalysisTask<N, A>): Map<N, Double> {
        val (l, u) = solveWithRange(analysisTask)
        return l.keys.associateWith { (u[it]!!+l[it]!!)/2 }
    }
}