package hu.bme.mit.theta.prob.analysis.lazy

import hu.bme.mit.theta.analysis.Trace
import hu.bme.mit.theta.analysis.expl.*
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceSeqItpChecker
import hu.bme.mit.theta.analysis.pred.*
import hu.bme.mit.theta.common.container.Containers
import hu.bme.mit.theta.core.decl.Decl
import hu.bme.mit.theta.core.model.ImmutableValuation
import hu.bme.mit.theta.core.model.MutableValuation
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.core.stmt.Stmts
import hu.bme.mit.theta.core.stmt.Stmts.Assume
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolExprs.Not
import hu.bme.mit.theta.core.type.booltype.BoolExprs.True
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.utils.ExprSimplifier
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.core.utils.PathUtils
import hu.bme.mit.theta.core.utils.WpState
import hu.bme.mit.theta.prob.analysis.BasicStmtAction
import hu.bme.mit.theta.prob.analysis.jani.*
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.Algorithm.*
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.solver.ItpSolver
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.utils.WithPushPop
import hu.bme.mit.theta.xta.analysis.expl.XtaExplUtils

class SMDPLazyChecker(
    val smtSolver: Solver,
    val itpSolver: ItpSolver,
    val algorithm: Algorithm,
    val verboseLogging: Boolean = false
) {

    enum class BRTDPStrategy {
        MAX_DIFF, RANDOM, ROUND_ROBIN,
        WEIGHTED_MAX, WEIGHTED_RANDOM,
        DIFF_BASED
    }

    enum class Algorithm {
        BRTDP, VI, BVI
    }

    fun checkExpl(
        smdp: SMDP,
        smdpReachabilityTask: SMDPReachabilityTask,
        brtdpStrategy: BRTDPStrategy = BRTDPStrategy.MAX_DIFF,
        useMay: Boolean = true,
        useMust: Boolean = false,
        threshold: Double = 1e-7,
        useSeq: Boolean = false,
        useGameRefinement: Boolean = false
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
            smdpLts.getCommandsFor(state).map { it.withPrecondition(smdpReachabilityTask.constraint) }

        val checker = ProbLazyChecker<
                SMDPState<ExplState>,
                SMDPState<ExplState>,
                SMDPCommandAction
                >(
            ::commandsWithPrecondition, { targetCommands(it.locs) },
            fullInit.first(), topInit.first(),
            ExplDomain::checkContainment,
            ExplDomain::isLeq,
            ExplDomain::mayBeEnabled,
            ExplDomain::mustBeEnabled,
            ExplDomain::isEnabled,
            { sc, a ->
                val res = domainTransFunc.getSuccStates(
                    sc.domainState, a, fullPrec
                )
                require(res.size == 1) { "Concrete trans func returned multiple successor states :-(" }
                SMDPState(res.first(), nextLocs(sc.locs, a.destination))
            },
            ExplDomain::block,
            ExplDomain::postImage,
            ExplDomain::preImage,
            ExplDomain::topAfter,
            smdpReachabilityTask.goal,
            useMay,
            useMust,
            verboseLogging,
            blockSeq = ExplDomain::blockSeq,
            useSeq = useSeq,
            useGameRefinement = useGameRefinement
        )
        val successorSelection = when (brtdpStrategy) {
            BRTDPStrategy.MAX_DIFF -> checker::maxDiffSelection
            BRTDPStrategy.RANDOM -> checker::randomSelection
            BRTDPStrategy.WEIGHTED_MAX -> checker::weightedMaxSelection
            BRTDPStrategy.WEIGHTED_RANDOM -> checker::weightedRandomSelection
            BRTDPStrategy.ROUND_ROBIN -> checker::roundRobinSelection
            BRTDPStrategy.DIFF_BASED -> checker::diffBasedSelection
        }

        val subResult = when (algorithm) {
            BRTDP -> checker.brtdp(successorSelection, threshold)
            VI -> checker.fullyExpanded(false, threshold)
            BVI -> checker.fullyExpanded(true, threshold)
        }

        return if (smdpReachabilityTask.negateResult) 1.0 - subResult else subResult
    }

    fun checkPred(
        smdp: SMDP,
        smdpReachabilityTask: SMDPReachabilityTask,
        brtdpStrategy: BRTDPStrategy = BRTDPStrategy.MAX_DIFF,
        useMay: Boolean = true,
        useMust: Boolean = false,
        threshold: Double = 1e-7,
        useSeq: Boolean = false,
        useGameRefinement: Boolean = false
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
            smdpLts.getCommandsFor(state).map { it.withPrecondition(smdpReachabilityTask.constraint) }

        val checker = ProbLazyChecker<
                SMDPState<ExplState>,
                SMDPState<PredState>,
                SMDPCommandAction
                >(
            ::commandsWithPrecondition, { targetCommands(it.locs) },
            fullInit.first(), topInit.first(),
            PredDomain::checkContainment,
            PredDomain::isLeq,
            PredDomain::mayBeEnabled,
            PredDomain::mustBeEnabled,
            PredDomain::isEnabled,
            { sc, a ->
                val res = domainTransFunc.getSuccStates(
                    sc.domainState, a, fullPrec
                )
                require(res.size == 1) { "Concrete trans func returned multiple successor states :-(" }
                SMDPState(res.first(), nextLocs(sc.locs, a.destination))
            },
            PredDomain::block,
            PredDomain::postImage,
            PredDomain::preImage,
            PredDomain::topAfter,
            smdpReachabilityTask.goal,
            useMay, useMust, verboseLogging,
            blockSeq = PredDomain::blockSeq,
            useSeq = useSeq,
            useGameRefinement = useGameRefinement
        )

        val successorSelection = when (brtdpStrategy) {
            BRTDPStrategy.MAX_DIFF -> checker::maxDiffSelection
            BRTDPStrategy.RANDOM -> checker::randomSelection
            BRTDPStrategy.WEIGHTED_MAX -> checker::weightedMaxSelection
            BRTDPStrategy.WEIGHTED_RANDOM -> checker::weightedRandomSelection
            BRTDPStrategy.ROUND_ROBIN -> checker::roundRobinSelection
            BRTDPStrategy.DIFF_BASED -> checker::diffBasedSelection
        }

        val subResult = when (algorithm) {
            BRTDP -> checker.brtdp(successorSelection, threshold)
            VI -> checker.fullyExpanded(false, threshold)
            BVI -> checker.fullyExpanded(true, threshold)
        }

        return if (smdpReachabilityTask.negateResult) 1.0 - subResult else subResult
    }

    private val ExplDomain = object {

        fun checkContainment(sc: SMDPState<ExplState>, sa: SMDPState<ExplState>): Boolean =
            sc.locs == sa.locs &&
                    ExplOrd.getInstance().isLeq(sc.domainState, sa.domainState)

        fun isLeq(sc: SMDPState<ExplState>, sa: SMDPState<ExplState>) =
            sc.locs == sa.locs &&
                    ExplOrd.getInstance().isLeq(sc.domainState, sa.domainState)

        fun mayBeEnabled(state: SMDPState<ExplState>, command: ProbabilisticCommand<SMDPCommandAction>): Boolean {
            val simplified = ExprSimplifier.simplify(command.guard, state.domainState)
            return simplified != BoolExprs.False()
        }

        fun mustBeEnabled(state: SMDPState<ExplState>, command: ProbabilisticCommand<SMDPCommandAction>): Boolean {
            val simplified = ExprSimplifier.simplify(command.guard, state.domainState)
            return simplified == True()
        }

        fun block(
            abstrState: SMDPState<ExplState>,
            expr: Expr<BoolType>,
            concrState: SMDPState<ExplState>
        ): SMDPState<ExplState> {
            require(
                ExplOrd.getInstance().isLeq(concrState.domainState, abstrState.domainState) &&
                        concrState.locs == abstrState.locs
            ) {
                "Block failed: Concrete state $concrState not contained in abstract state $abstrState!"
            }
            require(expr.eval(concrState.domainState) == BoolExprs.False()) {
                "Block failed: Concrete state $concrState does not contradict $expr"
            }

            // Using backward strategy
            val valI = XtaExplUtils.interpolate(concrState.domainState, expr)

            val newVars: MutableCollection<Decl<*>> = Containers.createSet()
            newVars.addAll(valI.getDecls())
            newVars.addAll(abstrState.domainState.getDecls())
            val builder = ImmutableValuation.builder()
            for (decl in newVars) {
                builder.put(decl, concrState.domainState.eval(decl).get())
            }
            val `val`: Valuation = builder.build()
            val newAbstractExpl = SMDPState<ExplState>(ExplState.of(`val`), concrState.locs)

            return newAbstractExpl
        }

        fun blockSeq(
            nodes: List<ProbLazyChecker<SMDPState<ExplState>, SMDPState<ExplState>, SMDPCommandAction>.Node>,
            guards: List<Expr<BoolType>>,
            actions: List<SMDPCommandAction>,
            toBlockAtLast: Expr<BoolType>
        ): List<SMDPState<ExplState>> {
            val seqItpChecker = ExprTraceSeqItpChecker.create(nodes.first().sc.toExpr(), toBlockAtLast, itpSolver)
            val states = nodes.map { it.sa }
            val actions = guards.zip(actions).map {
                (g: Expr<BoolType>, a: SMDPCommandAction) ->
                BasicStmtAction(listOf(Assume(g)) + a.stmts)
            }
            val trace = Trace.of(states, actions)
            val status = seqItpChecker.check(trace)
            if (status.isFeasible) throw RuntimeException("cannot block sequence")
            else {
                return nodes.zip(status.asInfeasible().refutation).map { (node, itp) ->
                    val newVars: MutableCollection<Decl<*>> = Containers.createSet()
                    newVars.addAll(ExprUtils.getVars(itp))
                    newVars.addAll(node.sa.domainState.getDecls())
                    val builder = ImmutableValuation.builder()
                    for (decl in newVars) {
                        builder.put(decl, node.sc.domainState.eval(decl).get())
                    }
                    val `val`: Valuation = builder.build()
                    val newAbstractExpl = SMDPState<ExplState>(ExplState.of(`val`), node.sc.locs)
                    newAbstractExpl
                }
            }
        }

        fun postImage(
            state: SMDPState<ExplState>,
            action: SMDPCommandAction,
            guard: Expr<BoolType>
        ): SMDPState<ExplState> {
            val res = MutableValuation.copyOf(state.domainState.`val`)
            val stmts = listOf(Assume(guard)) + action.stmts
            StmtApplier.apply(Stmts.SequenceStmt(stmts), res, true)
            return SMDPState(ExplState.of(res), nextLocs(state.locs, action.destination))
        }

        fun preImage(state: SMDPState<ExplState>, action: SMDPCommandAction): Expr<BoolType> =
            WpState.of(state.toExpr()).wep(Stmts.SequenceStmt(action.stmts)).expr

        fun topAfter(state: SMDPState<ExplState>, action: SMDPCommandAction): SMDPState<ExplState> =
            SMDPState(ExplState.top(), nextLocs(state.locs, action.destination))

        fun isEnabled(s: SMDPState<ExplState>, probabilisticCommand: ProbabilisticCommand<*>): Boolean {
            return probabilisticCommand.guard.eval(s.domainState) == True()
        }
    }

    private val PredDomain = object {
        val ord = PredOrd.create(smtSolver)

        fun checkContainment(sc: SMDPState<ExplState>, sa: SMDPState<PredState>): Boolean {
            if (sc.locs != sa.locs)
                return false

            val res = sa.toExpr().eval(sc.domainState)
            return if (res == True()) true
            else if (res == BoolExprs.False()) false
            else throw IllegalArgumentException("concrete state must be a full valuation")
        }

        fun isLeq(sa1: SMDPState<PredState>, sa2: SMDPState<PredState>) =
            sa1.locs == sa2.locs &&
                    ord.isLeq(sa1.domainState, sa2.domainState)

        fun mayBeEnabled(state: SMDPState<PredState>, command: ProbabilisticCommand<SMDPCommandAction>): Boolean {
            WithPushPop(smtSolver).use {
                smtSolver.add(PathUtils.unfold(state.toExpr(), 0))
                smtSolver.add(PathUtils.unfold(command.guard, 0))
                return smtSolver.check().isSat
            }
        }

        fun mustBeEnabled(state: SMDPState<PredState>, command: ProbabilisticCommand<SMDPCommandAction>): Boolean {
            WithPushPop(smtSolver).use {
                smtSolver.add(PathUtils.unfold(state.toExpr(), 0))
                smtSolver.add(PathUtils.unfold(Not(command.guard), 0))
                return smtSolver.check().isUnsat
            }
        }

        var useItp: Boolean = false
        fun block(
            abstrState: SMDPState<PredState>,
            expr: Expr<BoolType>,
            concrState: SMDPState<ExplState>
        ): SMDPState<PredState> {
            require(checkContainment(concrState, abstrState)) {
                "Block failed: Concrete state $concrState not contained in abstract state $abstrState!"
            }
            require(expr.eval(concrState.domainState) == BoolExprs.False()) {
                "Block failed: Concrete state $concrState does not contradict $expr"
            }

            val newAbstract = if(useItp) {
                lateinit var itp: Expr<BoolType>
                WithPushPop(itpSolver).use {
                    val A = itpSolver.createMarker()
                    val B = itpSolver.createMarker()
                    itpSolver.add(A, PathUtils.unfold(concrState.toExpr(), 0))
                    itpSolver.add(B, PathUtils.unfold(expr, 0))
                    val pattern = itpSolver.createBinPattern(A, B)
                    if (itpSolver.check().isSat)
                        throw IllegalArgumentException("Block failed: Concrete state $concrState does not contradict $expr")
                    itp = itpSolver.getInterpolant(pattern).eval(A)
                }

                val itpConjuncts = ExprUtils.getConjuncts(PathUtils.foldin(itp, 0)).filter {
                    WithPushPop(smtSolver).use { _ ->
                        smtSolver.add(PathUtils.unfold(abstrState.toExpr(), 0))
                        smtSolver.add(PathUtils.unfold(Not(it), 0))
                        smtSolver.check().isSat
                    }
                }
                val newConjuncts = abstrState.domainState.preds.toSet().union(itpConjuncts)

                PredState.of(newConjuncts)
            } else {
                val newPred = ExprUtils.canonize(Not(expr))
                if(abstrState.domainState.preds.contains(newPred)) abstrState.domainState
                else {
                    val needed = WithPushPop(smtSolver).use {
                        smtSolver.add(PathUtils.unfold(abstrState.domainState.toExpr(), 0))
                        smtSolver.add(PathUtils.unfold(expr, 0))
                        smtSolver.check().isSat
                    }
                    if (needed) {
                        PredState.of(abstrState.domainState.preds + newPred)
                    } else abstrState.domainState
                }
            }

            return SMDPState(newAbstract, abstrState.locs)
        }

        fun blockSeq(
            nodes: List<ProbLazyChecker<SMDPState<ExplState>, SMDPState<PredState>, SMDPCommandAction>.Node>,
            guards: List<Expr<BoolType>>,
            actions: List<SMDPCommandAction>,
            toBlockAtLast: Expr<BoolType>
        ): List<SMDPState<PredState>> {
            val seqItpChecker = ExprTraceSeqItpChecker.create(nodes.first().sc.toExpr(), toBlockAtLast, itpSolver)
            val states = nodes.map { it.sa }
            val actions = guards.zip(actions).map {
                    (g: Expr<BoolType>, a: SMDPCommandAction) ->
                BasicStmtAction(listOf(Assume(g)) + a.stmts)
            }
            val trace = Trace.of(states, actions)
            val status = seqItpChecker.check(trace)
            if (status.isFeasible)
                throw RuntimeException("cannot block sequence")
            else {
                return nodes.zip(status.asInfeasible().refutation).map { (node, itp) ->
                    val itpConjuncts = ExprUtils.getConjuncts(PathUtils.foldin(itp, 0))
//                    .filter {
//                        WithPushPop(smtSolver).use { _ ->
//                            smtSolver.add(PathUtils.unfold(node.sa.toExpr(), 0))
//                            smtSolver.add(PathUtils.unfold(Not(it), 0))
//                            smtSolver.check().isSat
//                        }
//                    }
                    val newConjuncts = node.sa.domainState.preds.toSet().union(itpConjuncts)

                    SMDPState(PredState.of(newConjuncts), node.sc.locs)
                }
            }
        }

        fun postImage(
            state: SMDPState<PredState>,
            action: SMDPCommandAction,
            guard: Expr<BoolType>
        ): SMDPState<PredState> {
            TODO()
        }

        fun preImage(state: SMDPState<PredState>, action: SMDPCommandAction): Expr<BoolType> =
            WpState.of(state.toExpr()).wep(Stmts.SequenceStmt(action.stmts)).expr

        fun topAfter(state: SMDPState<PredState>, action: SMDPCommandAction): SMDPState<PredState> =
            SMDPState(PredState.of(), nextLocs(state.locs, action.destination))

        fun isEnabled(s: SMDPState<ExplState>, probabilisticCommand: ProbabilisticCommand<*>): Boolean {
            return probabilisticCommand.guard.eval(s.domainState) == True()
        }
    }

}