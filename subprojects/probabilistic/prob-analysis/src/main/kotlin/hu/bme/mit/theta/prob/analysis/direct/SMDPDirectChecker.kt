package hu.bme.mit.theta.prob.analysis.direct

import hu.bme.mit.theta.analysis.expl.ExplInitFunc
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expl.ExplStmtTransFunc
import hu.bme.mit.theta.core.type.booltype.BoolExprs.True
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.jani.*
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.GameRewardFunction
import hu.bme.mit.theta.probabilistic.StochasticGameSolver
import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer
import hu.bme.mit.theta.solver.Solver

private typealias CheckerNode = DirectChecker.Node<SMDPState<ExplState>>

class SMDPDirectChecker(
    val solver: Solver,
    val verboseLogging: Boolean = false,
    val threshold: Double = 1e-7,
    val useQualitativePreprocessing: Boolean = false
) {
    fun check(
        smdp: SMDP,
        smdpReachabilityTask: SMDPReachabilityTask,
        solverSupplier: (
            threshold: Double,
            rewardFunction:
            GameRewardFunction<CheckerNode, FiniteDistribution<CheckerNode>>,
            initializer:
            SGSolutionInitializer<CheckerNode, FiniteDistribution<CheckerNode>>
        ) -> StochasticGameSolver<CheckerNode, FiniteDistribution<CheckerNode>>
    ): Double {
        val initFunc = SmdpInitFunc<ExplState, ExplPrec>(
            ExplInitFunc.create(solver, smdp.getFullInitExpr()),
            smdp
        )
        val fullPrec = ExplPrec.of(smdp.getAllVars())
        val initStates = initFunc.getInitStates(fullPrec)
        if(initStates.size != 1)
            throw RuntimeException("initial state must be deterministic")

        val smdpLts = SmdpCommandLts<ExplState>(smdp)
        fun commandsWithPrecondition(state: SMDPState<ExplState>) =
            smdpLts.getCommandsFor(state).map { it.withPrecondition(smdpReachabilityTask.constraint) }

        val transFunc = SMDPTransFunc(ExplStmtTransFunc.create(solver, 0))

        val directChecker = DirectChecker<SMDPState<ExplState>, SMDPCommandAction>(
            ::commandsWithPrecondition,
            this::isEnabled,
            { s -> smdpReachabilityTask.targetExpr.eval(s.domainState) == True() },
            initStates.first(),
            transFunc,
            fullPrec,
            solverSupplier,
            useQualitativePreprocessing,
            verboseLogging
        )

        return directChecker.check(smdpReachabilityTask.goal, threshold)
    }

    private fun isEnabled(state: SMDPState<ExplState>, cmd: ProbabilisticCommand<SMDPCommandAction>): Boolean {
        return cmd.guard.eval(state.domainState) == True()
    }
}