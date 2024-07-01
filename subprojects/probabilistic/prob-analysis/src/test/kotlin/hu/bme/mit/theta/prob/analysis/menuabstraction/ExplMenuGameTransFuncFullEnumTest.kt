package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.stmt.Stmts
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.inttype.IntExprs.*
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

class ExplMenuGameTransFuncFullEnumTest {

    lateinit var solver: Solver
    lateinit var transFunc: MenuGameTransFunc<ExplState, StmtAction, ExplPrec>

    @Before
    fun initEach() {
        solver = Z3SolverFactory.getInstance().createSolver()
        transFunc = BasicMenuGameTransFunc(ExplLinkedTransFunc(0, solver), ::explCanBeDisabled) //ExplMenuGameTransFunc(0, solver)
    }

    @Test
    fun simpleDeterministicCommandFullEnumTest() {
        val A = Decls.Var("A", Int())

        val command = ProbabilisticCommand<StmtAction>(
            True(), dirac(
                Stmts.Assign(A, Add(A.ref, Int(1))).toAction()
            )
        )

        val stateA = createState(A to 0)

        // Checking that a simple deterministic assignment works
        val nexts1 = transFunc.getNextStates(stateA, command, ExplPrec.of(listOf(A))).extractStates()
        val expected1 = listOf(dirac(createState(A to 1)))
        assertEquals(expected1, nexts1)

        // Checking that projecting to the empty precision works
        val nexts2 = transFunc.getNextStates(stateA, command, ExplPrec.of(listOf())).extractStates()
        val expected2 = listOf(dirac(createState()))
        val expected2Top = listOf(dirac(ExplState.top()))
        assertEquals(expected2, nexts2)
        assertEquals(expected2Top, nexts2) // Checking that semantic equality with Top works for projected result

        // Checking the result with an irrelevant variable, for all possible non-empty projections
        val B = Decls.Var("B", Int())
        val stateAB = createState(A to 1, B to 2)
        val nexts3AB = transFunc.getNextStates(stateAB, command, ExplPrec.of(listOf(A, B))).extractStates()
        val nexts3A = transFunc.getNextStates(stateAB, command, ExplPrec.of(listOf(A))).extractStates()
        val nexts3B = transFunc.getNextStates(stateAB, command, ExplPrec.of(listOf(B))).extractStates()

        val expected3AB = listOf(dirac(createState(A to 2, B to 2)))
        val expected3A = listOf(dirac(createState(A to 2)))
        val expected3B = listOf(dirac(createState(B to 2)))

        assertEquals(expected3AB, nexts3AB)
        assertEquals(expected3A, nexts3A)
        assertEquals(expected3B, nexts3B)
    }

    @Test
    fun simpleProbCommandTest() {
        val A = Decls.Var("A", Int())

        val command = ProbabilisticCommand<StmtAction>(
            True(), FiniteDistribution(
                mapOf(
                    Stmts.Assign(A, Add(A.ref, Int(1))).toAction() to 0.2,
                    Stmts.Assign(A, Add(A.ref, Int(2))).toAction() to 0.8,
                )
            )
        )

        val stateA = createState(A to 0)

        val nexts1 = transFunc.getNextStates(stateA, command, ExplPrec.of(listOf(A))).extractStates()
        val expected1 = listOf(
            FiniteDistribution(createState(A to 1) to 0.2, createState(A to 2) to 0.8)
        )
        assertEquals(expected1, nexts1)
    }

    @Test
    fun simpleMultiResultDeterministicCommandFullEnumTest() {
        val A = Decls.Var("A", Bool())
        val B = Decls.Var("B", Bool())

        val command = ProbabilisticCommand<StmtAction>(
            True(), dirac(
                Stmts.Assign(A, B.ref).toAction()
            )
        )

        val state = createBoolState(A to true)
        val nexts = transFunc.getNextStates(state, command, ExplPrec.of(listOf(A, B))).extractStates()

        // As B is unknown in the current state, the next state will depend on which concrete state we choose
        // we only know that A and B will be the same
        val expected = setOf(
            dirac(createBoolState(A to true, B to true)),
            dirac(createBoolState(A to false, B to false))
        )
        // The order of the states in the next state lists does not matter, so we check equality of sets,
        // but we also want to make sure that the states are not duplicated in the originally returned list
        assertEquals(2, nexts.size)
        assertEquals(expected, nexts.toSet())
    }

    @Test
    fun guardTest() {
        val A = Decls.Var("A", Int())

        val command = ProbabilisticCommand<StmtAction>(
            Leq(A.ref, Int(2)), dirac(
                Stmts.Assign(A, Int(2)).toAction()
            )
        )
        val prec = ExplPrec.of(listOf(A))

        // The command is enabled in this abstract state
        val state1 = createState(A to 1)
        val res1 = transFunc.getNextStates(state1, command, prec)
        val nexts1 = res1.extractStates()
        val expected1 = listOf(dirac(createState(A to 2)))
        assertEquals(expected1, nexts1)
        assert(!res1.canBeDisabled)

        // The command in not enabled in this abstract state
        val state2 = createState(A to 3)
        val res2 = transFunc.getNextStates(state2, command, prec)
        val nexts2 = res2.extractStates()
        val expected2 = listOf<FiniteDistribution<ExplState>>()
        assertEquals(expected2, nexts2)
        assert(res2.canBeDisabled)

        // Enabledness of the command is unknown in this state
        val state3 = createState()
        val res3 = transFunc.getNextStates(state3, command, prec)
        val nexts3 = res3.extractStates()
        val expected3 = setOf(dirac(createState(A to 2)))
        assertEquals(expected3, nexts3.toSet())
        assert(res3.canBeDisabled)
    }

    /**
     * Checks whether the guard is correctly taken into account when computing the possible
     * next states if enabledness is not known.
     */
    @Test
    fun guardEffectTest() {
        val A = Decls.Var("A", Int())

        val command = ProbabilisticCommand<StmtAction>(
            Eq(A.ref, Int(2)), dirac(
                Stmts.Assign(A, Add(A.ref, Int(1))).toAction()
            )
        )
        val prec = ExplPrec.of(listOf(A))

        // Although enabledness is unknown, if the command is enabled, we know the next state precisely
        val state = createState()
        val res = transFunc.getNextStates(state, command, prec)
        val nexts = res.extractStates()
        val expected = setOf(dirac(createState(A to 3)))
        assertEquals(expected, nexts.toSet())
        assert(res.canBeDisabled)
    }


    @Test
    fun complexCommandTest() {
        val A = Decls.Var("A", Int())
        val B = Decls.Var("B", Int())
        val C = Decls.Var("C", Int())

        // Cases to cover:
        // - unknown guard satisfaction with guard affecting the result
        // - multiple non-bottom abstraction choices
        // - non-dirac distribution in the result
        // - simplification in guard works correctly

        //A = 0
        //B = 1
        //
        //[2 <= C+B <= 3]
        //0.2: A += C
        //0.8: A := 1

        val CplusB = Add(C.ref, B.ref)
        val guard = And(listOf(
            Geq(CplusB, Int(2)),
            Leq(CplusB, Int(3)),
            )
        )

        val command = ProbabilisticCommand<StmtAction>(
            guard, FiniteDistribution(
                Stmts.Assign(A, Add(A.ref, C.ref)).toAction() to 0.2,
                Stmts.Assign(A, Int(1)).toAction() to 0.8
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
}