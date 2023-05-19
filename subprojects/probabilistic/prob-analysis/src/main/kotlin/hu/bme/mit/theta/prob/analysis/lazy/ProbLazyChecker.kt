package hu.bme.mit.theta.prob.analysis.lazy

import com.google.common.base.Stopwatch
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.common.logging.ConsoleLogger
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs.Not
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.probabilistic.*
import hu.bme.mit.theta.probabilistic.FiniteDistribution.Companion.dirac
import hu.bme.mit.theta.probabilistic.gamesolvers.MDPBVISolver
import hu.bme.mit.theta.probabilistic.gamesolvers.VISolver
import java.util.Objects
import java.util.Stack
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

class ProbLazyChecker<SC : ExprState, SA : ExprState, A : StmtAction>(
    private val getStdCommands: (SC) -> Collection<ProbabilisticCommand<A>>,
    private val getErrorCommands: (SC) -> Collection<ProbabilisticCommand<A>>,
    private val initState: SC,
    private val topInit: SA,
    private val checkContainment: (SC, SA) -> Boolean,
    private val isLeq: (SA, SA) -> Boolean,
    private val mayBeEnabled: (SA, ProbabilisticCommand<A>) -> Boolean,
    private val mustBeEnabled: (SA, ProbabilisticCommand<A>) -> Boolean,
    private val isEnabled: (SC, ProbabilisticCommand<A>) -> Boolean,
    private val concreteTransFunc: (SC, A) -> SC,
    private val block: (SA, Expr<BoolType>, SC) -> SA,
    private val postImage: (state: SA, action: A, guard: Expr<BoolType>) -> SA,
    private val preImage: (SA, A) -> Expr<BoolType>,
    private val topAfter: (SA, A) -> SA,
    private val goal: Goal,
    private val useMay: Boolean = true,
    private val useMust: Boolean = false,
    private val verboseLogging: Boolean = false,
    private val logger: Logger = ConsoleLogger(Logger.Level.VERBOSE),
    private val resetOnUncover: Boolean = true
) {
    private var numCoveredNodes = 0
    private var numRealCovers = 0

    private fun reset() {
        waitlist.clear()
        numCoveredNodes = 0
        numRealCovers = 0
    }

    init {
        if(!(useMay || useMust)) throw RuntimeException("No abstraction type (must/may/both) specified!")
    }

    val waitlist = ArrayDeque<Node>()

    private var nextNodeId = 0

    inner class Node(
        val sc: SC, sa: SA
    ) {
        var sa: SA = sa
            private set

        var onUncover: ((Node)->Unit)? = null

        val id: Int = nextNodeId++

        var isExpanded = false

        override fun hashCode(): Int {
            // as SA and the outgoing edges change throughout building the ARG,
            // and hash maps/sets are often used during this, the hashcode must not depend on them
            return Objects.hash(id)
        }

        private val outEdges = arrayListOf<Edge>()
        var backEdges = arrayListOf<Edge>()
        var coveringNode: Node? = null
            private set
        private val coveredNodes = arrayListOf<Node>()
        val isCovered: Boolean
            get() = coveringNode != null

        val isCovering: Boolean
            get() = coveredNodes.isNotEmpty()
        var isErrorNode = false
            private set

        fun getOutgoingEdges(): List<Edge> = outEdges
        fun createEdge(target: FiniteDistribution<Pair<A, Node>>, guard: Expr<BoolType>): Edge {
            val newEdge = Edge(this, target, guard)
            outEdges.add(newEdge)
            target.support.forEach { (a, n) ->
                n.backEdges.add(newEdge)
            }
            return newEdge
        }

        private var realCovered = false
        fun coverWith(coverer: Node) {
            numCoveredNodes++
            // equality checking of sc-s can be expensive if done often,
            // so we do this only if the number of real covers is logged
            if(verboseLogging && coverer.sc != this.sc) {
                realCovered = true
                numRealCovers++
            }
            coveringNode = coverer
            coverer.coveredNodes.add(this)
        }

        fun removeCover() {
            require(isCovered)
            numCoveredNodes--
            if(verboseLogging && realCovered) {
                realCovered = false
                numRealCovers--
            }
            coveringNode!!.coveredNodes.remove(this)
            coveringNode = null
            onUncover?.invoke(this)
        }

        fun changeAbstractLabel(
            newLabel: SA
        ) {
            if (newLabel == sa) return
            sa = newLabel
            for (coveredNode in ArrayList(coveredNodes)) { // copy because removeCover() modifies it inside the loop
                if (!checkContainment(coveredNode.sc, this.sa)) {
                    coveredNode.removeCover()
                    waitlist.add(coveredNode)
                } else {
                    coveredNode.strengthenForCovering()
                }
            }

            // strengthening the parent
            for (backEdge in backEdges) {
                val parent = backEdge.source
                val action = backEdge.getActionFor(this)
                val constrainedToPreimage = block(
                    parent.sa,
                    Not(preImage(this.sa, action)),
                    parent.sc
                )
                parent.changeAbstractLabel(constrainedToPreimage)
            }
        }

        fun markAsErrorNode() {
            isErrorNode = true
        }

        fun strengthenForCovering() {
            require(isCovered)
            val coverer = coveringNode!!
            if (!isLeq(sa, coverer.sa)) {
                changeAbstractLabel(block(sa, Not(coverer.sa.toExpr()), sc))
            }
        }

        fun strengthenAgainstCommand(
            c: ProbabilisticCommand<A>,
            negate: Boolean = false
        ) {
            val toBlock =
                if (negate) Not(c.guard)
                else c.guard
            val modifiedAbstract = block(sa, toBlock, sc)
            changeAbstractLabel(modifiedAbstract)
        }

        override fun toString(): String {
            return "Node[$id](c: $sc, a: $sa)"
        }
    }

    inner class Edge(
        val source: Node, val target: FiniteDistribution<Pair<A, Node>>, val guard: Expr<BoolType>
    ) {
        var targetList = target.support.toList()
        // used for round-robin strategy
        private var nextChoice = 0
        fun chooseNextRR(): Pair<A, Node> {
            val res = targetList[nextChoice]
            nextChoice = (nextChoice+1) % targetList.size
            return res
        }

        fun getActionFor(result: Node): A {
            for ((a, n) in target.support) {
                if (n == result) return a
            }
            throw IllegalArgumentException("$result not found in the targets of edge $this")
        }

        override fun toString(): String {
            return target.transform { it.first }.toString()
        }
    }

    private val random = Random(123)
    fun randomSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal
    ): Node {
        // first we select the best action according to U if maxing/L if mining so that the policy is optimistic
        // O for optimistic
        val O = if (goal == Goal.MAX) U else L
        val actionVals = currNode.getOutgoingEdges().associateWith {
            it.target.expectedValue { O.getValue(it.second) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        // then sample from its result
        val result = best.target.sample(random)
        return result.second
    }

    fun maxDiffSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal
    ): Node {
        val O = if (goal == Goal.MAX) U else L
        val actionVals = currNode.getOutgoingEdges().associateWith {
            it.target.expectedValue { O.getValue(it.second) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        val nextNodes = best.targetList
        val maxDiff = nextNodes.maxOf {
            U.getValue(it.second) - L.getValue(it.second)
        }
        val filtered = nextNodes.filter {
            U.getValue(it.second) - L.getValue(it.second) == maxDiff
        }
        val result = filtered[random.nextInt(filtered.size)]
        return result.second
    }

    fun weightedMaxSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal
    ): Node {
        val O = if (goal == Goal.MAX) U else L
        val actionVals = currNode.getOutgoingEdges().associateWith {
            it.target.expectedValue { O.getValue(it.second) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        val actionResult = best.target
        val weights = actionResult.support.map {
            it.second to actionResult[it] * (U[it.second]!!-L[it.second]!!)
        }
        val maxWeight = weights.maxOfOrNull { it.second } ?: 0.0
        val filtered = weights.filter { it.second == maxWeight }
        val result = filtered[random.nextInt(filtered.size)]
        return result.first
    }

    fun weightedRandomSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal
    ): Node {
        val O = if (goal == Goal.MAX) U else L
        val actionVals = currNode.getOutgoingEdges().associateWith {
            it.target.expectedValue { O.getValue(it.second) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        val actionResult = best.target
        val weights = actionResult.support.map {
            it.second to actionResult[it] * (U[it.second]!!-L[it.second]!!)
        }
        val sum = weights.sumOf { it.second }
        if(sum == 0.0) {
            return actionResult.support.toList()[random.nextInt(actionResult.support.size)].second
        }
        val pmf = weights.associate { it.first to it.second / sum }
        val result = FiniteDistribution(pmf).sample(random)
        return result
    }

    fun roundRobinSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal
    ): Node {
        val O = if (goal == Goal.MAX) U else L
        val actionVals = currNode.getOutgoingEdges().associateWith {
            it.target.expectedValue { O.getValue(it.second) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        return best.chooseNextRR().second
    }

    fun findMEC(root: Node): Set<Node> {
        fun findSCC(root: Node, availableEdges: (Node) -> List<Edge>): Set<Node> {
            val stack = Stack<Node>()
            val lowlink = hashMapOf<Node, Int>()
            val index = hashMapOf<Node, Int>()
            var currIndex = 0

            fun strongConnect(n: Node): Set<Node> {
                index[n] = currIndex
                lowlink[n] = currIndex++
                stack.push(n)

                val successors =
                    if (n.isCovered) setOf(n.coveringNode!!)
                    else availableEdges(n).flatMap { it.targetList.map { it.second } }.toSet()
                for (m in successors) {
                    if (m !in index) {
                        strongConnect(m)
                        lowlink[n] = min(lowlink[n]!!, lowlink[m]!!)
                    } else if (stack.contains(m)) {
                        lowlink[n] = min(lowlink[n]!!, index[m]!!)
                    }
                }

                val scc = hashSetOf<Node>()
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

        var scc: Set<Node> = hashSetOf()
        var availableEdges: (Node) -> List<Edge> = ProbLazyChecker<SC, SA, A>.Node::getOutgoingEdges
        do {
            val prevSCC = scc
            scc = findSCC(root, availableEdges)
            availableEdges = { n: ProbLazyChecker<SC, SA, A>.Node ->
                n.getOutgoingEdges().filter { it.targetList.all { it.second in scc } }
            }
        } while (scc.size != prevSCC.size)
        return scc
    }

    fun brtdp(
        successorSelection:
            (
            currNode: Node,
            U: Map<Node, Double>, L: Map<Node, Double>,
            goal: Goal
        ) -> Node,
        threshold: Double = 1e-7
    ): Double {
        reset()
        val timer = Stopwatch.createStarted()
        val initNode = Node(initState, topInit)

        waitlist.add(initNode)

        val reachedSet = arrayListOf(initNode)
        var U = hashMapOf(initNode to 1.0)
        var L = hashMapOf(initNode to 0.0)


        // virtually merged end components, also maintaining a set of edges that leave the EC for each of them
        val merged = hashMapOf(initNode to (setOf(initNode) to initNode.getOutgoingEdges()))
        var newCovered = arrayListOf<Node>()

        fun onUncover(n: Node) {
            U[n] = 1.0
            L[n] = 0.0
            for (node in merged[n]!!.first) {
                merged[node] = setOf(node) to node.getOutgoingEdges()
                if(node != n)
                    newCovered.add(n)
            }
        }
        var i = 0

//         while ( reachedSet.any{!it.isExpanded && !it.isCovered}) {
        while ( U[initNode]!! - L[initNode]!! > threshold) {
            // logging for experiments
            i++
            if (i % 100 == 0)
                if(verboseLogging) {
                    println(
                        "$i: " +
                        "nodes: ${reachedSet.size}, non-covered: ${reachedSet.size-numCoveredNodes}, " +
                                " real covers: $numRealCovers " +
                        "[${L[initNode]}, ${U[initNode]}], d=${U[initNode]!! - L[initNode]!!}, " +
                        "time (ms): ${timer.elapsed(TimeUnit.MILLISECONDS)}"
                    )
                }

            //----------------------------------------------------------------------------------------------------------

            // simulate a single trace
            val trace = arrayListOf(initNode)
            newCovered = arrayListOf<Node>()

            // TODO: probability-based bound for trace length (see learning algorithms paper)
            while (
                !((trace.last().isExpanded && trace.last().getOutgoingEdges()
                    .isEmpty()) || (trace.size > reachedSet.size * 3))
            ) {
                val lastNode = trace.last()
                if (!lastNode.isExpanded) {
                    val newNodes = expand(
                        lastNode,
                        getStdCommands(lastNode.sc),
                        getErrorCommands(lastNode.sc)
                    )
                    // as the node has just been expanded, its outgoing edges have been changed,
                    // so the merged map needs to be updated as well
                    if (merged[lastNode]!!.first.size == 1)
                        merged[lastNode] = setOf(lastNode) to lastNode.getOutgoingEdges()

                    for (newNode in newNodes) {
                        // treating each node as its own EC at first so that value computations can be done
                        // solely based on the _merged_ map
                        merged[newNode] = setOf(newNode) to newNode.getOutgoingEdges()
                        close(newNode, reachedSet)
                        reachedSet.add(newNode)
                        if (newNode.isCovered) {
                            newCovered.add(newNode)
                            U[newNode] = U.getOrDefault(newNode.coveringNode!!, 1.0)
                            L[newNode] = L.getOrDefault(newNode.coveringNode!!, 0.0)
                        } else if (newNode.isErrorNode) {
                            // TODO: this will actually never happen, as marking as error node happens during exploration
                            U[newNode] = 1.0
                            L[newNode] = 1.0
                        } else {
                            if(resetOnUncover)
                                newNode.onUncover = ::onUncover

                            U[newNode] = 1.0
                            L[newNode] = 0.0
                        }
                    }

                    if (newNodes.isEmpty()) {
                        // marking as error node is done during expanding the node,
                        // so the node might have become an error node since starting the core
                        if (lastNode.isErrorNode)
                            L[lastNode] = 1.0
                        // absorbing nodes can never lead to an error node
                        else
                            U[lastNode] = 0.0
                        break
                    }
                }

                val nextNode = successorSelection(lastNode, U, L, goal)
                trace.add(nextNode)
                // this would lead to infinite traces in MECs, but the trace length bound will stop the loop
                if (nextNode.isCovered)
                    trace.add(nextNode.coveringNode!!)
            }

            //TODO: remove and do something similar that makes sense
//            for (node in reachedSet) {
//                merged[node] = setOf(node) to node.getOutgoingEdges()
//            }
//            newCovered.clear()
//            newCovered.addAll(reachedSet.filter { it.isCovering })

            // for each new covered node added there is a chance that a new EC has been created
            while (newCovered.isNotEmpty()) {
                // the covered node then must be part of the EC, so it is enough to perform EC search on the subgraph
                // reachable from this node
                // this also means that at most one new EC can exist (which might be a superset of an existing one)
                val node = newCovered.first()

                val mec = findMEC(node)
                val edgesLeavingMEC = mec.flatMap {
                    it.getOutgoingEdges().filter { it.targetList.any { it.second !in mec } }
                }
                if (mec.size > 1) {
                    val zero = goal == Goal.MIN || (edgesLeavingMEC.isEmpty() && mec.all { it.isExpanded || it.isCovered })
                    for (n in mec) {
                        merged[n] = mec to edgesLeavingMEC
                        if (zero) U[n] = 0.0
                    }
                }
                newCovered.removeAll(mec)
            }

            val Unew = HashMap(U)
            val Lnew = HashMap(L)
            // value propagation using the merged map
            for (node in trace.reversed()) {
                if (node.isCovered) {
                    Unew[node] = U.getValue(node.coveringNode!!)
                    Lnew[node] = L.getValue(node.coveringNode!!)
                } else {
                    // TODO: based on rewards
                    var unew = if (Unew[node] == 0.0) 0.0 else (goal.select(
                        merged[node]!!.second.map {
                            it.target.expectedValue { U.getValue(it.second) }
                        }
                    ) ?: 1.0)
                    var lnew = if (Lnew[node] == 1.0) 1.0 else (goal.select(
                        merged[node]!!.second.map { it.target.expectedValue { L.getValue(it.second) } }
                    ) ?: 0.0)

                    for (siblingNode in merged[node]!!.first) {
                        Unew[siblingNode] = unew
                        Lnew[siblingNode] = lnew
                    }
                }
            }
            U = Unew
            L = Lnew
        }
        println(
            "final stats: " +
            "nodes: ${reachedSet.size}, non-covered: ${reachedSet.filterNot { it.isCovered }.size}, " +
                    " real covers: ${reachedSet.filter { it.isCovered && it.coveringNode!!.sc != it.sc }.size} " +
            "[${L[initNode]}, ${U[initNode]}], d=${U[initNode]!! - L[initNode]!!}"
        )
        timer.stop()
        println("Total time (ms): ${timer.elapsed(TimeUnit.MILLISECONDS)}")

        return U[initNode]!!
    }

    fun fullyExpanded(
        useBVI: Boolean = false,
        threshold: Double
    ): Double {
        reset()
        val timer = Stopwatch.createStarted()
        val initNode = Node(initState, topInit)

        waitlist.add(initNode)

        val reachedSet = arrayListOf(initNode)

        while (!waitlist.isEmpty()) {
            val n = waitlist.removeFirst()
            val newNodes = expand(
                n,
                getStdCommands(n.sc),
                getErrorCommands(n.sc),
            )
            for (newNode in newNodes) {
                close(newNode, reachedSet)
                if (!newNode.isCovered) {
                    waitlist.addFirst(newNode)
                }
                reachedSet.add(newNode)
            }
        }

        timer.stop()
        val explorationTime = timer.elapsed(TimeUnit.MILLISECONDS)
        println("Exploration time (ms): $explorationTime")
        timer.reset()
        timer.start()
        val errorProb = computeErrorProb(initNode, reachedSet, useBVI, threshold)
        timer.stop()
        val probTime = timer.elapsed(TimeUnit.MILLISECONDS)
        println("Probability computation time (ms): $probTime")
        println("Total time (ms): ${explorationTime+probTime}")
        return errorProb
    }

    private fun expand(
        n: Node,
        stdCommands: Collection<ProbabilisticCommand<A>>,
        errorCommands: Collection<ProbabilisticCommand<A>>,
    ): Collection<Node> {

        val children = arrayListOf<Node>()

        for (cmd in errorCommands) {
            if (isEnabled(n.sc, cmd)) {
                n.markAsErrorNode()
                if(useMust && !mustBeEnabled(n.sa, cmd)) {
                    n.strengthenAgainstCommand(cmd, true)
                }

                return children // keep error nodes absorbing
            } else if (useMay && mayBeEnabled(n.sa, cmd)) {
                n.strengthenAgainstCommand(cmd, false)
            }
        }
        for (cmd in stdCommands) {
            if (isEnabled(n.sc, cmd)) {
                val target = cmd.result.transform { a ->
                    val nextState = concreteTransFunc(n.sc, a)
                    val newNode = Node(nextState, topAfter(n.sa, a))
//                    val newNode = Node(nextState, nextState as SA)
                    children.add(newNode)
                    a to newNode
                }
                if(useMust && !mustBeEnabled(n.sa, cmd)) {
                    n.strengthenAgainstCommand(cmd, true)
                }

                n.createEdge(target, cmd.guard)
            } else if (useMay && mayBeEnabled(n.sa, cmd)) {
                n.strengthenAgainstCommand(cmd, false)
            }
        }

        n.isExpanded = true

        return children
    }

    private fun close(node: Node, reachedSet: Collection<Node>) {
        for (otherNode in reachedSet) {
            if (otherNode.sc == node.sc) {
                node.coverWith(otherNode)
                node.strengthenForCovering()
                return
            }
        }
        for (otherNode in reachedSet) {
            if (!otherNode.isCovered && checkContainment(node.sc, otherNode.sa)) {
                node.coverWith(otherNode)
                node.strengthenForCovering()
                break
            }
        }
    }

    abstract inner class PARGAction
    inner class EdgeAction(val e: Edge) : PARGAction() {
        override fun toString(): String {
            return e.toString()
        }
    }

    private inner class CoverAction : PARGAction() {

        override fun toString() = "<<cover>>"
    }


    inner class PARG(
        val init: Node,
        val reachedSet: Collection<Node>
    ) : ImplicitStochasticGame<Node, PARGAction>() {
        override val initialNode: Node
            get() = init

        override fun getAvailableActions(node: Node) =
            if (node.isCovered) listOf(CoverAction()) else node.getOutgoingEdges().map(::EdgeAction)

        override fun getResult(node: Node, action: PARGAction) =
            if (action is EdgeAction) action.e.target.transform { it.second }
            else dirac(node.coveringNode!!)

        override fun getPlayer(node: Node): Int = 0 // This is an MDP

    }

    private fun computeErrorProb(
        initNode: Node,
        reachedSet: Collection<Node>,
        useBVI: Boolean,
        threshold: Double
    ): Double {
        val parg = PARG(initNode, reachedSet)
        val rewardFunction = TargetRewardFunction<Node, PARGAction> { it.isErrorNode && !it.isCovered }
        val quantSolver =
            if(useBVI) MDPBVISolver(threshold, rewardFunction)
            else VISolver(threshold, rewardFunction, useGS = false)
        val analysisTask = AnalysisTask(parg, { goal })
        val nodes = parg.getAllNodes()
        println("All nodes: ${nodes.size}")
        println("Non-covered nodes: ${nodes.filter { !it.isCovered }.size}")
        val values = quantSolver.solve(analysisTask)
        return values[initNode]!!
    }

}