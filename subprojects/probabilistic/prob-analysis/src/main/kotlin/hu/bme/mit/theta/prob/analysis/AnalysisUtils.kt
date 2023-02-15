package hu.bme.mit.theta.prob.analysis

import hu.bme.mit.theta.core.decl.IndexedConstDecl
import hu.bme.mit.theta.core.decl.MultiIndexedConstDecl
import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.model.ImmutableValuation
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.Type
import hu.bme.mit.theta.core.type.anytype.PrimeExpr
import hu.bme.mit.theta.core.type.anytype.RefExpr
import hu.bme.mit.theta.core.utils.indexings.VarIndexing

/**
 * Transforms all indexed const decls with non-zero index to a multi-indexed const decl with its first index
 * being the old index, and its second index being the newIndex parameter
 */
fun <T: Type> addNewIndexToNonZero(expr: Expr<T>, newIndex: Int): Expr<T> {
    when(expr) {
        is RefExpr -> {
            val decl = expr.decl
            if(decl is VarDecl)
                return expr
            else if(decl is IndexedConstDecl && decl.index != 0) {
                return decl.varDecl.getConstDecl(listOf(decl.index, newIndex)).ref
            } else return expr
        }
        is PrimeExpr -> {
            throw IllegalArgumentException(
                "addNewIndexToNonZero should be called on indexed const decls, not primed var decls"
            )
        }
        else -> {
            return expr.map { addNewIndexToNonZero(it, newIndex) }
        }
    }
}


/**
 * Transforms an expression with multi-indexed const decls to one with only simple indexed const decls by
 * keeping only the indexToKeep-th index of multi-indexed const decls.
 */
fun <T: Type> removeMultiIndex(expr: Expr<T>, indexToKeep: Int): Expr<T> {
    when(expr) {
        is RefExpr -> {
            val decl = expr.decl
            if(decl is VarDecl)
                return expr // Not primed variable reference, offset is not applied
            else if(decl is MultiIndexedConstDecl) {
                return decl.varDecl.getConstDecl(decl.indices[indexToKeep]).ref
            } else return expr
        }
        is PrimeExpr -> {
            throw IllegalArgumentException(
                "removeMultiIndex should be called on (multi)indexed const decls, not primed var decls"
            )
        }
        else -> {
            return expr.map { removeMultiIndex(it, indexToKeep) }
        }
    }
}

/**
 * Extracts the value of each var decl in varDecls from a valuation of
 * multi-indexed and indexed constants based on a first index specified by an indexing
 * and an auxiliary second index specified by an integer.
 * If ignorAuxForZero is true, then variables with firstIndex 0 do not get
 * a second index.
 */
fun extractMultiIndexValuation(
    model: Valuation,
    varDecls: Collection<VarDecl<*>>,
    firstIndex: VarIndexing,
    auxIndex: Int,
    ignoreAuxForZero: Boolean
): Valuation {
    val builder = ImmutableValuation.builder()
    for (varDecl in varDecls) {
        val firstIndex: Int = firstIndex[varDecl]
        val constDecl = if(ignoreAuxForZero && firstIndex == 0) {
            varDecl.getConstDecl(0)
        } else {
            varDecl.getConstDecl(listOf(firstIndex, auxIndex))
        }
        val eval = model.eval(constDecl)
        if (eval.isPresent) {
            builder.put(varDecl, eval.get())
        }
    }
    return builder.build()
}