package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.AnalysisTask
import hu.bme.mit.theta.probabilistic.StochasticGameSolver

class OVISolver<N, A>(
    val epsilon: Double,
    var tolerance: Double, //threshold
    val useGS: Boolean = true,
    var values: MutableMap<N, Double> = mutableMapOf(),
    var toleranceAdjustmentCount: Int = 0,
    val onToleranceAdjustment: (Int) -> Unit = {}
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

        // If it is the first iteration, set the values with standard VI
        if (values.isEmpty()) {
            val viSolver = VISolver<N, A>(tolerance, useGS)
            val initialValues = viSolver.solve(analysisTask, initializer)
            values = initialValues.toMutableMap()
        }

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

            if (isAnyNewValueLessThanValue(upperBoundValues, newUpperBoundValues)) {
                //upperBoundValues = newUpperBoundValues
                upperBoundValues = updateMapIfSmaller(upperBoundValues, newUpperBoundValues)
                up = false
            } else if (isAnyNewValueLessThanValue(newUpperBoundValues, upperBoundValues)) {
                down = false
            }

            values = newValues.toMutableMap()
            strategy.putAll(valueStepResult.strategyUpdate!!)

            if (isAnyNewValueLessThanValue(values, upperBoundValues)) {
                this.tolerance = err / 2
                this.toleranceAdjustmentCount++
                onToleranceAdjustment(this.toleranceAdjustmentCount)
                return this.solveWithStrategy(analysisTask, initializer)
            }

            if (down) {
                val resultMap = averageValues(values, upperBoundValues)
                return resultMap to strategy
            } else if (up) {
                this.tolerance = err / 2
                this.toleranceAdjustmentCount++
                onToleranceAdjustment(this.toleranceAdjustmentCount)
                return this.solveWithStrategy(analysisTask, initializer)
            }
        }
        this.tolerance = err / 2
        this.toleranceAdjustmentCount++
        onToleranceAdjustment(this.toleranceAdjustmentCount)
        return this.solveWithStrategy(analysisTask, initializer)
    }

    fun adjustTolerance() {
        toleranceAdjustmentCount++
        onToleranceAdjustment(toleranceAdjustmentCount) // Call the provided lambda
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

    fun isAnyNewValueLessThanValue(values: Map<N, Double>, newValues: Map<N, Double>): Boolean {
        return values.keys.any { node ->
            newValues[node]?.let { newValue ->
                newValue < values[node]!!
            } ?: false
        }
    }

    fun updateMapIfSmaller(originalMap: Map<N, Double>, newMap: Map<N, Double>): Map<N, Double> {
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
        map1: Map<N, Double>,
        map2: Map<N, Double>
    ): Map<N, Double> {
        return map1.keys.associateWith { node ->
            val value1 = map1[node] ?: 0.0
            val value2 = map2[node] ?: 0.0
            (value1 + value2) / 2
        }
    }
}