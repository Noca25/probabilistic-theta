package hu.bme.mit.theta.prob.analysis.direct

import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expl.ExplStmtTransFunc
import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.prob.analysis.BasicStmtAction
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.GameRewardFunction
import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.probabilistic.StochasticGameSolver
import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer
import hu.bme.mit.theta.solver.Solver

private typealias SimpleCheckerNode = DirectCheckerNode<ExplState, BasicStmtAction>
class SimpleCommandsDirectChecker(
    val solver: Solver,
    val verboseLogging: Boolean = false,
    val useQualitativePreprocessing: Boolean = false
) {

    fun check(
        commands: Collection<ProbabilisticCommand<BasicStmtAction>>,
        initValuation: Valuation,
        invar: Expr<BoolType>,
        goal: Goal,
        solverSupplier: (
            rewardFunction:
            GameRewardFunction<SimpleCheckerNode, FiniteDistribution<SimpleCheckerNode>>,
            initializer:
            SGSolutionInitializer<SimpleCheckerNode, FiniteDistribution<SimpleCheckerNode>>
        ) -> StochasticGameSolver<SimpleCheckerNode, FiniteDistribution<SimpleCheckerNode>>
    ): Double {
        val fullPrec = ExplPrec.of(initValuation.decls.filterIsInstance<VarDecl<*>>())
        val initState = ExplState.of(initValuation)

        val transFunc = ExplStmtTransFunc.create(solver, 0)

        val directChecker = DirectChecker<ExplState, BasicStmtAction>(
            {commands},
            this::isEnabled,
            { s -> invar.eval(s) == BoolExprs.False() },
            initState,
            transFunc,
            fullPrec,
            solverSupplier,
            useQualitativePreprocessing,
            verboseLogging
        )

        return directChecker.check(goal)
    }

    private fun isEnabled(state: ExplState, cmd: ProbabilisticCommand<BasicStmtAction>): Boolean {
        return cmd.guard.eval(state) == BoolExprs.True()
    }
}