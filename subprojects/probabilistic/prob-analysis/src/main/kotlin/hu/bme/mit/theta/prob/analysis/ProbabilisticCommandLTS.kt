package hu.bme.mit.theta.prob.analysis

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.State

interface ProbabilisticCommandLTS<S: State, A: Action> {
    fun getAvailableCommands(state: S): Collection<ProbabilisticCommand<A>>
}