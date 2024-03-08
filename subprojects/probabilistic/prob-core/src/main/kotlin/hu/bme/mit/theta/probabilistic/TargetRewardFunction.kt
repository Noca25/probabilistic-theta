package hu.bme.mit.theta.probabilistic

import hu.bme.mit.theta.probabilistic.gamesolvers.almostSureMaxForMDP
import hu.bme.mit.theta.probabilistic.gamesolvers.computeMECs

class TargetRewardFunction<N, A>(
    val isTarget: (N) -> Boolean,
): GameRewardFunction<N, A> {
    override fun getStateReward(n: N) = 0.0

    override fun getEdgeReward(source: N, action: A, target: N) =
        // This way it should work for both self-looped and "proper" absorbing states
        if(!isTarget(source) && isTarget(target)) 1.0 else 0.0
    interface Initializer<N> {
        fun getLowerInitialValue(n: N): Double
        fun getUpperInitialValue(n: N): Double
    }
}