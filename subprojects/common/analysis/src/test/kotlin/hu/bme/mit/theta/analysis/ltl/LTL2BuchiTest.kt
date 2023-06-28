package hu.bme.mit.theta.analysis.ltl

import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.type.inttype.IntExprs
import hu.bme.mit.theta.core.type.inttype.IntExprs.*
import org.junit.Assert.*
import org.junit.Test

class LTL2BuchiTest{

    @Test
    fun test() {
        val A = Decls.Var("a", Int())
        val B = Decls.Var("b", Int())
        val formula = "G(F(a>2) or G(b<5))"
        LTL2Buchi().convert(formula, hashMapOf("a" to A, "b" to B), hashMapOf())
    }

}