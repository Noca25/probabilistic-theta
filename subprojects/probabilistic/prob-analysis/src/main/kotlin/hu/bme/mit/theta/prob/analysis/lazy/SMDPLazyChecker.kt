package hu.bme.mit.theta.prob.analysis.lazy

import hu.bme.mit.theta.analysis.expl.ExplInitFunc
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expl.ExplStmtTransFunc
import hu.bme.mit.theta.analysis.pred.PredAbstractors
import hu.bme.mit.theta.analysis.pred.PredInitFunc
import hu.bme.mit.theta.analysis.pred.PredPrec
import hu.bme.mit.theta.analysis.pred.PredState
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.jani.*
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.Algorithm.*
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.StochasticGame
import hu.bme.mit.theta.probabilistic.gamesolvers.diffBasedSelection
import hu.bme.mit.theta.probabilistic.gamesolvers.randomSelection
import hu.bme.mit.theta.probabilistic.gamesolvers.roundRobinSelection
import hu.bme.mit.theta.probabilistic.gamesolvers.weightedRandomSelection
import hu.bme.mit.theta.solver.ItpSolver
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.UCSolver

typealias SMDPLazyCheckerGame<S> = StochasticGame<
        ProbLazyChecker<SMDPState<ExplState>, SMDPState<S>, SMDPCommandAction>.Node,
        ProbLazyChecker<SMDPState<ExplState>, SMDPState<S>, SMDPCommandAction>.Edge
        >

class SMDPLazyChecker(
    val smtSolver: Solver,
    val itpSolver: ItpSolver,
    val ucSolver: UCSolver,
    val algorithm: Algorithm,
    val verboseLogging: Boolean = false,
    val brtdpStrategy: BRTDPStrategy = BRTDPStrategy.DIFF_BASED,
    val useMayStandard: Boolean = true,
    val useMustStandard: Boolean = false,
    val useMayTarget: Boolean = true,
    val useMustTarget: Boolean = false,
    val threshold: Double = 1e-7,
    val useSeq: Boolean = false,
    val useGameRefinement: Boolean = false,
    val useQualitativePreprocessing: Boolean = false,
    val mergeSameSCNodes: Boolean = true
) {

    enum class BRTDPStrategy {
        DIFF_BASED,
        RANDOM,
        ROUND_ROBIN,
        WEIGHTED_RANDOM;
    }

    enum class Algorithm {
        BRTDP, VI, BVI
    }

    fun checkExpl(
        smdp: SMDP,
        smdpReachabilityTask: SMDPReachabilityTask
    ): Double {

        fun targetCommands(locs: List<SMDP.Location>) = listOf(
            ProbabilisticCommand(
                smdpReachabilityTask.targetExpr, FiniteDistribution.dirac(
                    SMDPCommandAction.skipAt(locs, smdp)
                )
            )
        )

        val domainTransFunc = ExplStmtTransFunc.create(smtSolver, 0)
        val vars = smdp.getAllVars()
        val fullPrec = ExplPrec.of(vars)

        val initFunc = SmdpInitFunc(ExplInitFunc.create(smtSolver, smdp.getFullInitExpr()), smdp)
        val smdpLts = SmdpCommandLts<ExplState>(smdp)

        val topInit = initFunc.getInitStates(ExplPrec.empty())
        val fullInit = initFunc.getInitStates(fullPrec)

        // the task is given as the computation of P_opt(constraint U target_expr)
        // we add checking that constraint is still true to every normal command,
        // so that we hit a deadlock (w.r.t. normal commands) if it is not
        // the resulting deadlock state is non-rewarding iff the target command is not enabled, so when target_expr is false

        fun commandsWithPrecondition(state: SMDPState<ExplState>) =
            smdpLts.getCommandsFor(state).map {
                it.withPrecondition(smdpReachabilityTask.constraint)
                    .extendWith(
                        smdpReachabilityTask.preStepAdditions,
                        smdpReachabilityTask.postStepAdditions
                    )
            }

        val explDomain = SMDPExplDomain(domainTransFunc, fullPrec, itpSolver)

        val checker = ProbLazyChecker(
            ::commandsWithPrecondition, { targetCommands(it.locs) },
            fullInit.first(), topInit.first(),
            explDomain,
            smdpReachabilityTask.goal,
            useMayStandard,
            useMustStandard,
            useMayTarget,
            useMustTarget,
            verboseLogging,
            useSeq = useSeq,
            useGameRefinement = useGameRefinement,
            useQualitativePreprocessing = useQualitativePreprocessing,
            mergeSameSCNodes = mergeSameSCNodes
        )
        val successorSelection = when (brtdpStrategy) {
            BRTDPStrategy.RANDOM -> SMDPLazyCheckerGame<ExplState>::randomSelection
            BRTDPStrategy.WEIGHTED_RANDOM -> SMDPLazyCheckerGame<ExplState>::weightedRandomSelection
            BRTDPStrategy.ROUND_ROBIN -> roundRobinSelection { this.chooseNextRR().second }
            BRTDPStrategy.DIFF_BASED -> SMDPLazyCheckerGame<ExplState>::diffBasedSelection
        }

        val varOrder = smdp.getAllVars()
        val extract = { s: SMDPState<ExplState> ->
            varOrder.map { v ->
                s.domainState.`val`.eval(v).orElse(null)
            }
        }

        val subResult = when (algorithm) {
            BRTDP -> checker.brtdp(successorSelection, threshold)
            VI -> checker.fullyExpanded(false, threshold, extract)
            BVI -> checker.fullyExpanded(true, threshold, extract)
        }

        return if (smdpReachabilityTask.negateResult) 1.0 - subResult else subResult
    }

    fun checkPred(
        smdp: SMDP,
        smdpReachabilityTask: SMDPReachabilityTask
    ): Double {

        fun targetCommands(locs: List<SMDP.Location>) = listOf(
            ProbabilisticCommand(
                smdpReachabilityTask.targetExpr, FiniteDistribution.dirac(
                    SMDPCommandAction.skipAt(locs, smdp)
                )
            )
        )

        val domainTransFunc = ExplStmtTransFunc.create(smtSolver, 0)
        val vars = smdp.getAllVars()
        val fullPrec = ExplPrec.of(vars)

        val initFunc = SmdpInitFunc(ExplInitFunc.create(smtSolver, smdp.getFullInitExpr()), smdp)
        val abstrInitFunc = SmdpInitFunc(
            PredInitFunc.create(
                PredAbstractors.booleanAbstractor(smtSolver),
                smdp.getFullInitExpr()
            ), smdp
        )

        val smdpLts = SmdpCommandLts<ExplState>(smdp)

        val topInit = abstrInitFunc.getInitStates(PredPrec.of())
        val fullInit = initFunc.getInitStates(fullPrec)

        // the task is given as the computation of P_opt(constraint U target_expr)
        // we add checking that the constraint is still true to every normal command,
        // so that we hit a deadlock (w.r.t. normal commands) if it is not
        // the resulting deadlock state is non-rewarding iff the target command is not enabled, so when target_expr is false

        fun commandsWithPrecondition(state: SMDPState<ExplState>) =
            smdpLts.getCommandsFor(state).map {
                it.withPrecondition(smdpReachabilityTask.constraint)
                    .extendWith(
                        smdpReachabilityTask.preStepAdditions,
                        smdpReachabilityTask.postStepAdditions
                    )
            }

        val predDomain = SMDPPredDomain(domainTransFunc, fullPrec, smtSolver, itpSolver, ucSolver, false)
        val checker = ProbLazyChecker(
            ::commandsWithPrecondition, { targetCommands(it.locs) },
            fullInit.first(), topInit.first(),
            predDomain,
            smdpReachabilityTask.goal,
            useMayStandard,
            useMustStandard,
            useMayTarget,
            useMustTarget,
            verboseLogging,
            useSeq = useSeq,
            useGameRefinement = useGameRefinement,
            useQualitativePreprocessing = useQualitativePreprocessing,
            mergeSameSCNodes = mergeSameSCNodes
        )

        val successorSelection = when (brtdpStrategy) {
            BRTDPStrategy.RANDOM -> SMDPLazyCheckerGame<PredState>::randomSelection
            BRTDPStrategy.WEIGHTED_RANDOM -> SMDPLazyCheckerGame<PredState>::weightedRandomSelection
            BRTDPStrategy.ROUND_ROBIN -> roundRobinSelection { this.chooseNextRR().second }
            BRTDPStrategy.DIFF_BASED -> SMDPLazyCheckerGame<PredState>::diffBasedSelection
        }


        val subResult = when (algorithm) {
            BRTDP -> checker.brtdp(successorSelection, threshold)
            VI -> checker.fullyExpanded(false, threshold)
            BVI -> checker.fullyExpanded(true, threshold)
        }

        return if (smdpReachabilityTask.negateResult) 1.0 - subResult else subResult
    }

}