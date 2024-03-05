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

    class BasicInitializer<N>(val isTarget: (N) -> Boolean): Initializer<N> {
        override fun getLowerInitialValue(n: N): Double {
            return if(isTarget(n)) 1.0 else 0.0
        }

        override fun getUpperInitialValue(n: N): Double {
            return 1.0
        }
    }

    // TODO: ?????????
    class MDPAlmostSurePropagatingInitializer<N, A>(
        val targetList: List<N>,
        val mdp: StochasticGame<N, A>,
        val optim: Goal
    ): Initializer<N> {
        val almostSureTargets: Set<N> =
            if(optim == Goal.MAX) almostSureMaxForMDP(mdp, targetList).toSet()
            else setOf()

        val nonTargetAbsorbingStates = mdp.getAllNodes().filter {
            n -> n !in targetList && mdp.getAvailableActions(n).all { a -> mdp.getResult(n, a).support.let { it.size == 1 && n in it } }
        }
        val almostSureNonTargetAbsorbing =
            if (optim == Goal.MIN) almostSureMaxForMDP(mdp, nonTargetAbsorbingStates).toSet()
            else setOf()
        val inNonTargetEndComponent =
            if(optim == Goal.MIN) computeMECs(mdp).filter { it.none { it in targetList } }.flatten()
            else setOf()
        // TODO: change this to the likely much more efficient Algorithm 46 in principles of model checking
        val zeroForMin = almostSureNonTargetAbsorbing.union(inNonTargetEndComponent)

        constructor(isTarget: (N)->Boolean, mdp: StochasticGame<N, A>, optim: Goal):
                this(mdp.getAllNodes().filter(isTarget), mdp, optim)

        override fun getLowerInitialValue(n: N): Double {
            if(optim == Goal.MAX)
                return if(n in almostSureTargets) 1.0 else 0.0
            else return 0.0
        }

        override fun getUpperInitialValue(n: N): Double {
            if (optim == Goal.MIN)
                return if(n in zeroForMin) 0.0 else 1.0
            else return 1.0
        }
    }
}