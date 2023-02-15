package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.stmt.Stmts
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.inttype.IntExprs
import hu.bme.mit.theta.probabilistic.EnumeratedDistribution
import hu.bme.mit.theta.probabilistic.EnumeratedDistribution.Companion.dirac
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.z3.Z3SolverFactory
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class ExplProbabilisticCommandTransFuncMultiEnumTest {
    lateinit var solver: Solver
    lateinit var transFunc: ExplProbabilisticCommandTransFunc

    val A = Decls.Var("A", IntExprs.Int())
    val B = Decls.Var("B", IntExprs.Int())
    val C = Decls.Var("C", IntExprs.Int())

    @Before
    fun initEach() {
        solver = Z3SolverFactory.getInstance().createSolver()
        transFunc = ExplProbabilisticCommandTransFunc(2, solver)
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
            guard, EnumeratedDistribution(
                Stmts.Assign(A, IntExprs.Add(A.ref, C.ref)).toAction() to 0.2,
                Stmts.Assign(A, IntExprs.Int(1)).toAction() to 0.8
            )
        )
        val state = createState(A to 0, B to 1)
        val prec = ExplPrec.of(listOf(A, B))

        val nexts = transFunc.getNextStates(state, command, prec)

        val expected = setOf(
            dirac(ExplState.bottom()),
            dirac(createState(A to 1, B to 1)),
            EnumeratedDistribution(
                createState(A to 2, B to 1) to 0.2,
                createState(A to 1, B to 1) to 0.8
            )
        )

        Assert.assertEquals(expected, nexts.toSet())
    }

    @Test
    fun infiniteResultCommandMaxEnum2Test() {
        val command = ProbabilisticCommand<StmtAction>(
            BoolExprs.True(), dirac(
                Stmts.Havoc(A).toAction())
        )
        val state = createState(A to 0, B to 1)
        val prec = ExplPrec.of(listOf(A, B))

        val nexts = transFunc.getNextStates(state, command, prec)
        val expected = setOf(
            dirac(createState( B to 1))
        )
        Assert.assertEquals(expected, nexts.toSet())
    }
}