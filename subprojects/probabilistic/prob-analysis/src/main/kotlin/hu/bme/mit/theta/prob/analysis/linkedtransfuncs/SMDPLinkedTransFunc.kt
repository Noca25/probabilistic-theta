package hu.bme.mit.theta.prob.analysis.linkedtransfuncs

import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.prob.analysis.jani.SMDPCommandAction
import hu.bme.mit.theta.prob.analysis.jani.SMDPState
import hu.bme.mit.theta.prob.analysis.jani.nextLocs

class SMDPLinkedTransFunc<S: ExprState, P: Prec>(
    val baseTransFunc: LinkedTransFunc<S, SMDPCommandAction, P>
): LinkedTransFunc<SMDPState<S>, SMDPCommandAction, P> {
    override fun getSuccStates(
        currState: SMDPState<S>,
        precondition: Expr<BoolType>,
        actions: List<SMDPCommandAction>,
        prec: P
    ): List<List<SMDPState<S>>> {
        val succDataStates = baseTransFunc.getSuccStates(currState.domainState, precondition, actions, prec)
        val nextSMDPStates = succDataStates.map {
            it.mapIndexed { idx: Int, dataState: S ->
                SMDPState(dataState, nextLocs(currState.locs, actions[idx].destination))
            }
        }
        return nextSMDPStates
    }
}