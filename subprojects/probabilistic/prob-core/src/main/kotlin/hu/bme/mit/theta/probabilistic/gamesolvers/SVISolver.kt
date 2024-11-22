package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.*
import kotlin.math.max
import kotlin.math.min

class SVISolver<N, A>(
    var precision: Double
) : StochasticGameSolver<N, A> {
    override fun solve(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Map<N, Double> {
        val goal = analysisTask.goal
        val game = analysisTask.game
        val rewardFunction = analysisTask.rewardFunction
        val nodes = game.getAllNodes()

        val (mergedGame, mergedGameMap) = mergeMECs(game, rewardFunction)
        val mergedRewardFunction = MergedRewardFunction<N, A>()
        val mergedGameNodes = mergedGame.nodes
        val mergedInit = mergedGame.initNode

        val knownNodes = mergedGameNodes.filter { it.origNodes.all { node -> initializer.isKnown(node) } }.toMutableList()
        val unknownNodes = mergedGameNodes.filterNot { it.origNodes.all { node -> initializer.isKnown(node) } }.toMutableList()

        // Initialize vectors, bounds and decision value
        var values_x: MutableMap<MergedNode<N, A>, Double> = mergedGameNodes.associateWithTo(mutableMapOf()) { 0.0 }
        var values_y: MutableMap<MergedNode<N, A>, Double> = mergedGameNodes.associateWithTo(mutableMapOf()) { 1.0 }
        var lowerBound = Double.NEGATIVE_INFINITY
        var upperBound = Double.POSITIVE_INFINITY
        var decisionValue = Double.NEGATIVE_INFINITY
        var k = 0

        // Stores the data of the previous iteration
        var sviIterationData = SVIIterationData(HashMap(values_x), HashMap(values_y), lowerBound, upperBound, decisionValue)

        do {
            k++
            values_x.clear()
            values_y.clear()

            values_x = knownNodes.associateWithTo(mutableMapOf()) { 0.0 }
            values_y = knownNodes.associateWithTo(mutableMapOf()) { 0.0 }
            decisionValue = sviIterationData.decisionValue

            for(node in unknownNodes){
                val optimalAction = findAction(mergedGame, mergedRewardFunction, sviIterationData, node, goal)
                decisionValue = max(decisionValue, calculateDecisionValue(sviIterationData, node, optimalAction))
                val nodeValues = mergedGame.nodes.associateWithTo(mutableMapOf()) { mergedRewardFunction.getStateReward(it) + sviIterationData.values_x[it]!! }
                values_x.put(node, actionValues(mergedGame, nodeValues, node, mergedRewardFunction)[optimalAction]!!)
                values_y.put(node, actionValues(mergedGame, sviIterationData.values_y, node)[optimalAction]!!)
            }

            val allYValuesLessThanOne = values_y.filter { (node, _) -> unknownNodes.contains(node) }
                .all { (_, value) -> value < 1 }

            if(allYValuesLessThanOne){
                lowerBound = max(
                    sviIterationData.lowerBound,
                    unknownNodes.map { node ->
                        values_x[node]!! / (1 - values_y[node]!!)
                    }.minOrNull() ?: sviIterationData.lowerBound
                )

                upperBound = min(
                    sviIterationData.upperBound,
                    max(
                        decisionValue,
                        unknownNodes.map { node ->
                            values_x[node]!! / (1 - values_y[node]!!)
                        }.maxOrNull() ?: decisionValue
                    )
                )
            }
            sviIterationData = SVIIterationData(HashMap(values_x), HashMap(values_y), lowerBound, upperBound, decisionValue)
        } while (values_y[mergedInit]!! * (upperBound - lowerBound) >= 2 * precision)

        val sum = if(lowerBound == Double.NEGATIVE_INFINITY && upperBound == Double.POSITIVE_INFINITY) { 0.0 } else{ lowerBound + upperBound }

        return nodes.associateWith { values_x[mergedGameMap[it]!!]!! + values_y[mergedGameMap[it]!!]!! * (sum / 2) }
    }

    override fun solveWithStrategy(
        analysisTask: AnalysisTask<N, A>,
        initializer: SGSolutionInitializer<N, A>
    ): Pair<Map<N, Double>, Map<N, A>> {
        TODO("Not yet implemented")
    }

    data class SVIIterationData<N, A>(
        val values_x: MutableMap<MergedNode<N, A>, Double>,
        val values_y: MutableMap<MergedNode<N, A>, Double>,
        val lowerBound: Double,
        val upperBound: Double,
        val decisionValue: Double
    )

    fun findAction(mergedGame: MergedGame<N, A>, mergedRewardFunction: MergedRewardFunction<N, A>, sviIterationData: SVIIterationData<N, A>, mergedNode: MergedNode<N, A>, goal: (Int) -> Goal): MergedEdge<N, A>{
        val nodeValues = if (sviIterationData.upperBound != Double.POSITIVE_INFINITY) {
            mergedGame.nodes.associateWithTo(mutableMapOf()) { mergedRewardFunction.getStateReward(it) + sviIterationData.values_x[it]!! + sviIterationData.values_y[it]!! * sviIterationData.upperBound }
        } else {
            mergedGame.nodes.associateWithTo(mutableMapOf()) { mergedRewardFunction.getStateReward(it) + sviIterationData.values_x[it]!! }
        }
        val values = actionValues(mergedGame, nodeValues, mergedNode, mergedRewardFunction)
        val optimalAction = goal(mergedGame.getPlayer(mergedNode)).argSelect(values)!!
        return optimalAction
    }

    fun calculateDecisionValue(sviIterationData: SVIIterationData<N, A>, node: MergedNode<N, A>, alpha: MergedEdge<N, A>): Double {
        var decisionValue = Double.NEGATIVE_INFINITY
        for (beta in node.edges.filterNot { it == alpha }) {
            val (y_delta, x_delta) = calculateDeltaYX(node, alpha, beta, sviIterationData.values_y, sviIterationData.values_x)
            if (y_delta > 0){
                decisionValue = max(decisionValue, x_delta / y_delta)
            }
        }
        return decisionValue
    }

    fun calculateDeltaYX(node: MergedNode<N, A>, alpha: MergedEdge<N, A>, beta: MergedEdge<N, A>, y_values: Map<MergedNode<N, A>, Double>, x_values: Map<MergedNode<N, A>, Double>): Pair<Double, Double> {
        var deltaY = 0.0
        var deltaX = 0.0
        val availableNodes = node.edges.flatMap { it.res.pmf.keys }
        for (nextNode in availableNodes) {
            val probabilityAlpha = node.edges.first { it == alpha }.res.pmf[nextNode] ?: 0.0
            val probabilityBeta = node.edges.first { it == beta }.res.pmf[nextNode] ?: 0.0
            deltaY += (probabilityAlpha - probabilityBeta) * y_values[nextNode]!!
            deltaX += (probabilityBeta - probabilityAlpha) * x_values[nextNode]!!
        }
        return Pair(deltaY, deltaX)
    }


}