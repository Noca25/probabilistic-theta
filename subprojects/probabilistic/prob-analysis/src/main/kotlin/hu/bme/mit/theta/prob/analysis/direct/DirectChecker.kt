package hu.bme.mit.theta.prob.analysis.direct

import com.google.common.base.Stopwatch
import hu.bme.mit.theta.analysis.TransFunc
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.prob.analysis.jani.*
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.Algorithm.*
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.BRTDPStrategy.*
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.probabilistic.*
import hu.bme.mit.theta.probabilistic.gamesolvers.MDPBRTDPSolver
import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.diffBasedSelection
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.MDPAlmostSureTargetInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.TargetSetLowerInitializer
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap
import kotlin.math.min

class DirectChecker<S: ExplState, A: StmtAction>(
    private val getStdCommands: (S) -> Collection<ProbabilisticCommand<A>>,
    private val getTargetExpression: (S) -> Expr<BoolType>,
    private val initState: S,
    private val fullPrec: ExplPrec,

    val transFunc: TransFunc<S, A, ExplPrec>,
    val mdpSolverSupplier:
        (threshold: Double,
         rewardFunction: GameRewardFunction<Node<S>, FiniteDistribution<Node<S>>>,
         initializer: SGSolutionInitializer<Node<S>, FiniteDistribution<Node<S>>>)
    -> StochasticGameSolver<Node<S>, FiniteDistribution<Node<S>>>,
    val useQualitativePreprocessing: Boolean = false,
    val verboseLogging: Boolean = false
) {


    class Node<S>(val state: S) {
        companion object {
            private var nextId = 0
        }

        private val id = nextId++
        private val outEdges = arrayListOf<FiniteDistribution<Node<S>>>()
        var isExpanded: Boolean = false
        var isTargetNode: Boolean = false
        fun getOutgoingEdges(): List<FiniteDistribution<Node<S>>> = outEdges
        fun createEdge(target: FiniteDistribution<Node<S>>) {
            outEdges.add(target)
        }

        override fun hashCode(): Int {
            return Objects.hashCode(this.id)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Node<S>

            return id == other.id
        }
    }

    fun vi(goal: Goal, threshold: Double): Double {

        return fullyExplored(
            getStdCommands, getTargetExpression,
            initState, goal, threshold, fullPrec, mdpSolverSupplier
        )

    }

    fun brtdp(goal: Goal, threshold: Double): Double {
        val timer = Stopwatch.createStarted()

        val initNode = Node(initState)
        val game = object : StochasticGame<Node<S>, FiniteDistribution<Node<S>>> {
            override val initialNode: Node<S>
                get() = initNode

            override fun getAllNodes(): Collection<Node<S>> = throw UnsupportedOperationException()

            override fun getPlayer(node: Node<S>): Int = 0

            override fun getResult(node: Node<S>, action: FiniteDistribution<Node<S>>): FiniteDistribution<Node<S>> {
                require(node.isExpanded || node.isTargetNode)
                return action
            }

            override fun getAvailableActions(node: Node<S>): List<FiniteDistribution<Node<S>>> {
                require(node.isExpanded || node.isTargetNode)
                return node.getOutgoingEdges()
            }
        }

        val rewardFunction =
            TargetRewardFunction<Node<S>, FiniteDistribution<Node<S>>> {
                it.isTargetNode
            }
        val reachedSet = hashMapOf(initState to initNode)
        val checker = MDPBRTDPSolver(
            threshold, { i, reachedSet, linit, uinit ->
                if (i % 100 == 0)
                    if(verboseLogging) {
                        println(
                            "$i: nodes: ${reachedSet.size}, [${linit}, ${uinit}], " +
                                    "d=${uinit - linit}, " +
                                    "time (ms): ${timer.elapsed(TimeUnit.MILLISECONDS)}"
                        )
                    }
            }, Node<S>::isExpanded,
            { expand(this, reachedSet, fullPrec)},
            rewardFunction, game::diffBasedSelection
        )
        val res = checker.solve(AnalysisTask(game, {goal}))
        timer.stop()
        return res[initNode]!!
    }

    @Deprecated("Use the delegated version instead, kept for stability")
    private fun brtdp(
        getStdCommands: (S) -> Collection<ProbabilisticCommand<A>>,
        targetExpression: Expr<BoolType>,
        initState: S,
        goal: Goal,
        successorSelection:
            (currNode: Node<S>, U: Map<Node<S>, Double>, L: Map<Node<S>, Double>, goal: Goal) -> Node<S>,
        threshold: Double,
        fullPrec: ExplPrec
    ): Double {
        val timer = Stopwatch.createStarted()

        val initNode = Node<S>(initState)
        val reachedSet = hashMapOf(initState to initNode)

        var U = hashMapOf(initNode to 1.0)
        var L = hashMapOf(initNode to 0.0)

        // virtually merged end components, also maintaining a set of edges that leave the EC for each of them
        val merged = hashMapOf(initNode to (setOf(initNode) to initNode.getOutgoingEdges()))

        var i = 0

        while (U[initNode]!! - L[initNode]!! > threshold) {
            // ---------------------------------------------------------------------------------------------------------
            // Logging for experiments
            i++
            if (i % 100 == 0)
                if(verboseLogging) {
                    println(
                        "$i: nodes: ${reachedSet.size}, [${L[initNode]}, ${U[initNode]}], " +
                                "d=${U[initNode]!! - L[initNode]!!}, " +
                                "time (ms): ${timer.elapsed(TimeUnit.MILLISECONDS)}"
                    )
                }
            //----------------------------------------------------------------------------------------------------------

            // simulate a single trace
            val trace = arrayListOf(initNode)
            val revisitedNodes = arrayListOf<Node<S>>()
            while (
                !((trace.last().isExpanded && trace.last().getOutgoingEdges().isEmpty())
                        || (trace.size > reachedSet.size * 3))
            ) {
                val lastNode = trace.last()
                if (!lastNode.isExpanded) {
                    val (newlyExpanded, revisited) = expand(
                        lastNode,
                        reachedSet,
                        fullPrec
                    )
                    revisitedNodes.addAll(revisited)
                    if (merged[lastNode]!!.first.size == 1)
                        merged[lastNode] = setOf(lastNode) to lastNode.getOutgoingEdges()

                    for (newNode in newlyExpanded) {
                        newNode.isTargetNode = targetExpression.eval(newNode.state) == True()

                        // treating each node as its own EC at first so that value computations can be done
                        // solely based on the _merged_ map
                        merged[newNode] = setOf(newNode) to newNode.getOutgoingEdges()
                        if (newNode.isTargetNode) {
                            U[newNode] = 1.0
                            L[newNode] = 1.0
                        } else {
                            U[newNode] = 1.0
                            L[newNode] = 0.0
                        }
                    }

                    if (lastNode.getOutgoingEdges().isEmpty()) {
                        if (lastNode.isTargetNode)
                            L[lastNode] = 1.0
                        else
                            U[lastNode] = 0.0
                        break
                    }
                }

                val nextNode = successorSelection(lastNode, U, L, goal)
                trace.add(nextNode)
            }

            while (revisitedNodes.isNotEmpty()) {
                val node = revisitedNodes.first()

                val mec = findMEC(node)
                val edgesLeavingMEC = mec.flatMap {
                    it.getOutgoingEdges().filter { it.support.any { it !in mec } }
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
                        it.expectedValue { U.getValue(it) }
                    }
                ) ?: 1.0)
                val lnew = if (Lnew[node] == 1.0) 1.0 else (goal.select(
                    merged[node]!!.second.map { it.expectedValue { L.getValue(it) } }
                ) ?: 0.0)

                for (siblingNode in merged[node]!!.first) {
                    Unew[siblingNode] = unew
                    Lnew[siblingNode] = lnew
                }
            }
            U = Unew
            L = Lnew
        }

        timer.stop()
        println("Final nodes: ${reachedSet.size}")
        println("Total time (ms): ${timer.elapsed(TimeUnit.MILLISECONDS)}")

        return U[initNode]!!
    }
    
    private fun fullyExplored(
        getStdCommands: (S) -> Collection<ProbabilisticCommand<A>>,
        getTargetExpression: (S) -> Expr<BoolType>,
        initState: S,
        goal: Goal,
        threshold: Double,
        fullPrec: ExplPrec,
        solverSupplier:
            (threshold: Double,
             rewardFunction: GameRewardFunction<Node<S>, FiniteDistribution<Node<S>>>,
             initializer: SGSolutionInitializer<Node<S>, FiniteDistribution<Node<S>>>)
        -> StochasticGameSolver<Node<S>, FiniteDistribution<Node<S>>>
    ): Double {
        val timer = Stopwatch.createStarted()

        val initNode = Node<S>(initState)
        val waitlist: Queue<Node<S>> = ArrayDeque()
        waitlist.add(initNode)
        val reachedSet = hashMapOf(initState to initNode)
        val allNodes = arrayListOf(initNode)
        while (!waitlist.isEmpty()) {
            val node = waitlist.remove()
            if(!node.isTargetNode) {
                val (newNodes, _) = expand(
                    node,
                    reachedSet,
                    fullPrec
                )
                waitlist.addAll(newNodes)
                allNodes.addAll(newNodes)
            }
        }

        timer.stop()
        val explorationTime = timer.elapsed(TimeUnit.MILLISECONDS)
        println("Exploration time (ms): $explorationTime")
        timer.reset()
        timer.start()

        val game = object : StochasticGame<Node<S>, FiniteDistribution<Node<S>>> {
            override val initialNode: Node<S>
                get() = initNode

            override fun getAllNodes(): Collection<Node<S>> = allNodes

            override fun getPlayer(node: Node<S>): Int = 0

            override fun getResult(node: Node<S>, action: FiniteDistribution<Node<S>>)
                    = action

            override fun getAvailableActions(node: Node<S>)
                    = node.getOutgoingEdges()

        }

        val rewardFunction =
            TargetRewardFunction<Node<S>, FiniteDistribution<Node<S>>> {
                it.isTargetNode
            }
        val initializer =
            if(useQualitativePreprocessing) MDPAlmostSureTargetInitializer(game, goal, Node<S>::isTargetNode)
            else TargetSetLowerInitializer {
                it.isTargetNode
            }


        val quantSolver = solverSupplier(threshold, rewardFunction, initializer)

        val analysisTask = AnalysisTask(game, {goal})
        println("All nodes: ${allNodes.size}")
        val values = quantSolver.solve(analysisTask)

        timer.stop()
        val probTime = timer.elapsed(TimeUnit.MILLISECONDS)
        println("Probability computation time (ms): $probTime")
        println("Total time (ms): ${explorationTime+probTime}")
        println("All nodes: ${reachedSet.size}")

        return values[initNode]!!
    }

    private fun expand(
        node: Node<S>,
        reachedSet: HashMap<S, Node<S>>,
        fullPrec: ExplPrec
    ): Pair<List<Node<S>>, List<Node<S>>> {
        val stdCommands = getStdCommands(node.state)
        node.isExpanded = true
        if(node.isTargetNode) return Pair(listOf(),listOf())

        val currState = node.state
        val newChildren = arrayListOf<Node<S>>()
        val revisited = arrayListOf<Node<S>>()
        for (cmd in stdCommands) {
            if (isEnabled(currState, cmd)) {
                val target = cmd.result.transform { a ->
                    val nextState =
                        transFunc
                            .getSuccStates(currState, a, fullPrec)
                            // TODO: this works only with deterministic actions now
                            .first()
                    val newNode = if(reachedSet.containsKey(nextState)) {
                        val n = reachedSet.getValue(nextState)
                        revisited.add(n)
                        n
                    } else {
                        val n = Node<S>(nextState)
                        newChildren.add(n)
                        reachedSet[nextState] = n
                        n.isTargetNode =
                            getTargetExpression(n.state).eval(n.state) == True()
                        n
                    }
                    newNode
                }
                node.createEdge(target)
            }
        }
        return Pair(newChildren, revisited)
    }

    private fun isEnabled(state: ExplState, cmd: ProbabilisticCommand<A>): Boolean {
        return cmd.guard.eval(state) == True()
    }

    private fun findMEC(root: Node<S>): Set<Node<S>> {
        fun findSCC(
            root: Node<S>,
            availableEdges: (Node<S>) -> List<FiniteDistribution<Node<S>>>
        ): Set<Node<S>> {
            val stack = Stack<Node<S>>()
            val lowlink = hashMapOf<Node<S>, Int>()
            val index = hashMapOf<Node<S>, Int>()
            var currIndex = 0

            fun strongConnect(n: Node<S>): Set<Node<S>> {
                index[n] = currIndex
                lowlink[n] = currIndex++
                stack.push(n)

                val successors =
                    availableEdges(n).flatMap { it.support.map { it } }.toSet()
                for (m in successors) {
                    if (m !in index) {
                        strongConnect(m)
                        lowlink[n] = min(lowlink[n]!!, lowlink[m]!!)
                    } else if (stack.contains(m)) {
                        lowlink[n] = min(lowlink[n]!!, index[m]!!)
                    }
                }

                val scc = hashSetOf<Node<S>>()
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

        var scc: Set<Node<S>> = hashSetOf()
        var availableEdges: (Node<S>) -> List<FiniteDistribution<Node<S>>> = Node<S>::getOutgoingEdges
        do {
            val prevSCC = scc
            scc = findSCC(root, availableEdges)
            availableEdges = { n: Node<S> ->
                n.getOutgoingEdges().filter { it.support.all { it in scc } }
            }
        } while (scc.size != prevSCC.size)
        return scc
    }
}