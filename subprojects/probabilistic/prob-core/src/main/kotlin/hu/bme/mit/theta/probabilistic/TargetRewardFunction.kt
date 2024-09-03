package hu.bme.mit.theta.probabilistic

class TargetRewardFunction<N, A>(
    val isTarget: (N) -> Boolean,
): GameRewardFunction<N, A> {
    override fun getStateReward(n: N) = if(isTarget(n)) 1.0 else 0.0

    override fun getEdgeReward(source: N, action: A, target: N) =
        0.0
}