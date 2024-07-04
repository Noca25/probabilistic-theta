package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.StochasticGameSolver
import hu.bme.mit.theta.probabilistic.AnalysisTask

class TopologicalVISolver<N, A>:
StochasticGameSolver<N, A>{
    override fun solve(analysisTask: AnalysisTask<N, A>): Map<N, Double> {
        TODO("Not yet implemented")
    }

    override fun solveWithStrategy(analysisTask: AnalysisTask<N, A>): Pair<Map<N, Double>, Map<N, A>> {
        TODO("Not yet implemented")
    }
}