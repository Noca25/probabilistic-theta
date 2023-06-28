package hu.bme.mit.theta.analysis.expl

import com.google.common.collect.ImmutableList
import hu.bme.mit.theta.core.decl.Decl
import hu.bme.mit.theta.core.model.MutableValuation
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.core.stmt.*
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.LitExpr
import hu.bme.mit.theta.core.type.abstracttype.EqExpr
import hu.bme.mit.theta.core.type.abstracttype.NeqExpr
import hu.bme.mit.theta.core.type.anytype.RefExpr
import hu.bme.mit.theta.core.type.booltype.BoolLitExpr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.NotExpr
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs
import hu.bme.mit.theta.core.utils.ExprUtils
import java.util.stream.Collectors

class MultiValuationStmtApplier {
    data class ApplyResult(val status: Status, val valuations: List<MutableValuation>) {
        enum class Status {
            FAILURE, SUCCESS, BOTTOM
        }
    }

    private fun StmtApplier() {}

    fun apply(stmt: Stmt, valuations: List<MutableValuation>, approximate: Boolean): ApplyResult {
        return if (stmt is AssignStmt<*>) {
            applyAssign(stmt, valuations, approximate)
        } else if (stmt is AssumeStmt) {
            applyAssume(stmt, valuations, approximate)
        } else if (stmt is HavocStmt<*>) {
            applyHavoc(stmt, valuations)
        } else if (stmt is SkipStmt) {
            applySkip(valuations)
        } else if (stmt is SequenceStmt) {
            applySequence(stmt, valuations, approximate)
        } else if (stmt is NonDetStmt) {
            applyNonDet(stmt, valuations, approximate)
        } else if (stmt is OrtStmt) {
            applyOrt(stmt, valuations, approximate)
        } else if (stmt is LoopStmt) {
            applyLoop(stmt, valuations, approximate)
        } else if (stmt is IfStmt) {
            applyIf(stmt, valuations, approximate)
        } else {
            throw UnsupportedOperationException("Unhandled statement: $stmt")
        }
    }

    private fun applyAssign(
        stmt: AssignStmt<*>, valuations: List<MutableValuation>,
        approximate: Boolean
    ): ApplyResult {
        val varDecl = stmt.varDecl
        val res = arrayListOf<MutableValuation>()
        var status = ApplyResult.Status.SUCCESS
        for(v in valuations) {
            val expr = ExprUtils.simplify(stmt.expr, v)
            if (expr is LitExpr<*>) {
                v.put(varDecl, expr)
                res.add(v)
            } else if (approximate) {
                v.remove(varDecl)
                res.add(v)
            } else {
                status = ApplyResult.Status.FAILURE
            }
        }
        return ApplyResult(status, res)
    }

    private fun applyAssume(
        stmt: AssumeStmt, valuations: List<MutableValuation>,
        approximate: Boolean
    ): ApplyResult {
        val res = arrayListOf<MutableValuation>()
        var status = ApplyResult.Status.SUCCESS
        for(v in valuations) {
            val cond = ExprUtils.simplify(stmt.cond, v)
            if (cond is BoolLitExpr) {
                if (cond.value) {
                    res.add(v)
                }
            } else if (checkAssumeVarEqualsLit(cond, v)) {
                res.add(v)
            } else {
                if (approximate) {
                    TODO()
                    //ApplyResult.SUCCESS
                } else {
                    status = ApplyResult.Status.FAILURE
                }
            }
        }
        return ApplyResult(status, res)
    }

    // Helper function to evaluate assumptions of form [x = 1] or [not x != 1]
    private fun checkAssumeVarEqualsLit(cond: Expr<BoolType>, `val`: MutableValuation): Boolean {
        var ref: RefExpr<*>? = null
        var lit: LitExpr<*>? = null
        if (cond is EqExpr<*>) {
            val condEq = cond
            if (condEq.leftOp is RefExpr<*> && condEq.rightOp is LitExpr<*>) {
                ref = condEq.leftOp as RefExpr<*>
                lit = condEq.rightOp as LitExpr<*>
            }
            if (condEq.rightOp is RefExpr<*> && condEq.leftOp is LitExpr<*>) {
                ref = condEq.rightOp as RefExpr<*>
                lit = condEq.leftOp as LitExpr<*>
            }
        }
        if (cond is NotExpr) {
            val condNE = cond
            if (condNE.op is NeqExpr<*>) {
                val condNeq = condNE.op as NeqExpr<*>
                if (condNeq.leftOp is RefExpr<*> && condNeq.rightOp is LitExpr<*>) {
                    ref = condNeq.leftOp as RefExpr<*>
                    lit = condNeq.rightOp as LitExpr<*>
                }
                if (condNeq.rightOp is RefExpr<*> && condNeq.leftOp is LitExpr<*>) {
                    ref = condNeq.rightOp as RefExpr<*>
                    lit = condNeq.leftOp as LitExpr<*>
                }
            }
        }
        if (ref != null && lit != null) {
            `val`.put(ref.decl, lit)
            return true
        }
        return false
    }

    private fun applyHavoc(stmt: HavocStmt<*>, valuations: List<MutableValuation>): ApplyResult {
        //TODO: constrained havoc
        val res = arrayListOf<MutableValuation>()
        for (v in valuations) {
            val varDecl = stmt.varDecl
            v.remove(varDecl)
            res.add(v)
        }
        return ApplyResult(ApplyResult.Status.SUCCESS, res)
    }

    private fun applySkip(valuations: List<MutableValuation>): ApplyResult {
        return ApplyResult(ApplyResult.Status.SUCCESS, valuations)
    }

    private fun applySequence(
        stmt: SequenceStmt, valuations: List<MutableValuation>,
        approximate: Boolean
    ): ApplyResult {
        val results = arrayListOf<MutableValuation>()
        VAL_LOOP@for (v in valuations) {
            var subresults = listOf(v)
            for (subStmt in stmt.stmts) {
                val res = apply(subStmt, subresults, approximate)
                if (res.status == ApplyResult.Status.FAILURE)
                    return ApplyResult(ApplyResult.Status.FAILURE, listOf())
                else if (res.status == ApplyResult.Status.SUCCESS)
                    subresults = res.valuations
                else if (res.status == ApplyResult.Status.BOTTOM)
                    continue@VAL_LOOP
            }
            results.addAll(subresults)
        }
        return ApplyResult(ApplyResult.Status.SUCCESS, results)
    }

    private fun applyLoop(
        stmt: LoopStmt, valuations: List<MutableValuation>,
        approximate: Boolean
    ): ApplyResult {
        throw UnsupportedOperationException(String.format("Loop statement %s was not unrolled", stmt))
    }

    private fun applyNonDet(
        stmt: NonDetStmt, valuations: List<MutableValuation>,
        approximate: Boolean
    ): ApplyResult {
        val res = arrayListOf<MutableValuation>()
        var status = ApplyResult.Status.SUCCESS

        for (v in valuations) {
            var successIndex = -1
            for (i in stmt.stmts.indices) {
                val subVal = MutableValuation.copyOf(v)
                val subres = apply(stmt.stmts[i], listOf(subVal), approximate)
                if (subres.status == ApplyResult.Status.FAILURE)
                    status = ApplyResult.Status.FAILURE
                if (subres.status == ApplyResult.Status.SUCCESS) {
                    res.addAll(subres.valuations)
                    if (successIndex == -1) successIndex = i
                }
            }
        }
        return if (res.size == 0) {
            ApplyResult(ApplyResult.Status.BOTTOM, res)
        } else if (approximate) {
            TODO("Decide whether this should mean an approximation per input valuation or overall")
        } else {
            ApplyResult(status, res)
        }
    }

    private fun applyIf(
        stmt: IfStmt, valuations: List<MutableValuation>,
        approximate: Boolean
    ): ApplyResult {
        val res = arrayListOf<MutableValuation>()
        for(v in valuations) {
            val cond = ExprUtils.simplify(stmt.cond, v)
            if (cond is BoolLitExpr) {
                val subres = if (cond.value) {
                    apply(stmt.then, listOf(v), approximate)
                } else {
                    apply(stmt.elze, listOf(v), approximate)
                }
                if (subres.status == ApplyResult.Status.SUCCESS)
                    res.addAll(subres.valuations)
                else if(subres.status == ApplyResult.Status.FAILURE)
                    return ApplyResult(ApplyResult.Status.FAILURE, listOf())
            } else {
                TODO("handle this case, if it should be handled at all")
//                val thenVal = MutableValuation.copyOf(valuations)
//                val elzeVal = MutableValuation.copyOf(valuations)
//                val thenResult = apply(stmt.then, thenVal, approximate)
//                val elzeResult = apply(stmt.elze, elzeVal, approximate)
//                if (thenResult == ApplyResult.FAILURE || elzeResult == ApplyResult.FAILURE) {
//                    return ApplyResult.FAILURE
//                }
//                if (thenResult == ApplyResult.BOTTOM && elzeResult == ApplyResult.BOTTOM) {
//                    return ApplyResult.BOTTOM
//                }
//                if (thenResult == ApplyResult.SUCCESS && elzeResult == ApplyResult.BOTTOM) {
//                    val seq = SequenceStmt.of(ImmutableList.of(AssumeStmt.of(cond), stmt.then))
//                    return apply(seq, valuations, approximate)
//                }
//                if (thenResult == ApplyResult.BOTTOM && elzeResult == ApplyResult.SUCCESS) {
//                    val seq = SequenceStmt.of(ImmutableList.of(AssumeStmt.of(SmartBoolExprs.Not(cond)), stmt.elze))
//                    return apply(seq, valuations, approximate)
//                }
//                if (approximate) {
//                    apply(stmt.then, valuations, approximate)
//                    val toRemove = valuations.decls.stream()
//                        .filter { it: Decl<*>? ->
//                            valuations.eval(
//                                it
//                            ) != elzeVal.eval(it)
//                        }
//                        .collect(Collectors.toSet())
//                    for (decl in toRemove) valuations.remove(decl)
//                    ApplyResult.SUCCESS
//                } else {
//                    ApplyResult.FAILURE
//                }
            }
        }
        return ApplyResult(ApplyResult.Status.SUCCESS, res)
    }

    private fun applyOrt(
        stmt: OrtStmt, valuations: List<MutableValuation>,
        approximate: Boolean
    ): ApplyResult {
        throw UnsupportedOperationException()
    }
}