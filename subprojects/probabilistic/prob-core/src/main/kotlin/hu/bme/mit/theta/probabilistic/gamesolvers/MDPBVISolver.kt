package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.*
import kotlin.math.max
import kotlin.math.min

/**
 * Bounded Value Iteration solver for "stochastic games" with a single goal, which can be considered MDPs.
 * Uses MEC merging to make the upper bound converge, which does not work for general SGs.
 */
class MDPBVISolver<N, A>(
    val threshold: Double
) : StochasticGameSolver<N, A> {

    fun solveWithRange(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): RangeSolution<N, A> {
        require(analysisTask.discountFactor == 1.0) {
            "Discount not supported for BVI (yet?)"
        }

        val goal = analysisTask.goal
        val game = analysisTask.game
        val rewardFunction = analysisTask.rewardFunction
        val nodes = game.getAllNodes()

        val (mergedGame, mergedGameMap) = mergeMECs(game, rewardFunction)
        val mergedRewardFunction = MergedRewardFunction<N, A>()
        val mergedGameNodes = mergedGame.nodes
        val mergedInit = mergedGame.initNode

        var lCurr = mergedGameNodes.associateWith {
            max(it.reward, it.origNodes.maxOf { initializer.initialLowerBound(it) })
        }
        var uCurr = mergedGameNodes.associateWith {
            if(it.edges.isEmpty()) min(it.reward, it.origNodes.maxOf { initializer.initialUpperBound(it) })
            else it.origNodes.maxOf { initializer.initialUpperBound(it) }
        }
        val unknownNodes = mergedGameNodes.filter { uCurr[it]!! - lCurr[it]!! > threshold }.toMutableList()

        val strategy = hashMapOf<MergedNode<N, A>, MergedEdge<N, A>>() //TODO: initial strategy using the initializer
        do {
            lCurr = bellmanStep(mergedGame, lCurr, goal, mergedRewardFunction, unknownNodes = unknownNodes).result
            val uStep =
                bellmanStep(mergedGame, uCurr, goal, mergedRewardFunction, unknownNodes = unknownNodes)
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

    private fun liftStrategy(
        origGame: StochasticGame<N, A>,
        mergedStrategy: Map<MergedNode<N, A>, MergedEdge<N, A>>
    ): Map<N, A> {
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

    override fun solve(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Map<N, Double> {
        return solveWithRange(analysisTask, initializer).lower
    }

    override fun solveWithStrategy(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Pair<Map<N, Double>, Map<N, A>> {
        TODO("Not yet implemented")
    }
}