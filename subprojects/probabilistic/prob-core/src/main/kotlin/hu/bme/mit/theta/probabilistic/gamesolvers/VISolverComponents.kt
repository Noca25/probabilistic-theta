package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.*
import kotlin.collections.List
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.abs
import java.util.ArrayDeque
import kotlin.collections.*
import java.util.*

data class StepResult<N>(
    val result: Map<N, Double>,
    val maxChange: Double
)

/**
 * Computes the standard Bellman update in a stochastic game, and returns a new map with the new values.
 * Assumes that absorbing states are equipped with a self-loop instead of having no actions.
 * @param gaussSeidel Specifies whether the already updated neighbour values should be used (Gauss-Seidel VI, true),
 *  or the original values (standard VI, false)
 * @return Result of the Bellman update along with the absolute value of the largest value changed.
 */
fun <N, A> bellmanStep(
    game: StochasticGame<N, A>,
    currValues: Map<N, Double>,
    goal: (Int) -> Goal,
    discountFactor: Double = 1.0,
    gaussSeidel: Boolean = false
): StepResult<N> {
    val res = HashMap(currValues)
    val v = if(gaussSeidel) res else currValues
    var maxChange = 0.0
    for (node in game.getAllNodes()) {
        val newValue =
            // The result must always be non-null, if absorbing nodes have self-loops
            discountFactor * goal(game.getPlayer(node)).select(actionValues(game, v, node).values)!!
        res[node] = newValue
        val change = abs(newValue - currValues[node]!!)
        if (change > maxChange) maxChange = change
    }
    return StepResult(res, maxChange)
}

/**
 * Computes the standard Bellman update in a stochastic game, and returns a new map with the new values.
 * States meant to be absorbing must not be equipped with self-loops as it results in infinite reward.
 * @param gaussSeidel Specifies whether the already updated neighbour values should be used (Gauss-Seidel VI, true),
 *  or the original values (standard VI, false)
 * @return Result of the Bellman update along with the absolute value of the largest value changed.
 */
fun <N, A> bellmanStep(
    game: StochasticGame<N, A>,
    currValues: Map<N, Double>,
    goal: (Int) -> Goal,
    rewardFunction: GameRewardFunction<N, A>,
    discountFactor: Double = 1.0,
    gaussSeidel: Boolean = false
): StepResult<N> {
    val res = HashMap(currValues)
    val v = if(gaussSeidel) res else currValues
    var maxChange = 0.0
    for (node in game.getAllNodes()) {
        val newValue =
            rewardFunction.getStateReward(node) +
            discountFactor * (goal(game.getPlayer(node)).select(actionValues(game, v, node, rewardFunction).values) ?: 0.0)
        res[node] = newValue
        val change = abs(newValue - currValues[node]!!)
        if (change > maxChange) maxChange = change
    }
    return StepResult(res, maxChange)
}

/**
 * Computes the expected value of taking an action in a node for each available action in the node.
 */
fun <N, A> actionValues(game: StochasticGame<N, A>, nodeValues: Map<N, Double>, node: N): Map<A, Double> {
    return game.getAvailableActions(node)
        .associateWith { act ->
            game.getResult(node, act).expectedValue { n -> nodeValues.getOrDefault(n, 0.0) }
        }
}

/**
 * Computes the expected value of taking an action in a node for each available action in the node.
 */
fun <N, A> actionValues(game: StochasticGame<N, A>, nodeValues: Map<N, Double>, node: N, rewardFunction: GameRewardFunction<N, A>): Map<A, Double> {
    return game.getAvailableActions(node)
        .associateWith { act ->
            game.getResult(node, act)
                .expectedValue { n ->
                    nodeValues.getOrDefault(n, 0.0) + rewardFunction.getEdgeReward(node, act, n)
                }
        }
}

/**
 * Computes the deflation step of BVI.
 * See Kelmendi et. al.: Value Iteration for Simple Stochastic Games: Stopping Criterion and Learning Algorithm
 * for details.
 * @param upperValues The values to deflate
 * @param lowerValues The values to base dynamic MSEC computation on
 */
fun <N, A> deflate(
    game: StochasticGame<N, A>,
    upperValues: Map<N, Double>,
    lowerValues: Map<N, Double>,
    goal: (Int) -> Goal,
    msecOptimalityThreshold: Double = 1e-18
): Map<N, Double> {
    val optimalActions = game.getAllNodes().associateWith { n ->
        if (goal(game.getPlayer(n)) == Goal.MAX) game.getAvailableActions(n)
        else {
            val vals = actionValues(game, lowerValues, n)
            val optim = vals.values.minOrNull()
                ?: throw Exception("No out edges on node $n - use self loops for absorbing states!")
            game.getAvailableActions(n).filter { vals[it]!!.equals(optim, msecOptimalityThreshold) }
        }
    }
    val msecs = computeMECs(game) { optimalActions.get(it)!! }
    val res = upperValues.toMutableMap()
    for (msec in msecs) {
        val bestExit = (msec.filter { goal(game.getPlayer(it)) == Goal.MAX }.flatMap { n ->
            game.getAvailableActions(n).map { act -> game.getResult(n, act) }.filter { it.support.any { it !in msec } }
                .map { it.expectedValue { upperValues[it]!! } }
        } + msec.mapNotNull { lowerValues[it] } // Used so that we do not deflate lower than the currently known lower approximation
                ).maxOrNull() ?: 0.0
        for (node in msec) {
            if (res[node]!! > bestExit) res[node] = bestExit
        }
    }
    return res
}

/**
 * Computes the deflation step of BVI.
 * See Kelmendi et. al.: Value Iteration for Simple Stochastic Games: Stopping Criterion and Learning Algorithm
 * for details.
 * @param upperValues The values to deflate
 * @param lowerValues The values to base dynamic MSEC computation on
 */
fun <N, A> deflate(
    game: StochasticGame<N, A>,
    upperValues: Map<N, Double>,
    lowerValues: Map<N, Double>,
    goal: (Int) -> Goal,
    rewardFunction: GameRewardFunction<N, A>,
    msecOptimalityThreshold: Double = 1e-18
): Map<N, Double> {
    val optimalActions = game.getAllNodes().associateWith { n ->
        if (goal(game.getPlayer(n)) == Goal.MAX) game.getAvailableActions(n)
        else {
            val vals = actionValues(game, lowerValues, n)
            val optim = vals.values.minOrNull()
                ?: throw Exception("No out edges on node $n - use self loops for absorbing states!")
            game.getAvailableActions(n).filter { vals[it]!!.equals(optim, msecOptimalityThreshold) }
        }
    }
    val msecs = computeMECs(game) { optimalActions.get(it)!! }
    val res = upperValues.toMutableMap()
    for (msec in msecs) {
        val bestExit = (msec.filter { goal(game.getPlayer(it)) == Goal.MAX }.flatMap { n ->
            game.getAvailableActions(n).map { act -> game.getResult(n, act) }.filter { it.support.any { it !in msec } }
                .map { it.expectedValue { upperValues[it]!! } }
        } + msec.mapNotNull { lowerValues[it] } // Used so that we do not deflate lower than the currently known lower approximation
                ).maxOrNull() ?: 0.0
        for (node in msec) {
            TODO("rewards")
            if (res[node]!! > bestExit) res[node] = bestExit
        }
    }
    return res
}

/**
 * Computes Maximal End Components of a stochastic game. A single node by itself can only be an end-component if it
 * has a self-loop.
 * @param game The game to compute MECs of. Must be finite, as getAllNodes is called.
 * @param allowedActions Optional parameter to limit the available actions for each node.
 *      For each node n, allowedActions(n) should be a subset of game.availableActions(n).
 * @return List of maximal end components. One-element components are returned if they have self-loops.
 */
fun <N, A> computeMECs(
    game: StochasticGame<N, A>,
    allowedActions: (N) -> Collection<A> = game::getAvailableActions
): List<List<N>> {
    // Iterative computation using SCCs:
    // - the iteration starts with all actions allowed
    // - in each iteration, the strongly connected components are computed with the currently allowed actions
    // - if an action moves to a different SCC with non-zero probability from a node,
    //     it is removed from the allowed actions for that node
    // - this is repeated until fixpoint => the last computed SCCs are MECs, except for single-node components,
    //   where self-loops must be present to be considered MECs

    val allowedActionMap = game.getAllNodes().associateWith {
        HashSet(allowedActions(it))
    }

    while (true) {
        val currSCCs = computeSCCs(game) { allowedActionMap[it]!! }
        var changed = false
        for (scc in currSCCs) {
            for (n in scc) {
                val actions = allowedActionMap[n]!!
                val iter = actions.iterator()
                while (iter.hasNext()) {
                    val act = iter.next()
                    val leavesSCC = game.getResult(n, act).support.any { it !in scc }
                    if (leavesSCC) {
                        // This intentionally changes the original set in allowedActionMap!
                        iter.remove()
                        changed = true
                    }
                }
            }
        }

        if (!changed) return currSCCs.filter {
            // for single-node components, a self-loop must be present to be a MEC
            it.size > 1 || it.first().let { n ->
                allowedActions(n).any { a -> game.getResult(n, a).support == hashSetOf(n) }
            }
        }
    }
}

/**
 * Computes Strongly Connected Components of the edge-relation graph (ERG) of stochastic game.
 * An edge is present in the edge relation graph from node n to node m,
 * if an action is available in n that results in entering m with non-zero probability.
 * @param game The game to compute SCCs of. Must be finite, as getAllNodes is called.
 * @param allowedActions Optional parameter to limit the available actions for each node.
 *      For each node n, allowedActions(n) should be a subset of game.availableActions(n).
 * @return List of strongly connected components of the ERG. One-element components can exist without self-loops,
 *      unlike in the case of end-components
 */
fun <N, A> computeSCCs(
    game: StochasticGame<N, A>,
    allowedActions: (N) -> Collection<A>
): List<List<N>> {
    val ergEdges = arrayListOf<List<Int>>()
    val nodes = game.getAllNodes().toList()
    val idx = nodes.withIndex().associate { it.value to it.index }
    for (node in nodes) {
        val actions = allowedActions(node)
        ergEdges.add(
            actions.flatMap { game.getResult(node, it).support.map { idx[it]!! } }.distinct()
        )
    }
    return computeSCCs(ergEdges, nodes.size).map { it.map { nodes[it] } }
}

/**
 * Computes the strongly connected components of a directed graph, given by edge lists. A single node is an SCC can be
 * an SCC by itself even if it does not have a self-loop.
 * @param edges: the nth element gives the ends of edges leaving the nth node
 * @param numNodes: number of nodes in the graph (all numbers in the edge list must be less than this number)
 */
fun computeSCCs(
    edges: List<List<Int>>,
    numNodes: Int
): ArrayList<Set<Int>> {
    // The computation uses the Kosaraju algorithm
    // Using Ints from the node indices instead of the node objects

    val L = Stack<Int>()
    L.ensureCapacity(numNodes)

    //DFS to push every vertex onto L in their DFS *completion* order
    val E = edges

    val dfsstack = Stack<Int>()
    dfsstack.ensureCapacity(numNodes)

    val visited = Array(E.size) { false }
    for (i in E.indices) {
        if (visited[i]) continue
        dfsstack.push(i)
        while (!dfsstack.empty()) {
            val u = dfsstack.peek()
            visited[u] = true
            var completed = true
            for (v in E[u]) {
                if (!visited[v]) {
                    completed = false
                    dfsstack.push(v)
                }
            }
            if (completed) {
                dfsstack.pop()
                L.push(u)
            }
        }
    }

    val EInv = Array(E.size) { ArrayList<Int>() }
    for ((u, list) in E.withIndex()) {
        for (v in list) {
            EInv[v].add(u)
        }
    }

    val q: Queue<Int> = ArrayDeque()
    val assigned = Array(E.size) { false }
    val SCCs: ArrayList<Set<Int>> = arrayListOf()
    while (!L.empty()) {
        val u = L.pop()
        if (assigned[u]) continue
        val scc = HashSet<Int>()
        q.add(u)
        while (!q.isEmpty()) {
            val v = q.poll()
            scc.add(v)
            assigned[v] = true
            for (w in EInv[v]) {
                if (!assigned[w]) q.add(w)
            }
        }
        SCCs.add(scc)
    }

    return SCCs
}

/**
 * Computes the set of nodes from which the target set of nodes can be reached almost surely with an appropriate strategy.
 * The input stochastic game must be a finite MPD: all nodes must belong to the same player and mdp.getAllNodes must
 * terminate in finite time. Implementation based on Principles of Model Checking Algorithm 45.
 */
fun <N, A> almostSureMaxForMDP(
    mdp: StochasticGame<N, A>,
    targets: List<N>
): List<N> {
    val nodes = mdp.getAllNodes().toList()
    val nodeToIdMap = nodes.withIndex().associate { it.value to it.index }
    val actionResults = nodes.map { n ->
        mdp.getAvailableActions(n).map { a ->
            mdp.getResult(n, a).support.map { nodeToIdMap.getValue(it) }
        }
    }
    val almostSureIds = almostSureMaxForMDP(
        nodes.size, actionResults, targets.map(nodeToIdMap::getValue)
    )
    val result = almostSureIds.map { nodes[it] }
    return result
}

fun almostSureMaxForMDP(
    numNodes: Int,
    actionResults: List<List<List<Int>>>,
    targets: List<Int>
): Collection<Int> {
    // implementation based on Principles of Model Checking Algorithm 45.
    require(actionResults.size == numNodes)

    val remainingNodes = (0 until numNodes).toMutableList()
    val modifiedActionResults = actionResults.map { it.toMutableList() }.toMutableList()

    var pre = (0 until numNodes).map { arrayListOf<Pair<Int, Int>>() }
    for ((node, actions: List<List<Int>>) in modifiedActionResults.withIndex()) {
        for((action, results: List<Int>) in actions.withIndex()) {
            for (result in results) {
                pre[result].add(Pair(node, action))
            }
        }
    }

    var U: MutableList<Int> = arrayListOf()
    U.addAll(remainingNodes)
    U.removeAll(targets)
    var lastRemoved = targets
    do {
        lastRemoved = lastRemoved.flatMap { pre[it].map { it.first } }
    } while (U.removeAll(lastRemoved))

    do {
        val R = ArrayList(U)
        while (R.isNotEmpty()) {
            val u = R.removeLast()
            for ((t, alpha) in pre[u]) {
                if(t in U) continue
                for (i in modifiedActionResults[t][alpha]) {
                    pre[i].remove(Pair(t, alpha))
                }
                modifiedActionResults[t][alpha] = emptyList()
                if(modifiedActionResults.any { it.isNotEmpty() }) {
                    R.add(t)
                    U.add(t)
                }
            }
            // all incoming edges of u have been removed
            remainingNodes.remove(u)
            modifiedActionResults[u].clear()
        }

        pre = (0 until numNodes).map { arrayListOf() }
        for ((node, actions: List<List<Int>>) in modifiedActionResults.withIndex()) {
            for((action, results: List<Int>) in actions.withIndex()) {
                for (result in results) {
                    pre[result].add(Pair(node, action))
                }
            }
        }

        // determine the states s that cannot reach B in the modified MDP
        U = remainingNodes.minus(U).toMutableList()
        U.removeAll(targets)
        lastRemoved = targets
        do {
            lastRemoved = lastRemoved.flatMap { pre[it].map { it.first } }
        } while (U.removeAll(lastRemoved))
    } while (U.isNotEmpty())

    return remainingNodes
}

/**
 * Computes the set of nodes from which the target set of nodes can be reached almost surely with an appropriate strategy.
 * The input stochastic game must be a finite MPD: all nodes must belong to the same player and mdp.getAllNodes must
 * terminate in finite time. Implementation based on Principles of Model Checking Algorithm 45.
 */
fun <N, A> almostSureMinForMDP(
    mdp: StochasticGame<N, A>,
    targets: List<N>
): List<N> {
    val nodes = mdp.getAllNodes().toList()
    val nodeToIdMap = nodes.withIndex().associate { it.value to it.index }
    val actionResults = nodes.map { n ->
        mdp.getAvailableActions(n).map { a ->
            mdp.getResult(n, a).support.map { nodeToIdMap.getValue(it) }
        }
    }
    val almostSureIds = almostSureMinForMDP(
        nodes.size, actionResults, targets.map(nodeToIdMap::getValue)
    )
    val result = almostSureIds.map { nodes[it] }
    return result
}

fun almostSureMinForMDP(
    numNodes: Int,
    actionResults: List<List<List<Int>>>,
    targets: List<Int>
): Collection<Int> {
    // Based on Principles of Model Checking Lemma 10.111
    require(actionResults.size == numNodes)

    val modifiedActionResults = actionResults.map { it.toMutableList() }.toMutableList()

    var pre = (0 until numNodes).map { arrayListOf<Pair<Int, Int>>() }
    for ((node, actions: List<List<Int>>) in modifiedActionResults.withIndex()) {
        for((action, results: List<Int>) in actions.withIndex()) {
            for (result in results) {
                pre[result].add(Pair(node, action))
            }
        }
    }

    val S = (0 until numNodes)

    // Computing T = {s \in S | P^max(t |= [](!target)) = 1}
    val T = S.toMutableList()

    fun removeState(s: Int) {
        T.remove(s)
        for ((u, beta) in pre[s]) {
            modifiedActionResults[u][beta] = emptyList()
            if(modifiedActionResults.all { it.isEmpty() }) {
                removeState(u)
            }
        }
    }

    targets.forEach(::removeState)

    // results = complement of the set of states that can reach T via a path fragment through (S minus B)"
    var newlyAdded: List<Int> = T
    do {
        newlyAdded = newlyAdded.flatMap { pre[it].map { it.first }.filter { it !in targets && it !in T } }
        T.addAll(newlyAdded)
    } while (newlyAdded.isNotEmpty())

    return S.minus(T)
}

fun <N, A> almostNeverForMDP(
    mdp: StochasticGame<N, A>,
    targets: List<N>
): List<N> {
    val nodes = mdp.getAllNodes().toList()
    val nodeToIdMap = nodes.withIndex().associate { it.value to it.index }
    val actionResults = nodes.map { n ->
        mdp.getAvailableActions(n).map { a ->
            mdp.getResult(n, a).support.map { nodeToIdMap.getValue(it) }
        }
    }
    val almostNeverIds = almostNeverForMDP(
        nodes.size, actionResults, targets.map(nodeToIdMap::getValue)
    )
    val result = almostNeverIds.map { nodes[it] }
    return result
}

fun almostNeverForMDP(
    numNodes: Int,
    actionResults: List<List<List<Int>>>,
    targets: List<Int>
): Collection<Int> {
    // implementation based on Principles of Model Checking Alg 46
    TODO()
}

fun tarjan() {
    TODO()
}

fun ndfs() {
    TODO()
}