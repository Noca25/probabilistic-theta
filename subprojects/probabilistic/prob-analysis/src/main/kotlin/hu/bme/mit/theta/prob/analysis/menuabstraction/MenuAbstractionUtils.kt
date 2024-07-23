package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.pred.PredState
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.core.utils.PathUtils
import hu.bme.mit.theta.prob.analysis.jani.SMDPState
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.utils.WithPushPop

fun explCanBeDisabled(s: ExplState, guard: Expr<BoolType>): Boolean {
    return !ExprUtils.simplify(guard, s).equals(True())
}

fun explMaySatisfy(s: ExplState, expr: Expr<BoolType>): Boolean {
    return !ExprUtils.simplify(expr, s).equals(False())
}

fun explMustSatisfy(s: ExplState, expr: Expr<BoolType>): Boolean {
    return ExprUtils.simplify(expr, s).equals(True())
}

fun predCanBeDisabled(s: PredState, guard: Expr<BoolType>, solver: Solver): Boolean {
    WithPushPop(solver).use {
        solver.add(PathUtils.unfold(s.toExpr(), 0))
        solver.add(PathUtils.unfold(Not(guard), 0))
        return solver.check().isSat
    }
}

fun predCanBeDisabled(solver: Solver) = fun (s: PredState, guard: Expr<BoolType>): Boolean {
    return predCanBeDisabled(s, guard, solver)
}

fun predMaySatisfy(s: PredState, expr: Expr<BoolType>, solver: Solver): Boolean {
    WithPushPop(solver).use {
        solver.add(PathUtils.unfold(s.toExpr(), 0))
        solver.add(PathUtils.unfold(expr, 0))
        return solver.check().isSat
    }
}

fun predMaySatisfy(solver: Solver) = fun (s: PredState, expr: Expr<BoolType>): Boolean {
    return predMaySatisfy(s, expr, solver)
}

fun predMustSatisfy(s: PredState, expr: Expr<BoolType>, solver: Solver): Boolean {
    WithPushPop(solver).use {
        solver.add(PathUtils.unfold(s.toExpr(), 0))
        solver.add(PathUtils.unfold(Not(expr), 0))
        return solver.check().isUnsat
    }
}

fun predMustSatisfy(solver: Solver) = fun (s: PredState, expr: Expr<BoolType>): Boolean {
    return predMustSatisfy(s, expr, solver)
}

fun <S: ExprState> smdpCanBeDisabled(domainCanBeDisabled: (s: S, guard: Expr<BoolType>)-> Boolean) =
    fun (s: SMDPState<S>, guard: Expr<BoolType>): Boolean = domainCanBeDisabled(s.domainState, guard)

fun <S: ExprState> smdpMaySatisfy(domainMaySatisfy: (s: S, expr: Expr<BoolType>)-> Boolean) =
    fun (s: SMDPState<S>, expr: Expr<BoolType>): Boolean = domainMaySatisfy(s.domainState, expr)

fun <S: ExprState> smdpMustSatisfy(domainMustSatisfy: (s: S, expr: Expr<BoolType>)-> Boolean) =
    fun (s: SMDPState<S>, expr: Expr<BoolType>): Boolean = domainMustSatisfy(s.domainState, expr)