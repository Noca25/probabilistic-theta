package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.probabilistic.GameRewardFunction

class MenuGameLowerRewardFunc<S: State, A: Action> : GameRewardFunction<MenuGameNode<S, A>, MenuGameAction<S, A>> {
    override fun getStateReward(n: MenuGameNode<S, A>): Double {
        return when (n) {
            is MenuGameNode.StateNode -> n.minReward.toDouble()
            is MenuGameNode.ResultNode -> 0.0
            is MenuGameNode.TrapNode -> 0.0
        }
    }

    override fun getEdgeReward(
        source: MenuGameNode<S, A>,
        action: MenuGameAction<S, A>,
        target: MenuGameNode<S, A>
    ): Double {
        return 0.0 // for now
    }
}