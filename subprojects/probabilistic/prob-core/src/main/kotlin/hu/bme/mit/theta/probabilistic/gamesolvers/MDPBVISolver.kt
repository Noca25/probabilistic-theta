package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.*
import kotlin.math.max
import kotlin.math.pow

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
        var uCurr = calculateInitialUpperBound(mergedGame, mergedRewardFunction)
        
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

    private fun calculateInitialUpperBound(mergedGame: MergedGame<N, A>, mergedRewardFunction: MergedRewardFunction<N, A>): Map<MergedNode<N, A>, Double>{
        val nodes = mergedGame.nodes
        val SCCs = computeSCCs(mergedGame) { node -> mergedGame.getAvailableActions(node)}

        val upperBound: MutableMap<MergedNode<N, A>, Double> = mutableMapOf()

        for(node in nodes){
            var upperBoundValue = 0.0
            if(node.edges.isNotEmpty()){
                val reachableNodes = getReachableNodes(node)
                for(reachableNode in reachableNodes){
                    val expNumOfVisits = calculateExpectedNumberOfVisits(mergedGame, SCCs, reachableNode)
                    val maxExpReward = getExpectedMaxRewardForNode(reachableNode, mergedRewardFunction)
                    upperBoundValue += expNumOfVisits * maxExpReward
                }
            }
            else{
                upperBoundValue = node.reward
            }
            upperBound.put(node, upperBoundValue)
        }
        return upperBound
    }

    fun getReachableNodes(startNode: MergedNode<N, A>): Set<MergedNode<N, A>> {
        val reachableNodes = mutableSetOf<MergedNode<N, A>>()
        val visited = mutableSetOf<MergedNode<N, A>>()
        dfs(startNode, reachableNodes, visited)
        return reachableNodes
    }

    fun dfs(currentNode: MergedNode<N, A>, reachableNodes: MutableSet<MergedNode<N, A>>, visited: MutableSet<MergedNode<N, A>>) {
        if (currentNode !in visited) {
            visited.add(currentNode)
            reachableNodes.add(currentNode)
            for (neighbor in currentNode.edges.flatMap { edge -> edge.res.pmf.keys }) {
                dfs(neighbor, reachableNodes, visited)
            }
        }
    }
    private fun calculateExpectedNumberOfVisits(mergedGame: MergedGame<N, A>, SCCs: List<Set<MergedNode<N, A>>>, node: MergedNode<N, A>): Double{
        val p = calculateP(mergedGame.nodes)
        val q = calculateQ(SCCs)
        val sizeOfComponent = SCCs.filter { it.contains(node) }.size

        val maxRecurrenceProbUpperBound = 1 - p.pow(sizeOfComponent - 1) * (1 - q)

        if (maxRecurrenceProbUpperBound == 1.0) {
            throw Exception("Division by zero")
        } else {
            return 1 / (1 - maxRecurrenceProbUpperBound)
        }
    }

    private fun calculateP(nodes: List<MergedNode<N,A>>): Double {
        return nodes.flatMap { node ->
            node.edges.flatMap { edge ->
                edge.res.pmf.values
            }
        }.minOrNull() ?:0.0
    }

    private fun calculateQ(SCCs: List<Set<MergedNode<N, A>>>): Double {
         return SCCs.map{ scc ->
             calculate_q_t(scc)
         }.maxOrNull() ?: 0.0
    }

    private fun calculate_q_t(SCC: Set<MergedNode<N, A>>): Double {
        val X_t = SCC.flatMap { node ->
            node.edges.map { edge ->
                val probStaysInSCC = edge.res.pmf
                    .filter { (targetNode, probability) -> targetNode in SCC }
                    .values.sum()
                if(probStaysInSCC < 1) probStaysInSCC else 0.0
            }
        }
        return X_t.maxOrNull() ?: 0.0
    }

    private fun getExpectedMaxRewardForNode(node: MergedNode<N, A>, rewardFunction: MergedRewardFunction<N, A>): Double{
        // merged reward functionnel szamolni az edge.reward helyett
        // state reward + exp act reward
        val nodeReward = rewardFunction.getStateReward(node)

        return node.edges.map{ edge ->
            edge.res.pmf.entries.sumOf { (targetNode, probability) ->
                val edgeReward = rewardFunction.getEdgeReward(node, edge, targetNode)
                nodeReward + probability * edgeReward
            }
        }.maxOrNull() ?: nodeReward
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