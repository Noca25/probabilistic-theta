package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.*
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Bounded Value Iteration solver for "stochastic games" with a single goal, which can be considered MDPs.
 * Uses MEC merging to make the upper bound converge, which does not work for general SGs.
 */
class MDPBVISolver<N, A>(
    val rewardFunction: GameRewardFunction<N, A>,
    val initializer: SGSolutionInitializer<N, A>,
    val threshold: Double
) : StochasticGameSolver<N, A> {


    companion object {
        var nextNodeId = 0L

        fun <N, A> supplier(threshold: Double) = {
                rewardFunction: GameRewardFunction<N, A>,
                initializer: SGSolutionInitializer<N, A> ->
            MDPBVISolver(rewardFunction, initializer, threshold)
        }

    }

    private inner class MergedNode(val origNodes: Set<N>, val reward: Double) {
        val id = nextNodeId++
        val edges = arrayListOf<MergedEdge>()
        override fun hashCode(): Int {
            return Objects.hashCode(this.id)
        }
    }

    private inner class MergedEdge(
        val res: FiniteDistribution<MergedNode>,
        val reward: Map<MergedNode, Double>,
        val origin: Pair<N, A>)

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
    }

    fun solveWithRange(analysisTask: AnalysisTask<N, A>): RangeSolution<N, A> {
        require(analysisTask.discountFactor == 1.0) {
            "Discount not supported for BVI (yet?)"
        }

        val goal = analysisTask.goal
        val game = analysisTask.game
        val initNode = game.initialNode
        val initGoal = goal(game.getPlayer(initNode))
        require(initGoal == Goal.MAX) {
            "Only maximization supported with BVI for now, merging might need to be adjusted for MIN, not tested yet"
        }
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
            val mergedNode = MergedNode(setOf(n), rewardFunction.getStateReward(n))
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
        val mergedRewardFunction = MergedRewardFunction()

        var lCurr = mergedGameNodes.associateWith {
            max(it.reward, it.origNodes.maxOf { initializer.initialLowerBound(it) })
        }
        var uCurr = mergedGameNodes.associateWith {
            if(it.edges.isEmpty()) min(it.reward, it.origNodes.maxOf { initializer.initialUpperBound(it) })
            else it.origNodes.maxOf { initializer.initialUpperBound(it) }
        }
        val unknownNodes = mergedGameNodes.filter { uCurr[it]!! - lCurr[it]!! > threshold }.toMutableList()

        val strategy = hashMapOf<MergedNode, MergedEdge>()
        do {
            lCurr = bellmanStep(mergedGame, lCurr, {initGoal}, mergedRewardFunction, unknownNodes = unknownNodes).result
            val uStep =
                bellmanStep(mergedGame, uCurr, { initGoal }, mergedRewardFunction, unknownNodes = unknownNodes)
            uCurr = uStep.result
            strategy.putAll(uStep.strategyUpdate!!)
            // TODO: it'd be more efficient to do this during the update
            unknownNodes.removeAll { uCurr[it]!!-lCurr[it]!! < threshold }
        } while (uCurr[mergedInit]!!-lCurr[mergedInit]!! > threshold)

        return RangeSolution(
            nodes.associateWith { lCurr[mergedGameMap[it]!!]!! },
            nodes.associateWith { uCurr[mergedGameMap[it]!!]!! },
            liftStrategy(game, strategy)
        )
    }

    private fun liftStrategy(origGame: StochasticGame<N, A>, mergedStrategy: Map<MergedNode, MergedEdge>): Map<N, A> {
        val originalStrategy = hashMapOf<N, A>()
        for ((mergedNode, chosenEdge) in mergedStrategy) {
            originalStrategy[chosenEdge.origin.first] = chosenEdge.origin.second
            val known = hashSetOf(chosenEdge.origin.first)
            while (known.size != mergedNode.origNodes.size) {
                for (origNode in mergedNode.origNodes) {
                    if(origNode !in known) {
                        val action =
                            origGame.getAvailableActions(origNode).find {
                                origGame.getResult(origNode, it).support.any { it in known }
                            }
                        if (action != null) {
                            known.add(origNode)
                            originalStrategy[origNode] = action
                        }
                    }
                }
            }
        }
        return originalStrategy
    }

    override fun solve(analysisTask: AnalysisTask<N, A>): Map<N, Double> {
        return solveWithRange(analysisTask).lower
    }

    override fun solveWithStrategy(analysisTask: AnalysisTask<N, A>): Pair<Map<N, Double>, Map<N, A>> {
        TODO("Not yet implemented")
    }
}