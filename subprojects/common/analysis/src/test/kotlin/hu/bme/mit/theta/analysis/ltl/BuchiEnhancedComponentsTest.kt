package hu.bme.mit.theta.analysis.ltl

import hu.bme.mit.theta.analysis.buchi.AutomatonBuilder
import hu.bme.mit.theta.analysis.buchi.BuchiAutomaton
import hu.bme.mit.theta.analysis.unit.UnitPrec
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.stmt.NonDetStmt
import hu.bme.mit.theta.core.stmt.SkipStmt
import hu.bme.mit.theta.core.stmt.Stmts
import hu.bme.mit.theta.core.stmt.Stmts.Assign
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.inttype.IntExprs
import hu.bme.mit.theta.core.type.inttype.IntExprs.*
import hu.bme.mit.theta.xsts.XSTS
import org.junit.Assert
import org.junit.Test
import java.io.FileInputStream

class BuchiEnhancedComponentsTest {

    private val x = Decls.Var("x", Int())
    private val y = Decls.Var("y", Int())

    val automaton = buildAutomaton()
    val xsts = XSTS(
        hashMapOf(), setOf(), NonDetStmt.of(listOf(SkipStmt.getInstance())),
        NonDetStmt.of(listOf(
            Assign(x, Add(x.ref, Int(1))),
            Assign(y, Add(y.ref, Int(1)))
        )),
        NonDetStmt.of(listOf(SkipStmt.getInstance())),
        And(Eq(x.ref, Int(0)), Eq(y.ref, Int(0))),
        True()
    )

    private fun buildAutomaton(): BuchiAutomaton {
        val builder = AutomatonBuilder()
        builder.setAps(hashMapOf(
            "p" to Leq(x.ref, Int(3)),
            "q" to Geq(y.ref, Int(2))
        ))
        return builder.parseAutomaton(FileInputStream("src/test/resources/test.hoa"))
    }

    @Test
    fun initTest() {
        val initFunc = BuchiInitFunc<UnitPrec>(automaton)
        val initStates = initFunc.getInitStates(UnitPrec.getInstance())
        Assert.assertEquals(1, initStates.size)
        Assert.assertEquals(0, initStates.first().id)

//        val combinedInitFunc = BuchiEnhancedInitFunc<UnitPrec>(
//            TODO("move to another module so that xsts-analysis things are known")
//        )
    }

    fun ltsTest() {
    }
}