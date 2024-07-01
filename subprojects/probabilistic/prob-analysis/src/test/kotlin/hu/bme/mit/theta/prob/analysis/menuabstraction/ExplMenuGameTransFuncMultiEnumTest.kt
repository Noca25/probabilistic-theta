package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.stmt.Stmts
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.inttype.IntExprs
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.linkedtransfuncs.ExplLinkedTransFunc
import hu.bme.mit.theta.prob.analysis.toAction
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.FiniteDistribution.Companion.dirac
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.z3.Z3SolverFactory
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ExplMenuGameTransFuncMultiEnumTest {
    lateinit var solver: Solver
    lateinit var transFunc: MenuGameTransFunc<ExplState, StmtAction, ExplPrec>

    val A = Decls.Var("A", IntExprs.Int())
    val B = Decls.Var("B", IntExprs.Int())
    val C = Decls.Var("C", IntExprs.Int())

    @Before
    fun initEach() {
        solver = Z3SolverFactory.getInstance().createSolver()
        transFunc = BasicMenuGameTransFunc(ExplLinkedTransFunc(2, solver), ::explCanBeDisabled) //ExplMenuGameTransFunc(0, solver)
    }


    @Test
    fun complexCommandMaxEnum2Test() {
        val CplusB = IntExprs.Add(C.ref, B.ref)
        val guard = BoolExprs.And(
            listOf(
                IntExprs.Geq(CplusB, IntExprs.Int(2)),
                IntExprs.Leq(CplusB, IntExprs.Int(3)),
            )
        )

        val command = ProbabilisticCommand<StmtAction>(
            guard, FiniteDistribution(
                Stmts.Assign(A, IntExprs.Add(A.ref, C.ref)).toAction() to 0.2,
                Stmts.Assign(A, IntExprs.Int(1)).toAction() to 0.8
            )
        )
        val state = createState(A to 0, B to 1)
        val prec = ExplPrec.of(listOf(A, B))

        val res = transFunc.getNextStates(state, command, prec)
        val nexts = res.extractStates()

        val expected = setOf(
            dirac(createState(A to 1, B to 1)),
            FiniteDistribution(
                createState(A to 2, B to 1) to 0.2,
                createState(A to 1, B to 1) to 0.8
            )
        )

        assertEquals(expected, nexts.toSet())
        assert(res.canBeDisabled)
    }

    @Test
    fun infiniteResultCommandMaxEnum2Test() {
        val command = ProbabilisticCommand<StmtAction>(
            BoolExprs.True(), dirac(
                Stmts.Havoc(A).toAction())
        )
        val state = createState(A to 0, B to 1)
        val prec = ExplPrec.of(listOf(A, B))

        val res = transFunc.getNextStates(state, command, prec)
        val nexts = res.extractStates()
        val expected = setOf(
            dirac(createState( B to 1))
        )
        assertEquals(expected, nexts.toSet())
        assert(!res.canBeDisabled)
    }
}