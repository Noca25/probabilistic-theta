package hu.bme.mit.theta.analysis.live2reach

import hu.bme.mit.theta.analysis.LTS
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.stmt.AssignStmt
import hu.bme.mit.theta.core.stmt.Stmt
import hu.bme.mit.theta.core.stmt.Stmts
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.Type
import hu.bme.mit.theta.core.type.abstracttype.AbstractExprs
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.booltype.BoolType

class Live2SafeLTS<S : State, A : StmtAction>(
    val baseLTS: LTS<S, A>, modelVars: Collection<VarDecl<*>>,
    val canStore: (S) -> Boolean,
    val isTriggered: (S) -> Boolean,
    val replaceStmt: (A, Stmt) -> A
): LTS<S, A> {
    val storedVarMap = modelVars.associateWith { Decls.Var(it.name + "__stored", it.type) }
    val storeStmt = Stmts.NonDetStmt(listOf(Stmts.Skip(),
        Stmts.SequenceStmt(
            storedVarMap.map { (orig, stored) -> AssignStmt.create<Type>(stored, orig.ref) })
        )
    )
    val triggeredVar = Decls.Var("__l2r__triggered", Bool())
    val setTriggered = Stmts.Assign(triggeredVar, True())

    val targetExpr = And(
        storedVarMap.map { (orig, stored) -> AbstractExprs.Eq(orig.ref, stored.ref) }
        + listOf(triggeredVar.ref)
    )

    override fun getEnabledActionsFor(state: S): Collection<A> {
        return if(canStore(state)) {
            baseLTS.getEnabledActionsFor(state).map { action ->
                val stmts = action.stmts.toMutableList()
                if(isTriggered(state)) stmts.add(0, setTriggered)
                stmts.add(0, storeStmt)
                val newStmt = Stmts.SequenceStmt(stmts)

                replaceStmt(action, newStmt)
            }
        } else {
            if(isTriggered(state)) {
                baseLTS.getEnabledActionsFor(state).map {action ->
                    val stmts = action.stmts.toMutableList()
                    stmts.add(0, setTriggered)
                    val newStmt = Stmts.SequenceStmt(stmts)

                    replaceStmt(action, newStmt)
                }
            } else baseLTS.getEnabledActionsFor(state)
        }
    }

    fun extendInitExpr(origInit: Expr<BoolType>): Expr<BoolType> {
        return And(
            origInit,
            Not(triggeredVar.ref),
            And(storedVarMap.map { (orig, stored) -> AbstractExprs.Eq(orig.ref, stored.ref) })
        )
    }
}