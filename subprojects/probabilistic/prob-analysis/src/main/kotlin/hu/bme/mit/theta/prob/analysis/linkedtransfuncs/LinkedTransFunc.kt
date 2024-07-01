package hu.bme.mit.theta.prob.analysis.linkedtransfuncs

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType

interface LinkedTransFunc<S: State, in A: Action, in P: Prec> {
    fun getSuccStates(currState: S, precondition: Expr<BoolType>, actions: List<A>, prec: P): List<List<S>>
}