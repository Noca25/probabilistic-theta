package hu.bme.mit.theta.prob.analysis.besttransformer

import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.prob.analysis.P_CONCRETE
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameAction
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameNode
import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.GameRewardFunction
import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.probabilistic.StochasticGameSolver
import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.TargetSetLowerInitializer

class BestTransformerCegarChecker<S : ExprState, A : StmtAction, P : Prec>(
    val abstractor: BestTransformerAbstractor<S, A, P>,
    val refiner: BestTransformerRefiner<S, A, P, *>,
    val gameSolverSupplier: (
        GameRewardFunction<BestTransformerGameNode<S, A>, BestTransformerGameAction<S, A>>,
        SGSolutionInitializer<BestTransformerGameNode<S, A>, BestTransformerGameAction<S, A>>
    ) -> StochasticGameSolver<
            BestTransformerGameNode<S, A>,
            BestTransformerGameAction<S, A>
            >
) {
    data class BestTransformerCheckerResult<S : ExprState, A : StmtAction, P : Prec>(
        val finalPrec: P,
        val finalLowerInitValue: Double,
        val finalUpperInitValue: Double
    )

    fun check(initPrec: P, goal: Goal, threshold: Double): BestTransformerCheckerResult<S, A ,P> {
        var currPrec = initPrec
        val initializer = TargetSetLowerInitializer<BestTransformerGameNode<S, A>, BestTransformerGameAction<S, A>> {
            it is BestTransformerGameNode.AbstractionChoiceNode && it.minReward == 1
        }
        while (true) {
            val abstraction = abstractor.computeAbstraction(currPrec)
            val game = abstraction.game
            val upperAnalysisTask = AnalysisTask(game, { if (it == P_CONCRETE) goal else Goal.MAX })
            val lowerAnalysisTask = AnalysisTask(game, { if (it == P_CONCRETE) goal else Goal.MIN })
            val (lowerValues, lowerStrat) = gameSolverSupplier(abstraction.rewardMin, initializer).solveWithStrategy(lowerAnalysisTask)
            val (upperValues, upperStrat) = gameSolverSupplier(abstraction.rewardMax, initializer).solveWithStrategy(upperAnalysisTask)
            if (upperValues[game.initialNode]!! - lowerValues[game.initialNode]!! < threshold) {
                return BestTransformerCheckerResult(
                    currPrec,
                    lowerValues[game.initialNode]!!,
                    upperValues[game.initialNode]!!
                )
            }
            currPrec = refiner.refine(game, upperValues, lowerValues, upperStrat, lowerStrat, currPrec).newPrec
        }
    }

}