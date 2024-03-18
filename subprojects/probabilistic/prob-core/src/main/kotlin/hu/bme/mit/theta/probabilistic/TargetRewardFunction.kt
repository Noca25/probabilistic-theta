package hu.bme.mit.theta.probabilistic

class TargetRewardFunction<N, A>(
    val isTarget: (N) -> Boolean,
): GameRewardFunction<N, A> {
    override fun getStateReward(n: N) = if(isTarget(n)) 1.0 else 0.0

    override fun getEdgeReward(source: N, action: A, target: N) =
        // This way it should work for both self-looped and "proper" absorbing states
        0.0//if(!isTarget(source) && isTarget(target)) 1.0 else 0.0
}