package hu.bme.mit.theta.prob.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.enum
import hu.bme.mit.theta.analysis.InitFunc
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.expl.ExplInitFunc
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ItpRefToExplPrec
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.refinement.*
import hu.bme.mit.theta.analysis.pred.*
import hu.bme.mit.theta.core.model.ImmutableValuation
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.besttransformer.*
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameAction
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameNode
import hu.bme.mit.theta.prob.analysis.direct.SMDPDirectChecker
import hu.bme.mit.theta.prob.analysis.direct.SMDPDirectCheckerGame
import hu.bme.mit.theta.prob.analysis.jani.*
import hu.bme.mit.theta.prob.analysis.jani.model.Model
import hu.bme.mit.theta.prob.analysis.jani.model.json.JaniModelMapper
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.Algorithm
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.BRTDPStrategy.*
import hu.bme.mit.theta.prob.analysis.linkedtransfuncs.ExplLinkedTransFunc
import hu.bme.mit.theta.prob.analysis.linkedtransfuncs.LinkedTransFunc
import hu.bme.mit.theta.prob.analysis.linkedtransfuncs.PredLinkedTransFunc
import hu.bme.mit.theta.prob.analysis.linkedtransfuncs.SMDPLinkedTransFunc
import hu.bme.mit.theta.prob.analysis.menuabstraction.*
import hu.bme.mit.theta.prob.cli.JaniCLI.Domain.*
import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.probabilistic.StochasticGameSolver
import hu.bme.mit.theta.probabilistic.gamesolvers.*
import hu.bme.mit.theta.solver.ItpSolver
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.UCSolver
import hu.bme.mit.theta.solver.z3.Z3SolverFactory
import kotlin.io.path.Path

class JaniCLI : CliktCommand() {

    enum class Domain {
        PRED, EXPL, NONE
    }
    enum class Approximation(
        val useMayStandard: Boolean,
        val useMustStandard: Boolean,
        val useMayTarget: (Goal)->Boolean,
        val useMustTarget: (Goal)->Boolean
    ) {
        /**
         * Lower approximation of the possible behaviors, leading to lower power of the non-determinism.
         * Maximal values are approximated from below, minimal values from above.
         */
        LOWER(
            false,
            true,
            { when(it) {Goal.MAX -> false; Goal.MIN -> true } },
            { when(it) {Goal.MAX -> true; Goal.MIN -> false } }
        ),
        /**
         * Upper approximation of the possible behaviors, leading to higher power of the non-determinism.
         * Maximal values are approximated from above, minimal values from below.
         */
        UPPER(
            true,
            false,
            { when(it) {Goal.MAX -> true; Goal.MIN -> false } },
            { when(it) {Goal.MAX -> false; Goal.MIN -> true } }
            ),

        /**
         * Computes exact values for both minimal and maximal values.
         */
        EXACT(true, true, {true}, {true})
    }
    enum class AbstractionMethod() {
        LAZY, MENU_LAZY, MENU, BT
    }

    val model: String by option("-m", "--model", "-i", "--input",
        help = "Path to the input JANI file."
    ).required()
    val parameters by option( "-p", "--parameters",
        help = "Specifies model parameters - constants which do not have values defined in the model."
    )
    val threshold by option(
        help = "Threshold used for convergence checking."
    ).double().default(1e-6)
    val algorithm by option(
        help = "MDP solver algorithm to use."
    ).enum<Algorithm>().default(Algorithm.BVI)
    val abstraction by option(
        help = "Abstraction method to use. Defaults to ASG-based lazy abstraction for backwards compatibility."
    ).enum<AbstractionMethod>().default(AbstractionMethod.LAZY)
    val domain by option(
        help = "Abstract domain to use. NONE means direct model checking without abstraction."
    ).enum<Domain>().required()
    val approximation by option(
        help = "Approximation direction to use."
    ).enum<Approximation>().required()
    val property: String? by option(
        help = "Name of the JANI property to check. All properties are checked in sequence if not given."
    )
    val strategy by option(
        help = "Successor computation strategy for BRTDP."
    ).enum<SMDPLazyChecker.BRTDPStrategy>().default(DIFF_BASED)
    val verbose by option().flag()
    val preproc by option("--preproc",
        help = "Use qualitative preprocessing, i.e. precompute almost sure reachability and avoidance."
    ).flag("--nopreproc", default = true)
    val sequenceInterpolation by option("--seq",
        help = "Use sequence interpolation for refinement in lazy abstraction."
    ).flag("--noseq", default = true)
    val eliminateSpurious by option("--elim",
        help = "Use interpolation to eliminate (almost-)spurious pivot nodes during game refinement." +
                "Only used for best transformer abstraction for now."
    ).flag("--no-elim", default = false)


    override fun run() {

        val modelPath = Path(model)
        val parameters =
            parameters
                ?.split(",")
                ?.filter { it.isNotEmpty() }
                ?.map { it.split("=") }
                ?.associate { it[0] to it[1] }
                ?: mapOf()
        val model =
            JaniModelMapper()
                .readValue(modelPath.toFile(), Model::class.java)
                .toSMDP(parameters)
        val solver = Z3SolverFactory.getInstance().createSolver()
        val itpSolver = Z3SolverFactory.getInstance().createItpSolver()
        val ucSolver = Z3SolverFactory.getInstance().createUCSolver()

        for (prop in model.properties) {
            if(this.property != null && this.property != prop.name)
                continue
            if (prop is SMDPProperty.ProbabilityProperty || prop is SMDPProperty.ProbabilityThresholdProperty) {
                val (task, modifiedSMDP) =
                    try {
                        extractSMDPReachabilityTask(prop, model)
                    } catch (e: UnsupportedOperationException) {
                        if (this.property != null)
                            throw RuntimeException("Error: property ${prop.name} unsupported")
                        println("Error: property ${prop.name} unsupported, moving on")
                        continue
                    }
                val smdp = modifiedSMDP ?: model

                // TODO: correct config of may/must separately for standard and target commands should make approximate
                //  MIN computation correct, but this has not been sufficiently tested yet
                //if (task.goal == Goal.MIN && (domain != NONE && approximation != Approximation.EXACT))
                //    throw RuntimeException("Error: Approximate computation for MIN property ${prop.name} unsupported")

                val result = when (abstraction) {
                    AbstractionMethod.LAZY, AbstractionMethod.MENU_LAZY -> lazy(solver, itpSolver, ucSolver, task, smdp)
                    AbstractionMethod.MENU -> menu(solver, itpSolver, ucSolver, task, smdp)
                    AbstractionMethod.BT -> bestTransformer(solver, itpSolver, ucSolver, task, smdp)
                }
                println("result: ${prop.name}: $result")
            } else if(prop is SMDPProperty.ExpectationProperty && domain == NONE) {
                val task = extractSMDPExpectedRewardTask(prop)
                val directChecker = SMDPDirectChecker(solver, verbose, preproc)
                val successorSelection = when (strategy) {
                    DIFF_BASED -> SMDPDirectCheckerGame::diffBasedSelection
                    RANDOM -> SMDPDirectCheckerGame::randomSelection
                    ROUND_ROBIN -> TODO()
                    WEIGHTED_RANDOM -> SMDPDirectCheckerGame::weightedRandomSelection
                }
                val quantSolver = when (algorithm) {
                    Algorithm.BVI -> MDPBVISolver(threshold)
                    Algorithm.VI -> VISolver(threshold)
                    Algorithm.BRTDP -> MDPBRTDPSolver(
                        successorSelection,
                        threshold
                    ) { iteration, reachedSet, linit, uinit ->
                        if (verbose) {
                            if(iteration % 1000 == 0) println("Iteration $iteration: [$linit, $uinit], ${reachedSet.size} nodes")
                        }
                    }
                }
                val result = directChecker.check(model, task, quantSolver)

                println("${prop.name}: $result")
            } else {
                if(this.property != null)
                    throw RuntimeException("Error: Non-probability property ${prop.name} unsupported")
                println("Non-probability property found")
            }
        }
    }

    private fun menu(
        solver: Solver,
        itpSolver: ItpSolver,
        ucSolver: UCSolver,
        task: SMDPReachabilityTask,
        model: SMDP,
    ) : Double {
        val traceChecker =
            if(sequenceInterpolation) ExprTraceSeqItpChecker.create(model.getFullInitExpr(), BoolExprs.True(), itpSolver)
            else ExprTraceBwBinItpChecker.create(model.getFullInitExpr(), BoolExprs.True(), itpSolver)
        return when(domain) {
            PRED -> {
                val exprSplitter = ExprSplitters.atoms()
                return menuHelper(
                    solver,
                    itpSolver,
                    ucSolver,
                    task,
                    model,
                    PredInitFunc.create(
                        PredAbstractors.booleanSplitAbstractor(solver),
                        model.getFullInitExpr()
                    ),
                    PredLinkedTransFunc(solver),
                    smdpCanBeDisabled(predCanBeDisabled(solver)),
                    smdpMaySatisfy(predMaySatisfy(solver)),
                    smdpMustSatisfy(predMustSatisfy(solver)),
                    PredPrec.of(task.targetExpr),
                    {
                        this.join(PredPrec.of(exprSplitter.apply(it)))
                    },
                    when(algorithm) {
                        Algorithm.BVI -> SGBVISolver(threshold)
                        Algorithm.VI -> VISolver(threshold)
                        Algorithm.BRTDP -> TODO()
                    },
                    eliminateSpurious,
                    traceChecker,
                    ItpRefToPredPrec(exprSplitter)
                )
            }
            EXPL -> menuHelper(
                solver,
                itpSolver,
                ucSolver,
                task,
                model,
                ExplInitFunc.create(
                    solver,
                    model.getFullInitExpr()
                ),
                ExplLinkedTransFunc(0, solver),
                smdpCanBeDisabled(::explCanBeDisabled),
                smdpMaySatisfy(::explMaySatisfy),
                smdpMustSatisfy(::explMustSatisfy),
                ExplPrec.of(ExprUtils.getVars(task.targetExpr)),
                {this.join(ExplPrec.of(ExprUtils.getVars(it)))},
                when(algorithm) {
                    Algorithm.BVI -> SGBVISolver(threshold)
                    Algorithm.VI -> VISolver(threshold)
                    Algorithm.BRTDP -> TODO()
                },
                eliminateSpurious,
                traceChecker,
                ItpRefToExplPrec()
            )
            NONE -> throw IllegalArgumentException("Domain must be selected for menu game abstraction")
        }
    }

    private fun <D: ExprState, P: Prec, R: Refutation> menuHelper(
        solver: Solver,
        itpSolver: ItpSolver,
        ucSolver: UCSolver,
        task: SMDPReachabilityTask,
        model: SMDP,
        domainInitFunc: InitFunc<D, P>,
        domainTransFunc: LinkedTransFunc<D, SMDPCommandAction, P>,
        canBeDisabled: (SMDPState<D>, Expr<BoolType>) -> Boolean,
        maySatisfy: (SMDPState<D>, Expr<BoolType>) -> Boolean,
        mustSatisfy: (SMDPState<D>, Expr<BoolType>) -> Boolean,
        initPrec: P,
        extend: P.(basedOn: Expr<BoolType>) -> P,
        quantSolver: StochasticGameSolver<MenuGameNode<SMDPState<D>, SMDPCommandAction>, MenuGameAction<SMDPState<D>, SMDPCommandAction>>,
        eliminateSpurious: Boolean,
        traceChecker: ExprTraceChecker<R>,
        refToPrec: RefutationToPrec<P, R>
    ): Double {
        val lts = SmdpCommandLts<D>(model)
        val initFunc = SmdpInitFunc<D, P>(domainInitFunc, model)
        val transFunc = BasicMenuGameTransFunc(SMDPLinkedTransFunc(domainTransFunc), canBeDisabled)
        val abstractor = MenuGameAbstractor(
            lts,
            initFunc,
            transFunc,
            task.targetExpr,
            maySatisfy,
            mustSatisfy,
            eliminateSpurious
        )

        val refiner = MenuGameRefiner<SMDPState<D>, SMDPCommandAction, P, R>(
            solver,
            extend,
            eliminateSpurious,
            traceChecker,
            refToPrec
        )

        val checker = MenuGameCegarChecker(
            abstractor,
            refiner,
            quantSolver
        )
        return checker.check(initPrec, task.goal, threshold).finalUpperInitValue
    }

    private fun bestTransformer(
        solver: Solver,
        itpSolver: ItpSolver,
        ucSolver: UCSolver,
        task: SMDPReachabilityTask,
        model: SMDP
    ): Double {
        val traceChecker =
            if(sequenceInterpolation) ExprTraceSeqItpChecker.create(model.getFullInitExpr(), BoolExprs.True(), itpSolver)
            else ExprTraceBwBinItpChecker.create(model.getFullInitExpr(), BoolExprs.True(), itpSolver)
        return when(domain) {
            PRED -> {
                val exprSplitter = ExprSplitters.atoms()
                bestTransformerHelper(
                    solver, itpSolver, ucSolver, task, model,
                    PredInitFunc.create(
                        PredAbstractors.booleanSplitAbstractor(solver),
                        model.getFullInitExpr()
                    ),
                    PredLinkedTransFunc(solver),
                    smdpGetGuardSatisfactionConfigs(predGetGuardSatisfactionConfigs(solver)),
                    smdpMaySatisfy(predMaySatisfy(solver)),
                    smdpMustSatisfy(predMustSatisfy(solver)),
                    PredPrec.of(task.targetExpr),
                    { this.join(PredPrec.of(exprSplitter.apply(it))) },
                    when(algorithm) {
                        Algorithm.BVI -> SGBVISolver(threshold)
                        Algorithm.VI -> VISolver(threshold)
                        Algorithm.BRTDP -> TODO()
                    },
                    ReachableMostUncertain(),
                    eliminateSpurious,
                    traceChecker,
                    ItpRefToPredPrec(exprSplitter)
                )
            }
            EXPL -> bestTransformerHelper(
                solver, itpSolver, ucSolver, task, model,
                ExplInitFunc.create(
                    solver,
                    model.getFullInitExpr()
                ),
                ExplLinkedTransFunc(0, solver),
                smdpGetGuardSatisfactionConfigs(explGetGuardSatisfactionConfigs(solver)),
                smdpMaySatisfy(::explMaySatisfy),
                smdpMustSatisfy(::explMustSatisfy),
                ExplPrec.of(ExprUtils.getVars(task.targetExpr)),
                {this.join(ExplPrec.of(ExprUtils.getVars(it)))},
                when(algorithm) {
                    Algorithm.BVI -> SGBVISolver(threshold)
                    Algorithm.VI -> VISolver(threshold)
                    Algorithm.BRTDP -> TODO()
                },
                ReachableMostUncertain(),
                eliminateSpurious,
                traceChecker,
                ItpRefToExplPrec()
            )

            NONE -> throw IllegalArgumentException("Domain must be selected for best transformer game abstraction")
        }

    }

    private fun <D: ExprState, P: Prec, R: Refutation> bestTransformerHelper(
        solver: Solver,
        itpSolver: ItpSolver,
        ucSolver: UCSolver,
        task: SMDPReachabilityTask,
        model: SMDP,
        domainInitFunc: InitFunc<D, P>,
        domainTransFunc: LinkedTransFunc<D, SMDPCommandAction, P>,
        getGuardSatisfactionConfigs: (SMDPState<D>, List<ProbabilisticCommand<SMDPCommandAction>>) -> List<List<ProbabilisticCommand<SMDPCommandAction>>>,
        maySatisfy: (SMDPState<D>, Expr<BoolType>) -> Boolean,
        mustSatisfy: (SMDPState<D>, Expr<BoolType>) -> Boolean,
        initPrec: P,
        extend: P.(basedOn: Expr<BoolType>) -> P,
        quantSolver: StochasticGameSolver<BestTransformerGameNode<SMDPState<D>, SMDPCommandAction>, BestTransformerGameAction<SMDPState<D>, SMDPCommandAction>>,
        pivotSelectionStrategy: PivotSelectionStrategy,
        eliminateSpurious: Boolean,
        traceChecker: ExprTraceChecker<R>,
        refToPrec: RefutationToPrec<P, R>
    ): Double {
        val lts = SmdpCommandLts<D>(model)
        val initFunc = SmdpInitFunc<D, P>(domainInitFunc, model)
        val transFunc = BasicBestTransformerTransFunc(SMDPLinkedTransFunc(domainTransFunc), getGuardSatisfactionConfigs)
        val abstractor = BestTransformerAbstractor(
            lts,
            initFunc,
            transFunc,
            task.targetExpr,
            maySatisfy,
            mustSatisfy,
            eliminateSpurious
        )

        val refiner = BestTransformerRefiner<SMDPState<D>, SMDPCommandAction, P, R>(
            solver, extend, pivotSelectionStrategy, eliminateSpurious, traceChecker, refToPrec
        )

        val checker = BestTransformerCegarChecker(
            abstractor,
            refiner,
            quantSolver
        )
        return checker.check(initPrec, task.goal, threshold).finalUpperInitValue
    }

    private fun lazy(
        solver: Solver,
        itpSolver: ItpSolver,
        ucSolver: UCSolver,
        task: SMDPReachabilityTask,
        model: SMDP
    ): Double {
        val preproc = if (algorithm == Algorithm.BRTDP) false else preproc

        val lazyChecker = SMDPLazyChecker(
            solver,
            itpSolver,
            ucSolver,
            algorithm,
            verbose,
            strategy,
            approximation.useMayStandard,
            approximation.useMustStandard,
            approximation.useMayTarget(task.goal),
            approximation.useMustTarget(task.goal),
            threshold,
            sequenceInterpolation,
            this.abstraction == AbstractionMethod.MENU_LAZY,
            preproc
        )

        if (model.getAllVars().size < 30) {
            ImmutableValuation.experimental = true
            ImmutableValuation.declOrder = model.getAllVars().toTypedArray()
        }

        val directChecker = SMDPDirectChecker(solver, verbose, preproc)
        val successorSelection = when (strategy) {
            DIFF_BASED -> SMDPDirectCheckerGame::diffBasedSelection
            RANDOM -> SMDPDirectCheckerGame::randomSelection
            ROUND_ROBIN -> TODO()
            WEIGHTED_RANDOM -> SMDPDirectCheckerGame::weightedRandomSelection
        }
        val quantSolverSupplier = when (algorithm) {
            Algorithm.BVI -> MDPBVISolver(threshold)
            Algorithm.VI -> VISolver(threshold)
            Algorithm.BRTDP -> MDPBRTDPSolver(
                successorSelection,
                threshold
            ) { iteration, reachedSet, linit, uinit ->
                if (verbose) {
                    if(iteration % 1000 == 0) println("Iteration $iteration: [$linit, $uinit], ${reachedSet.size} nodes")
                }
            }
        }
        val result = when (domain) {
            PRED -> lazyChecker.checkPred(model, task)
            EXPL -> lazyChecker.checkExpl(model, task)
            NONE -> directChecker.check(model, task, quantSolverSupplier)
        }
        return result
    }
}

fun main(args: Array<String>) = JaniCLI().main(args)