package hu.bme.mit.theta.probabilistic.gamesolvers

import hu.bme.mit.theta.probabilistic.*
import java.util.*
import kotlin.math.min

class MDPBRTDPSolver<N: ExpandableNode<N>, A>(
    val rewardFunction: TargetRewardFunction<N, A>,
    val successorSelection: StochasticGame<N, A>.(N, L: Map<N, Double>, U: Map<N, Double>, Goal) -> N,
    val threshold: Double = 1e-7,
    val progressReport: (iteration: Int, reachedSet: Set<N>, linit: Double, uinit: Double) -> Unit
     = { _,_,_,_ -> }
): StochasticGameSolver<N, A> {
    override fun solve(analysisTask: AnalysisTask<N, A>): Map<N, Double> {
        val game = analysisTask.game
        val initNode = game.initialNode
        val goal = analysisTask.goal(game.getPlayer(initNode))
        val reachedSet = hashSetOf(initNode)

        var U = hashMapOf(initNode to 1.0)
        var L = hashMapOf(initNode to 0.0)

        // virtually merged end components, also maintaining a set of edges that leave the EC for each of them
        val merged = hashMapOf(initNode to (setOf(initNode) to game.getAvailableActions(initNode)))

        var i = 0

        while (U[initNode]!! - L[initNode]!! > threshold) {
            // ---------------------------------------------------------------------------------------------------------
            // Logging for experiments
            i++
            if (i % 100 == 0)
                progressReport(i, reachedSet, L[initNode]!!, U[initNode]!!)
            //----------------------------------------------------------------------------------------------------------

            // simulate a single trace
            val trace = arrayListOf(initNode)
            val revisitedNodes = arrayListOf<N>()
            while (
                !((trace.last().isExpanded() && game.getAvailableActions(trace.last()).isEmpty())
                        || (trace.size > reachedSet.size * 3))
            ) {
                val lastNode = trace.last()
                if (!lastNode.isExpanded()) {

                    val (newlyExpanded, revisited) = lastNode.expand()
                    revisitedNodes.addAll(revisited)
                    if (merged[lastNode]!!.first.size == 1)
                        merged[lastNode] = setOf(lastNode) to game.getAvailableActions(lastNode)

                    for (newNode in newlyExpanded) {
                        // treating each node as its own EC at first so that value computations can be done
                        // solely based on the _merged_ map
                        merged[newNode] = setOf(newNode) to game.getAvailableActions(newNode)
                        if (rewardFunction.isTarget(newNode)) {
                            U[newNode] = 1.0
                            L[newNode] = 1.0
                        } else {
                            U[newNode] = 1.0
                            L[newNode] = 0.0
                        }
                    }

                    if (game.getAvailableActions(lastNode).isEmpty()) {
                        if (rewardFunction.isTarget(lastNode))
                            L[lastNode] = 1.0
                        else
                            U[lastNode] = 0.0
                        break
                    }
                }

                val nextNode = game.successorSelection(lastNode, L, U, goal)
                trace.add(nextNode)
            }

            while (revisitedNodes.isNotEmpty()) {
                val node = revisitedNodes.first()

                val mec = findMEC(node, game)
                val edgesLeavingMEC = mec.flatMap {n ->
                    game.getAvailableActions(n).filter { a -> game.getResult(n, a).support.any { it !in mec } }
                }
                if (mec.size > 1) {
                    for (n in mec) {
                        merged[n] = mec to edgesLeavingMEC
                        if (goal == Goal.MIN || edgesLeavingMEC.isEmpty()) U[n] = 0.0
                    }
                }
                revisitedNodes.removeAll(mec)
            }

            val Unew = HashMap(U)
            val Lnew = HashMap(L)
            // value propagation using the merged map
            for (node in trace.reversed()) {
                // TODO: based on rewards
                val unew = if (Unew[node] == 0.0) 0.0 else (goal.select(
                    merged[node]!!.second.map {
                        game.getResult(node, it).expectedValue { U.getValue(it) }
                    }
                ) ?: 1.0)
                val lnew = if (Lnew[node] == 1.0) 1.0 else (goal.select(
                    merged[node]!!.second.map {
                        game.getResult(node, it).expectedValue { L.getValue(it) }
                    }
                ) ?: 0.0)

                for (siblingNode in merged[node]!!.first) {
                    Unew[siblingNode] = unew
                    Lnew[siblingNode] = lnew
                }
            }
            U = Unew
            L = Lnew
        }

        return U
    }

    private fun findMEC(root: N, game: StochasticGame<N, A>): Set<N> {

        fun findSCC(
            root: N,
            availableActions: (N) -> Collection<A>
        ): Set<N> {
            val stack = Stack<N>()
            val lowlink = hashMapOf<N, Int>()
            val index = hashMapOf<N, Int>()
            var currIndex = 0

            fun strongConnect(n: N): Set<N> {
                index[n] = currIndex
                lowlink[n] = currIndex++
                stack.push(n)

                val successors =
                    availableActions(n).flatMap { game.getResult(n, it).support.map { it } }.toSet()
                for (m in successors) {
                    if (m !in index) {
                        strongConnect(m)
                        lowlink[n] = min(lowlink[n]!!, lowlink[m]!!)
                    } else if (stack.contains(m)) {
                        lowlink[n] = min(lowlink[n]!!, index[m]!!)
                    }
                }

                val scc = hashSetOf<N>()
                if (lowlink[n] == index[n]) {
                    do {
                        val m = stack.pop()
                        scc.add(m)
                    } while (m != n)
                }
                return scc
            }

            return strongConnect(root)
        }

        var scc: Set<N> = hashSetOf()
        val origAvailableEdges: (N) -> Collection<A> =
            { if(it.isExpanded()) game.getAvailableActions(it) else arrayListOf() }
            // TODO("will this work?")
        var availableEdges = origAvailableEdges
        do {
            val prevSCC = scc
            scc = findSCC(root, availableEdges)
            // TODO: it would make more sense to use a map for handling this
            availableEdges = { n: N ->
                origAvailableEdges(n).filter { game.getResult(n, it).support.all { it in scc } }
            }
        } while (scc.size != prevSCC.size)
        return scc
    }

}