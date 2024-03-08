package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.InitFunc
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.common.visualization.writer.GraphvizWriter
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.stmt.Stmts.Assign
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.inttype.IntExprs
import hu.bme.mit.theta.core.type.inttype.IntExprs.*
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.solver.z3.Z3SolverFactory
import org.junit.Test

class MenuGameAbstractorTest {

    val A = Decls.Var("A", Int())
    val B = Decls.Var("B", Int())
    val C = Decls.Var("C", Int())
    val fullInit = createState(A to 0, B to 0, C to 0)
    val init = InitFunc<ExplState, ExplPrec> { prec -> listOf(prec.createState(fullInit)) }
    val solver = Z3SolverFactory.getInstance().createSolver()
    lateinit var lts: SimpleProbLTS
    lateinit var transFunc: ExplProbabilisticCommandTransFunc
    lateinit var abstractor: MenuGameAbstractor<ExplState, StmtAction, ExplPrec>

    class SimpleProbLTS(private val commands: List<ProbabilisticCommand<StmtAction>>) :
        ProbabilisticCommandLTS<ExplState, StmtAction> {
        override fun getAvailableCommands(state: ExplState): Collection<ProbabilisticCommand<StmtAction>> {
            return commands.filter {
                ExprUtils.simplify(it.guard, state) != False()
            }
        }
        override fun canFail(state: ExplState, command: ProbabilisticCommand<StmtAction>): Boolean {
            TODO("Should not be used by the abstractor for now anyway")
        }

    }

    private fun simpleSetup() {
        val commands = listOf(
            And(Lt(A.ref, Int(2)), Lt(B.ref, Int(3))).then(
                0.8 to Assign(A, IntExprs.Add(A.ref, Int(1))),
                0.2 to Assign(B, IntExprs.Add(B.ref, Int(1)))
            ),
            Lt(C.ref, Int(3)).then(1.0 to Assign(C, IntExprs.Add(C.ref, Int(1))))
        )
        lts = SimpleProbLTS(commands)
        transFunc = ExplProbabilisticCommandTransFunc(0, solver)
        val abstractor = MenuGameAbstractor(
            lts, init, transFunc, TODO(), TODO()
        )
    }

    @Test
    fun explicitAllVarsAbstractionTest() {
        simpleSetup()
        val sg = abstractor.computeAbstraction(ExplPrec.of(listOf(A, B, C)))
        val nodes = sg.getAllNodes()

        // There cannot be any abstraction choice as all vars are tracked
        assert(nodes.all { it.player ==  P_CONCRETE || sg.getAvailableActions(it).size == 1})
        val initialNode = sg.initialNode
        assert(
            initialNode is MenuGameAbstractor.MenuGameNode.StateNode &&
                    initialNode.s == createState(A to 0, B to 0, C to 0)
        )

        // checked manually
        // TODO: some more automatic checks
        val viz = GraphvizWriter.getInstance().writeString(
            sg.materialize().first.visualize()
        )
    }

    @Test
    fun explicit2VarsTest() {
        simpleSetup()
        val sg = abstractor.computeAbstraction(ExplPrec.of(listOf(A, B)))
        val initialNode = sg.initialNode
        assert(
            initialNode is MenuGameAbstractor.MenuGameNode.StateNode &&
                    initialNode.s == createState(A to 0, B to 0)
        )

        // checked manually
        // TODO: some more automatic checks
        val viz = GraphvizWriter.getInstance().writeString(
            sg.materialize().first.visualize()
        )
    }

    @Test
    fun explicit1VarTest() {
        val sg = abstractor.computeAbstraction(ExplPrec.of(listOf(A)))

        val initialNode = sg.initialNode
        assert(initialNode is MenuGameAbstractor.MenuGameNode.StateNode && initialNode.s == createState(A to 0))

        // checked manually
        // TODO: some more automatic checks
        val viz = GraphvizWriter.getInstance().writeString(
            sg.materialize().first.visualize()
        )
    }
}