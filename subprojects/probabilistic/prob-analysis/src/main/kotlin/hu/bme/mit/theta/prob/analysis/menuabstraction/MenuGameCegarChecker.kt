package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.GameRewardFunction
import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.probabilistic.StochasticGameSolver

class MenuGameCegarChecker<S : ExprState, A : StmtAction, P : Prec>(
    val abstractor: MenuGameAbstractor<S, A, P>,
    val refiner: MenuGameRefiner<S, A, P>,
    val gameSolverSupplier: (
        GameRewardFunction<MenuGameAbstractor.MenuGameNode<S, A>, MenuGameAbstractor.MenuGameAction<S, A>>
    ) -> StochasticGameSolver<
            MenuGameAbstractor.MenuGameNode<S, A>,
            MenuGameAbstractor.MenuGameAction<S, A>
            >
) {
    data class MenuGameCheckerResult<S : ExprState, A : StmtAction, P : Prec>(
        val finalPrec: P,
        val finalLowerInitValue: Double,
        val finalUpperInitValue: Double
    )

    fun check(initPrec: P, goal: Goal, threshold: Double): MenuGameCheckerResult<S, A ,P> {
        var currPrec = initPrec
        while (true) {
            val abstraction = abstractor.computeAbstraction(currPrec)
            val game = abstraction.game
            val upperAnalysisTask = AnalysisTask(game, { if (it == P_CONCRETE) goal else Goal.MAX })
            val lowerAnalysisTask = AnalysisTask(game, { if (it == P_CONCRETE) goal else Goal.MIN })
            val lowerValues = gameSolverSupplier(abstraction.rewardMin).solve(lowerAnalysisTask)
            val upperValues = gameSolverSupplier(abstraction.rewardMax).solve(upperAnalysisTask)
            if (upperValues[game.initialNode]!! - lowerValues[game.initialNode]!! < threshold) {
                return MenuGameCheckerResult(
                    currPrec,
                    lowerValues[game.initialNode]!!,
                    upperValues[game.initialNode]!!
                )
            }
            currPrec = refiner.refine(game, upperValues, lowerValues, currPrec).newPrec
        }
    }

}