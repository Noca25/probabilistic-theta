package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.linkedtransfuncs.LinkedTransFunc
import hu.bme.mit.theta.probabilistic.FiniteDistribution

class BasicMenuGameTransFunc<S : State, A : Action, P : Prec>(
    val baseTransFunc: LinkedTransFunc<S, A, P>,
    val canBeDisabled: (S, Expr<BoolType>) -> Boolean
) : MenuGameTransFunc<S, A, P> {
    override fun getNextStates(state: S, command: ProbabilisticCommand<A>, prec: P): MenuGameTransFuncResult<S, A> {

        val effectDistr = command.result.pmf.entries
        val actions = effectDistr.map { it.key }
        val probs = effectDistr.map { it.value }
        val canTrap = canBeDisabled(state, command.guard)
        val succStates =
            baseTransFunc.getSuccStates(state, command.guard, actions, prec).map {
                FiniteDistribution((actions.zip(it)).zip(probs).toMap())
            }


        return MenuGameTransFuncResult(succStates, canTrap)
    }

}