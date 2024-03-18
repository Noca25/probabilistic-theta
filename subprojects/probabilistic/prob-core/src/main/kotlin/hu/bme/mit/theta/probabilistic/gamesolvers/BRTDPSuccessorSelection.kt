package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.probabilistic.StochasticGame
import kotlin.random.Random

private val random = Random(123)
fun <N, A> StochasticGame<N, A>.randomSelection(
    currNode: N,
    L: Map<N, Double>,
    U: Map<N, Double>,
    goal: Goal
): N {
    // first we select the best action according to U if maxing/L if mining so that the policy is optimistic
    // O for optimistic
    val O = if (goal == Goal.MAX) U else L
    val actionVals = this.getAvailableActions(currNode).associateWith {
        getResult(currNode, it).expectedValue { O.getValue(it) }
    }
    val bestValue = goal.select(actionVals.values)
    val bests = actionVals.filterValues { it == bestValue }.map { it.key }
    val best = bests[random.nextInt(bests.size)]
    // then sample from its result
    val result = getResult(currNode, best).sample(random)
    return result
}

fun <N, A> StochasticGame<N, A>.diffBasedSelection(
    currNode: N,
    L: Map<N, Double>,
    U: Map<N, Double>,
    goal: Goal
): N {
    val O = if (goal == Goal.MAX) U else L
    val actionVals = getAvailableActions(currNode).associateWith {
        getResult(currNode, it).expectedValue { O.getValue(it) }
    }
    val bestValue = goal.select(actionVals.values)
    val bests = actionVals.filterValues { it == bestValue }.map { it.key }
    val best = bests[random.nextInt(bests.size)]
    val nextNodes = getResult(currNode, best).support
    var sum = 0.0
    val pmf = nextNodes.associateWith {
        val d = U[it]!! - L[it]!!
        sum += d
        d
    }.toMutableMap()
    if(sum == 0.0) {
        // If every successor has already converged, we chose uniformly
        // (should actually stop the simulation, but that is the responsibility of the BRTDP loop)
        return nextNodes.random(random)
    }
    else {
        for (nextNode in nextNodes) {
            pmf[nextNode] = pmf[nextNode]!! / sum
        }
        val result = FiniteDistribution(pmf).sample(random)
        return result
    }
}

fun <N, A> StochasticGame<N, A>.weightedRandomSelection(
    currNode: N,
    L: Map<N, Double>,
    U: Map<N, Double>,
    goal: Goal
): N {
    val O: Map<N, Double> = if (goal == Goal.MAX) U else L
    val actionVals = getAvailableActions(currNode).associateWith {
        getResult(currNode, it).expectedValue { O.getValue(it) }
    }
    val bestValue = goal.select(actionVals.values)
    val bests = actionVals.filterValues { it == bestValue }.map { it.key }
    val best = bests[random.nextInt(bests.size)]
    val actionResult = getResult(currNode, best)
    val weights = actionResult.support.map {
        it to actionResult[it] * (U[it]!!-L[it]!!)
    }
    val sum = weights.sumOf { it.second }
    if(sum == 0.0) {
        return actionResult.support.toList()[random.nextInt(actionResult.support.size)]
    }
    val pmf = weights.associate { it.first to it.second / sum }
    val result = FiniteDistribution(pmf).sample(random)
    return result
}