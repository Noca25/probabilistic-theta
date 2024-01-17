package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.*
import java.util.Objects

/**
 * Bounded Value Iteration solver for "stochastic games" with a single goal, which are actually MDPs.
 * Uses MEC merging to make the upper bound converge, which does not work for real SGs.
 */
class MDPBVISolver<N, A>(
    val tolerance: Double,
    val rewardFunction: GameRewardFunction<N, A>,
    val upperInit: Double = 1.0
) : StochasticGameSolver<N, A> {

    companion object {
        var nextNodeId = 0L
    }

    private inner class MergedNode(val origNodes: List<N>, val reward: Double) {
        val id = nextNodeId++
        val edges = arrayListOf<MergedEdge>()
        override fun hashCode(): Int {
            return Objects.hashCode(this.id)
        }
    }

    private inner class MergedEdge(val res: FiniteDistribution<MergedNode>, val reward: Map<MergedNode, Double>)

    private inner class MergedGame(
        val initNode: MergedNode, val nodes: List<MergedNode>
    ) : StochasticGame<MergedNode, MergedEdge> {
        override val initialNode: MergedNode
            get() = initNode

        override fun getAllNodes(): Collection<MergedNode> = nodes

        override fun getPlayer(node: MergedNode): Int = 0
        override fun getResult(node: MergedNode, action: MergedEdge): FiniteDistribution<MergedNode> = action.res

        override fun getAvailableActions(node: MergedNode): Collection<MergedEdge> = node.edges
    }

    private inner class MergedRewardFunction : GameRewardFunction<MergedNode, MergedEdge> {
        override fun getStateReward(n: MergedNode): Double {
            return n.reward
        }

        override fun getEdgeReward(source: MergedNode, action: MergedEdge, target: MergedNode): Double {
            return action.reward[target] ?: 0.0
        }

        override fun getLowerInitialValue(n: MergedNode): Double {
            TODO("Not yet implemented")
        }

        override fun getUpperInitialValue(n: MergedNode): Double {
            TODO("Not yet implemented")
        }

    }

    override fun solve(analysisTask: AnalysisTask<N, A>): Map<N, Double> {
        require(analysisTask.discountFactor == 1.0) {
            "Discount not supported for BVI (yet?)"
        }

        val goal = analysisTask.goal
        val game = analysisTask.game
        val initNode = game.initialNode
        val initGoal = goal(game.getPlayer(initNode))
        val nodes = game.getAllNodes()
        val mecs = computeMECs(game)

        // creating nodes of the merged game
        val mergedGameMap = hashMapOf<N, MergedNode>()
        val mergedGameNodes = arrayListOf<MergedNode>()
        val remainingNodes = HashSet(nodes)
        for (mec in mecs) {
            val mergedNode = MergedNode(mec, 0.0)
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
            val mergedNode = MergedNode(listOf(n), rewardFunction.getStateReward(n))
            mergedGameMap[n] = mergedNode
            mergedGameNodes.add(mergedNode)
        }

        // creating edges of the merged game
        for (node in mergedGameNodes) {
            for (origNode in node.origNodes) {
                for (action in game.getAvailableActions(origNode)) {
                    val res = game.getResult(origNode, action)
                    if(res.support.any { it !in node.origNodes }) {
                        val reward = hashMapOf<MergedNode, Double>()
                        for (n in res.support) {
                            // TODO: deal with overwriting
                            if(n !in node.origNodes) {
                                reward[mergedGameMap[n]!!] = rewardFunction(origNode, action, n)
                            }
                        }
                        node.edges.add(
                            MergedEdge(res.transform { mergedGameMap[it]!! }, reward)
                        )
                    }
                }
            }
        }
        val mergedInit = mergedGameMap[initNode]!!

        val mergedGame = MergedGame(mergedInit, mergedGameNodes)
        val mergedRewardFunction = MergedRewardFunction()

        var lCurr = mergedGameNodes.associateWith { it.reward }
        var uCurr = mergedGameNodes.associateWith {
            if(it.edges.isEmpty()) it.reward
            else upperInit
        }
        do {
            lCurr = bellmanStep(mergedGame, lCurr, {initGoal}, mergedRewardFunction).result
            uCurr = bellmanStep(mergedGame, uCurr, {initGoal}, mergedRewardFunction).result
        } while (uCurr[mergedInit]!!-lCurr[mergedInit]!! > tolerance)

       return nodes.associateWith { uCurr[mergedGameMap[it]!!]!! }
    }
}