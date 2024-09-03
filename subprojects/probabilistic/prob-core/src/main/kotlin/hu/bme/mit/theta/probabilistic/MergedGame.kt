package hu.bme.mit.theta.probabilistic

import hu.bme.mit.theta.probabilistic.gamesolvers.computeMECs
import java.util.*

class MergedGame<N, A>(
    val initNode: MergedNode<N,A>,
    val nodes: List<MergedNode<N,A>>
) : StochasticGame<MergedNode<N,A>, MergedEdge<N,A>> {
    override val initialNode: MergedNode<N,A>
        get() = initNode

    override fun getAllNodes(): Collection<MergedNode<N,A>> = nodes

    override fun getPlayer(node: MergedNode<N,A>): Int = 0
    override fun getResult(node: MergedNode<N,A>, action: MergedEdge<N,A>): FiniteDistribution<MergedNode<N,A>> = action.res

    override fun getAvailableActions(node: MergedNode<N,A>): Collection<MergedEdge<N,A>> = node.edges
}

class MergedNode<N, A>(val origNodes: Set<N>, val reward: Double) {
    companion object {
        var nextNodeId = 0
    }

    val id = nextNodeId++
    val edges = arrayListOf<MergedEdge<N, A>>()
    override fun hashCode(): Int {
        return Objects.hashCode(this.id)
    }
}

class MergedEdge<N, A>(
    val res: FiniteDistribution<MergedNode<N, A>>,
    val reward: Map<MergedNode<N, A>, Double>,
    val origin: Pair<N, A>
)

class MergedRewardFunction<N, A> : GameRewardFunction<MergedNode<N,A>, MergedEdge<N,A>> {
    override fun getStateReward(n: MergedNode<N,A>): Double {
        return n.reward
    }

    override fun getEdgeReward(source: MergedNode<N,A>, action: MergedEdge<N,A>, target: MergedNode<N,A>): Double {
        return action.reward[target] ?: 0.0
    }
}


fun <N, A> mergeMECs(game: StochasticGame<N, A>, rewardFunction: GameRewardFunction<N, A>):
        Pair<MergedGame<N, A>, Map<N, MergedNode<N, A>>> {
    val initNode = game.initialNode
    val nodes = game.getAllNodes()
    val mecs = computeMECs(game)

    // creating nodes of the merged game
    val mergedGameMap = hashMapOf<N, MergedNode<N, A>>()
    val mergedGameNodes = arrayListOf<MergedNode<N, A>>()
    val remainingNodes = HashSet(nodes)
    for (mec in mecs) {
        val mergedNode = MergedNode<N, A>(mec, 0.0)
        for (n in mec) {
            require(rewardFunction.getStateReward(n) == 0.0) {
                "Infinite reward cycle found, solution with BVI not supported yet"
            }
            mergedGameMap[n] = mergedNode
        }
        mergedGameNodes.add(mergedNode)
        remainingNodes.removeAll(mec)
    }
    for (n in remainingNodes) {
        val mergedNode = MergedNode<N, A>(setOf(n), rewardFunction.getStateReward(n))
        mergedGameMap[n] = mergedNode
        mergedGameNodes.add(mergedNode)
    }

    // creating edges of the merged game
    for (node in mergedGameNodes) {
        for (origNode in node.origNodes) {
            for (action in game.getAvailableActions(origNode)) {
                val res = game.getResult(origNode, action)
                if(res.support.any { it !in node.origNodes }) {
                    val reward = hashMapOf<MergedNode<N, A>, Double>()
                    for (n in res.support) {
                        // TODO: deal with overwriting and non-zero rewards in multiple-state ECs (at least throw an exception)
                        if(n !in node.origNodes) {
                            reward[mergedGameMap[n]!!] = rewardFunction(origNode, action, n)
                        }
                    }
                    node.edges.add(
                        MergedEdge(res.transform { mergedGameMap[it]!! }, reward, Pair(origNode, action))
                    )
                }
            }
        }
    }
    val mergedInit = mergedGameMap[initNode]!!
    val mergedGame = MergedGame(mergedInit, mergedGameNodes)

    return mergedGame to mergedGameMap
}

