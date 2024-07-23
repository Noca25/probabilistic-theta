package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.probabilistic.FiniteDistribution

interface MenuGameTransFunc<S : State, A : Action, P : Prec> {
    /**
     * Computes a list of possible next state distributions after executing the given command in the given abstract state.
     * The result is projected to the precision in the argument.
     */
    fun getNextStates(state: S, command: ProbabilisticCommand<A>, prec: P): MenuGameTransFuncResult<S, A>
}

data class MenuGameTransFuncResult<S : State, A : Action>(
    val succStates: List<FiniteDistribution<Pair<A, S>>>,
    val canBeDisabled: Boolean
)
