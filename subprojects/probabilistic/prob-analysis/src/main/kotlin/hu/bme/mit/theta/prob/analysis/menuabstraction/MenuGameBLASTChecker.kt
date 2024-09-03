package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.InitFunc
import hu.bme.mit.theta.analysis.PartialOrd
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.prob.analysis.P_CONCRETE
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommandLTS
import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.probabilistic.StochasticGameSolver

class MenuGameBLASTChecker<S : ExprState, A : StmtAction, P : Prec>(
    val lts: ProbabilisticCommandLTS<S, A>,
    val init: InitFunc<S, P>,
    val transFunc: MenuGameTransFunc<S, A, P>,
    val targetExpr: Expr<BoolType>,
    val maySatisfy: (S, Expr<BoolType>) -> Boolean,
    val mustSatisfy: (S, Expr<BoolType>) -> Boolean,
    val ord: PartialOrd<S>,
    val refiner: MenuGameRefiner<S, A, P, *>,
    val extendPrec: P.(P) -> P,
    val gameSolverSupplier: (

    ) -> StochasticGameSolver<
            MenuGameNode<S, A>,
            MenuGameAction<S, A>
            >
) {
    data class BLASTMenuGameCheckerResult<S : ExprState, A : StmtAction, P : Prec>(
        //TODO: some generalization of lastPrec, e.g. a list of all final precs, or a map from states to final support prec
        val finalLowerInitValue: Double,
        val finalUpperInitValue: Double
    )

    fun check(initPrec: P, goal: Goal, threshold: Double): BLASTMenuGameCheckerResult<S, A, P> {
        val game = BLASTMenuGame(
            lts,
            init,
            transFunc,
            targetExpr,
            maySatisfy,
            mustSatisfy,
            initPrec,
            ord,
            extendPrec
        )
        while (true) {
            game.fullyExplore()
            val lowerInitializer = TODO()
            val upperInitializer = TODO()
            val lowerAnalysisTask = AnalysisTask(game, { if (it == P_CONCRETE) goal else Goal.MIN }, MenuGameLowerRewardFunc())
            val upperAnalysisTask = AnalysisTask(game, { if (it == P_CONCRETE) goal else Goal.MAX }, MenuGameUpperRewardFunc())
            val lowerValues = gameSolverSupplier().solveWithStrategy(lowerAnalysisTask, lowerInitializer)
            val upperValues = gameSolverSupplier().solveWithStrategy(upperAnalysisTask, upperInitializer)
            if (upperValues.first[game.initialNode]!! - lowerValues.first[game.initialNode]!! < threshold) {
                return BLASTMenuGameCheckerResult(
                    lowerValues.first[game.initialNode]!!,
                    upperValues.first[game.initialNode]!!
                )
            }
            // As the support precision for a given state might be ambiguous, and the
            val refinementResult = refiner.refine(
                game,
                upperValues.first,
                lowerValues.first,
                upperValues.second,
                lowerValues.second,
                initPrec
            )
            game.refine(refinementResult)
        }
    }

}