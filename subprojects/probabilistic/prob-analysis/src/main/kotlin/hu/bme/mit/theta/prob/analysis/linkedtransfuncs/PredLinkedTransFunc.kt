package hu.bme.mit.theta.prob.analysis.linkedtransfuncs

import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.analysis.pred.PredPrec
import hu.bme.mit.theta.analysis.pred.PredState
import hu.bme.mit.theta.core.decl.Decl
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolExprs.True
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.And
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.Not
import hu.bme.mit.theta.core.utils.PathUtils.unfold
import hu.bme.mit.theta.core.utils.StmtUtils
import hu.bme.mit.theta.core.utils.indexings.VarIndexing
import hu.bme.mit.theta.core.utils.indexings.VarIndexingFactory
import hu.bme.mit.theta.prob.analysis.addNewIndexToNonZero
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.utils.WithPushPop

class PredLinkedTransFunc(val solver: Solver): LinkedTransFunc<PredState, StmtAction, PredPrec> {
    private companion object {
        var instanceCounter = 0
    }
    private val litPrefix = "__" + javaClass.simpleName + "_" + (instanceCounter++) + "_"
    private val actLits = arrayListOf<Decl<BoolType>>()

    private fun generateActivationLiterals(n: Int) {
        while(actLits.size < n) {
            actLits.add(Decls.Const(litPrefix + actLits.size, BoolExprs.Bool()))
        }
    }

    override fun getSuccStates(currState: PredState, precondition: Expr<BoolType>, actions: List<StmtAction>, prec: PredPrec): List<List<PredState>> {
        val results = arrayListOf<List<PredState>>()

        var i = 0
        val auxIndex = hashMapOf<Expr<BoolType>, Int>()
        val targetIndexing = hashMapOf<Expr<BoolType>, VarIndexing>()
        generateActivationLiterals(prec.preds.size * actions.size)

        fun getActLit(resultIdx: Int, predIdx: Int) = actLits[resultIdx * prec.preds.size + predIdx]

        val resultExprs = actions.map {
            val stmtUnfoldResult = StmtUtils.toExpr(it.stmts, VarIndexingFactory.indexing(0))

            val expr = unfold(And(stmtUnfoldResult.exprs), 0)
            val multiIndexedExpr = addNewIndexToNonZero(expr,i)
            targetIndexing[multiIndexedExpr] = stmtUnfoldResult.indexing

            // Next state predicates with activation literals
            val activationExprs = arrayListOf<Expr<BoolType>>()
            for ((predIdx, pred) in prec.preds.withIndex()) {
                val actLit = getActLit(i, predIdx)
                val indexedPred = addNewIndexToNonZero(unfold(pred, stmtUnfoldResult.indexing), i)
                activationExprs.add(BoolExprs.Iff(indexedPred, actLit.ref))
            }

            val resultExpr = And(multiIndexedExpr, And(activationExprs))
            auxIndex[resultExpr] = i
            i++
            return@map resultExpr
        }

        WithPushPop(solver).use {
            solver.add(unfold(And(currState.toExpr(), precondition), 0))
            solver.add(And(resultExprs))

            while (solver.check().isSat) {
                val model = solver.model
                val feedbackList = arrayListOf<Expr<BoolType>>()
                val result = resultExprs.map { expr ->
                    val aux = auxIndex[expr]!!
                    val preds = arrayListOf<Expr<BoolType>>()
                    for ((predIdx, pred) in prec.preds.withIndex()) {
                        val actLit = getActLit(aux, predIdx)
                        val truthValue = model.eval(actLit).get()
                        preds.add(
                            if (truthValue == True()) pred else prec.negate(pred)
                        )
                        feedbackList.add(
                            if (truthValue == True()) actLit.ref else Not(actLit.ref)
                        )
                    }
                    val newState = PredState.of(preds)
                    newState
                }

                val feedback = Not(And(feedbackList))
                solver.add(feedback)

                results.add(result)
            }
        }

        return results
    }
}