package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.probabilistic.FiniteDistribution

sealed class MenuGameAction<S : State, A : Action>() {
    data class ChosenCommand<S : State, A : Action>(val command: ProbabilisticCommand<A>) : MenuGameAction<S, A>()
    data class AbstractionDecision<S : State, A : Action>(
        val result: FiniteDistribution<Pair<A, S>>
    ) : MenuGameAction<S, A>()

    class EnterTrap<S : State, A : Action>() : MenuGameAction<S, A>() {
        override fun toString(): String {
            return "ENTER TRAP"
        }
    }
}