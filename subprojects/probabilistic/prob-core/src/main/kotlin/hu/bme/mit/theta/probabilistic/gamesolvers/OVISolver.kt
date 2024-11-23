package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.StochasticGameSolver
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.ExplicitInitializer

class OVISolver<N, A>(
    val epsilon: Double,
    var tolerance: Double, //threshold
    val useGS: Boolean = true,
    var values: MutableMap<N, Double> = mutableMapOf(),
    var toleranceAdjustmentCount: Int = 0,
) : StochasticGameSolver<N, A> {
    override fun solve(analysisTask: AnalysisTask<N, A>, initializer: SGSolutionInitializer<N, A>): Map<N, Double> {
        return solveWithStrategy(analysisTask, initializer).first
    }

    override fun solveWithStrategy(
        analysisTask: AnalysisTask<N, A>,
        initializer: SGSolutionInitializer<N, A>
    ): Pair<Map<N, Double>, Map<N, A>> {
        val game = analysisTask.game
        val goal = analysisTask.goal
        val rewardFunction = analysisTask.rewardFunction

        val allNodes = game.getAllNodes()
        val unknownNodes = allNodes.filterNot(initializer::isKnown)
        val strategy = initializer.initialStrategy().toMutableMap()

        val viSolver = VISolver<N, A>(tolerance, useGS)
        val initialValues = viSolver.solve(analysisTask, initializer)
        values = initialValues.toMutableMap()

        var upperBoundValues = upperBoundValues(values, epsilon)
        var viters = 0
        var err = 0.0

        //Start verification phase
        while (viters < 1 / tolerance) {
            var up = true
            var down = true
            viters++

            // Bellman step on both bounds
            val valueStepResult =
                bellmanStep(game, values, goal, rewardFunction, analysisTask.discountFactor, useGS, unknownNodes)
            val upperBoundValueStepResult = bellmanStep(
                game,
                upperBoundValues,
                goal,
                rewardFunction,
                analysisTask.discountFactor,
                useGS,
                unknownNodes
            )

            err = valueStepResult.maxChange

            val newValues = valueStepResult.result
            val newUpperBoundValues = upperBoundValueStepResult.result

            if (valueDecrease(upperBoundValues, newUpperBoundValues)) {
                upperBoundValues = updateDecreasedValues(upperBoundValues, newUpperBoundValues)
                up = false
            }
            if (valueIncrease(upperBoundValues, newUpperBoundValues)) {
                down = false
            }

            values = newValues.toMutableMap()
            strategy.putAll(valueStepResult.strategyUpdate!!)

            if (valueDecrease(values, upperBoundValues)) {
                return adjustToleranceAndRecurse(analysisTask, strategy, err)
            }

            if (down) {
                val resultMap = averageValues(values, upperBoundValues)
                println("Tolerance adjusted " + toleranceAdjustmentCount + " times")
                return resultMap to strategy
            } else if (up) {
                return adjustToleranceAndRecurse(analysisTask, strategy, err)
            }
        }
        return adjustToleranceAndRecurse(analysisTask, strategy, err)
    }

    private fun adjustToleranceAndRecurse(
        analysisTask: AnalysisTask<N, A>,
        strategy: MutableMap<N, A>,
        err: Double
    ): Pair<Map<N, Double>, Map<N, A>> {
        this.tolerance = err / 2
        this.toleranceAdjustmentCount++
        return this.solveWithStrategy(
            analysisTask,
            ExplicitInitializer(values, mapOf(), 0.0, Double.POSITIVE_INFINITY, this.tolerance, strategy)
        )
    }

    fun upperBoundValues(values: Map<N, Double>, epsilon: Double): Map<N, Double> {
        val upperValues = mutableMapOf<N, Double>()
        for (v in values) {
            if (v.value == 0.0) {
                upperValues.put(v.key, 0.0)
            } else {
                upperValues.put(v.key, v.value + epsilon)
            }
        }
        return upperValues
    }

    // Returns true if at least one value decreased with the Bellman step
    fun valueDecrease(values: Map<N, Double>, newValues: Map<N, Double>): Boolean {
        for (node in values.keys) {
            if (newValues[node]!! < values[node]!!) {
                return true
            }
        }
        return false
    }

    // Returns true if at least one value increased with the Bellman step
    fun valueIncrease(values: Map<N, Double>, newValues: Map<N, Double>): Boolean {
        for (node in values.keys) {
            if (newValues[node]!! > values[node]!!) {
                return true
            }
        }
        return false
    }

    fun updateDecreasedValues(originalMap: Map<N, Double>, newMap: Map<N, Double>): Map<N, Double> {
        val updatedMap = originalMap.toMutableMap()
        for ((key, newValue) in newMap) {
            updatedMap[key]?.let { originalValue ->
                if (newValue < originalValue) {
                    updatedMap[key] = newValue
                }
            }
        }
        return updatedMap
    }

    fun averageValues(
        lowerBound: Map<N, Double>,
        upperBound: Map<N, Double>
    ): Map<N, Double> {
        return lowerBound.keys.associateWith { node ->
            val lowerValue = lowerBound[node] ?: 0.0
            val upperValue = upperBound[node] ?: 0.0
            (lowerValue + upperValue) / 2
        }
    }
}