package hu.bme.mit.theta.prob.analysis.besttransformer

import hu.bme.mit.theta.analysis.InitFunc
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expl.ItpRefToExplPrec
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceBwBinItpChecker
import hu.bme.mit.theta.analysis.expr.refinement.ItpRefutation
import hu.bme.mit.theta.analysis.pred.PredAbstractors
import hu.bme.mit.theta.analysis.pred.PredInitFunc
import hu.bme.mit.theta.analysis.pred.PredPrec
import hu.bme.mit.theta.analysis.pred.PredState
import hu.bme.mit.theta.common.visualization.writer.GraphvizWriter
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.stmt.Stmts.Assign
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolExprs.And
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.inttype.IntExprs.*
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.prob.analysis.P_ABSTRACTION
import hu.bme.mit.theta.prob.analysis.P_CONCRETE
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameAction
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameNode
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameNode.AbstractionChoiceNode
import hu.bme.mit.theta.prob.analysis.linkedtransfuncs.ExplLinkedTransFunc
import hu.bme.mit.theta.prob.analysis.linkedtransfuncs.PredLinkedTransFunc
import hu.bme.mit.theta.prob.analysis.menuabstraction.*
import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.probabilistic.StochasticGame
import hu.bme.mit.theta.probabilistic.gamesolvers.VISolver
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.TargetSetLowerInitializer
import hu.bme.mit.theta.probabilistic.setGoal
import hu.bme.mit.theta.solver.z3.Z3SolverFactory
import org.junit.Test
import java.awt.Color

class BestTransformerAbstractionTest {

    val A = Decls.Var("A", Int())
    val B = Decls.Var("B", Int())
    val C = Decls.Var("C", Int())
    val fullInit = createState(A to 0, B to 0, C to 0)

    lateinit var targetExpr: Expr<BoolType>
    val solver = Z3SolverFactory.getInstance().createSolver()

    val explInit = InitFunc<ExplState, ExplPrec> { prec -> listOf(prec.createState(fullInit)) }
    val innerPredInitFunc = PredInitFunc.create(PredAbstractors.booleanAbstractor(solver), fullInit.toExpr())
    val predInit = InitFunc<PredState, PredPrec> { prec -> innerPredInitFunc.getInitStates(prec) }

    lateinit var explLts: SimpleProbLTS<ExplState>
    lateinit var predLts: SimpleProbLTS<PredState>

    lateinit var explTransFunc: BestTransformerTransFunc<ExplState, StmtAction, ExplPrec>
    lateinit var predTransFunc: BestTransformerTransFunc<PredState, StmtAction, PredPrec>
    lateinit var explAbstractor: BestTransformerAbstractor<ExplState, StmtAction, ExplPrec>
    lateinit var predAbstractor: BestTransformerAbstractor<PredState, StmtAction, PredPrec>

    private fun simpleSetup() {
        // [A < 2 && B < 3]:
        // - 0.8: A:=A+1
        // - 0.2: B:=B+1
        // [C < 3]:
        // - 1.0: C:=C+1
        val commands = listOf(
            And(Lt(A.ref, Int(2)), Lt(B.ref, Int(3))).then(
                0.8 to Assign(A, Add(A.ref, Int(1))),
                0.2 to Assign(B, Add(B.ref, Int(1)))
            ),
            Lt(C.ref, Int(3)).then(1.0 to Assign(C, Add(C.ref, Int(1))))
        )
        explLts = SimpleProbLTS(commands)
        predLts = SimpleProbLTS(commands)
        targetExpr = Eq(A.ref, Int(2))

        explTransFunc =
            BasicBestTransformerTransFunc(
                ExplLinkedTransFunc(0, solver),
                explGetGuardSatisfactionConfigs(solver)
            )
        explAbstractor = BestTransformerAbstractor(
            explLts, explInit, explTransFunc,
            targetExpr,
            ::explMaySatisfy,
            ::explMustSatisfy
        )

        predTransFunc =
            BasicBestTransformerTransFunc(
                PredLinkedTransFunc(solver),
                predGetGuardSatisfactionConfigs(solver)
            )
        predAbstractor = BestTransformerAbstractor(
            predLts, predInit, predTransFunc,
            targetExpr,
            predMaySatisfy(solver),
            predMustSatisfy(solver)
        )

    }


    private fun <S : ExprState> checkAndViz(abstraction: BestTransformerAbstractor.AbstractionResult<S, StmtAction>) {
        val (lowerSolution, upperSolution) = computeValues(abstraction)

        // checked manually
        testViz(abstraction.game, lowerSolution.first, upperSolution.first)
    }

    private fun <S : ExprState> testViz(
        sg: StochasticGame<BestTransformerGameNode<S, StmtAction>, BestTransformerGameAction<S, StmtAction>>,
        lowerValues: Map<BestTransformerGameNode<S, StmtAction>, Double>,
        upperValues: Map<BestTransformerGameNode<S, StmtAction>, Double>
    ) {
        val materResult = sg.materialize()
        val viz = GraphvizWriter.getInstance().writeString(
            materResult.first.visualize(
                lowerValues.mapKeys { materResult.second[it.key]!! },
                upperValues.mapKeys { materResult.second[it.key]!! },
                sg.getAllNodes().filter {
                    it is AbstractionChoiceNode && it.maxReward == 1
                }.map { materResult.second[it]!! }.associateWith { Color(255, 150, 150) }
            )
        )
        println(viz)
    }

    private fun <S : ExprState> computeValues(
        abstraction: BestTransformerAbstractor.AbstractionResult<S, StmtAction>
    ): Pair<
            Pair<
                    Map<BestTransformerGameNode<S, StmtAction>, Double>,
                    Map<BestTransformerGameNode<S, StmtAction>, BestTransformerGameAction<S, StmtAction>>
                    >,
            Pair<
                    Map<BestTransformerGameNode<S, StmtAction>, Double>,
                    Map<BestTransformerGameNode<S, StmtAction>, BestTransformerGameAction<S, StmtAction>>
                    >
            > {
        val lowerAnalysisTask =
            AnalysisTask(abstraction.game, setGoal(P_CONCRETE to Goal.MAX, P_ABSTRACTION to Goal.MIN))
        val lowerSolution = VISolver(
            abstraction.rewardMin,
            TargetSetLowerInitializer {
                it is AbstractionChoiceNode && abstraction.rewardMin(it) == 1.0
            },
            1e-6,
            false
        ).solveWithStrategy(lowerAnalysisTask)

        val upperAnalysisTask =
            AnalysisTask(abstraction.game, setGoal(P_CONCRETE to Goal.MAX, P_ABSTRACTION to Goal.MAX))
        val upperSolution = VISolver(
            abstraction.rewardMax,
            TargetSetLowerInitializer {
                it is AbstractionChoiceNode && abstraction.rewardMax(it) == 1.0
            },
            1e-6,
            false
        ).solveWithStrategy(upperAnalysisTask)
        return Pair(lowerSolution, upperSolution)
    }

    @Test
    fun explicitAllVarsAbstractionTest() {
        simpleSetup()
        val abstraction = explAbstractor.computeAbstraction(ExplPrec.of(listOf(A, B, C)))
        val sg = abstraction.game
        val nodes = sg.getAllNodes()

        // There cannot be any abstraction choice as all vars are tracked
        assert(nodes.all { it.player == P_CONCRETE || sg.getAvailableActions(it).size <= 1 })
        val initialNode = sg.initialNode
        assert(
            initialNode is AbstractionChoiceNode && initialNode.s == createState(A to 0, B to 0, C to 0)
        )

        checkAndViz(abstraction)
    }

    @Test
    fun explicit2VarsTest() {
        simpleSetup()
        val abstraction = explAbstractor.computeAbstraction(ExplPrec.of(listOf(A, B)))
        val sg = abstraction.game
        val initialNode = sg.initialNode
        assert(
            initialNode is AbstractionChoiceNode && initialNode.s == createState(A to 0, B to 0)
        )

        checkAndViz(abstraction)
    }

    @Test
    fun explicit1VarTest() {
        simpleSetup()
        val abstraction = explAbstractor.computeAbstraction(ExplPrec.of(listOf(A)))
        val sg = abstraction.game

        val initialNode = sg.initialNode
        assert(initialNode is AbstractionChoiceNode && initialNode.s == createState(A to 0))

        checkAndViz(abstraction)
    }


    @Test
    fun refinerTestExpl() {
        simpleSetup()

        val initPrec = ExplPrec.of(listOf(A))
        val abstraction = explAbstractor.computeAbstraction(initPrec, true)
        val (lower, upper) = computeValues(abstraction)

        val itpSolver = Z3SolverFactory.getInstance().createItpSolver()
        val traceChecker = ExprTraceBwBinItpChecker.create(fullInit.toExpr(), BoolExprs.True(), itpSolver)
        val refToPrec = ItpRefToExplPrec()
        val pivotSelectionStrategy = ReachableMostUncertain()
        val refiner = BestTransformerRefiner<ExplState, StmtAction, ExplPrec, ItpRefutation>(solver, {
            this.join(ExplPrec.of(ExprUtils.getVars(it)))
        }, pivotSelectionStrategy, true, traceChecker, refToPrec)

        val (newPrec, pivot) = refiner.refine(abstraction.game, upper.first, lower.first, upper.second, lower.second, initPrec)
        println(pivot)
        println(newPrec)

        assert(newPrec == ExplPrec.of(listOf(A, B))) //C can be ignored, but B is needed for more precise result
    }


}