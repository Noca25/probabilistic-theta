package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.analysis.pred.PredState
import hu.bme.mit.theta.core.decl.Decl
import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.model.ImmutableValuation
import hu.bme.mit.theta.core.stmt.AssumeStmt
import hu.bme.mit.theta.core.stmt.Stmt
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.And
import hu.bme.mit.theta.core.type.inttype.IntExprs
import hu.bme.mit.theta.core.type.inttype.IntType
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommandLTS
import hu.bme.mit.theta.prob.analysis.toAction
import hu.bme.mit.theta.probabilistic.FiniteDistribution

fun createState(vararg v: Pair<VarDecl<IntType>, Int>): ExplState {
    val builder = ImmutableValuation.builder()
    for ((decl, value) in v) {
        builder.put(decl, IntExprs.Int(value))
    }
    return ExplState.of(builder.build())
}

fun createBoolState(vararg v: Pair<VarDecl<BoolType>, Boolean>): ExplState {
    val builder = ImmutableValuation.builder()
    for ((decl, value) in v) {
        builder.put(decl, BoolExprs.Bool(value))
    }
    return ExplState.of(builder.build())
}

fun createPredState(vararg v: Expr<BoolType>) =
    PredState.of(v.toList())

fun constrainRange(vararg constraints: Pair<VarDecl<IntType>, Pair<Int, Int>>): AssumeStmt {
    val expr = And(
        constraints.map {
            val (v, r) = it; val (l, u) = r
            And(
                IntExprs.Geq(v.ref, IntExprs.Int(l)),
                IntExprs.Leq(v.ref, IntExprs.Int(u))
            )
        }
    )
    return AssumeStmt.of(expr)
}

fun Expr<BoolType>.then(vararg results: Pair<Double, Stmt>) = ProbabilisticCommand<StmtAction>(
    this, FiniteDistribution(results.associate { it.second.toAction() to it.first })
)

fun <S: State, A: Action> MenuGameTransFuncResult<S, A>.extractStates() = this.succStates.map { it.transform { it.second } }

class SimpleProbLTS<S: ExprState>(private val commands: List<ProbabilisticCommand<StmtAction>>) :
    ProbabilisticCommandLTS<S, StmtAction> {
    override fun getAvailableCommands(state: S): Collection<ProbabilisticCommand<StmtAction>> {
        return commands//.filter { ExprUtils.simplify(it.guard, state) != False() }
    }
}

// Expr DSL
infix fun Decl<IntType>.lt(x: Int) = IntExprs.Lt(this.ref, IntExprs.Int(x))