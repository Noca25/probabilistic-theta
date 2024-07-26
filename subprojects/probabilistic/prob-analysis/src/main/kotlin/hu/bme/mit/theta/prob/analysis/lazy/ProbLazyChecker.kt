package hu.bme.mit.theta.prob.analysis.lazy

import com.google.common.base.Stopwatch
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.common.logging.ConsoleLogger
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs.Not
import hu.bme.mit.theta.core.type.booltype.BoolExprs.True
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.probabilistic.*
import hu.bme.mit.theta.probabilistic.FiniteDistribution.Companion.dirac
import hu.bme.mit.theta.probabilistic.gamesolvers.*
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.FallbackInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.MDPAlmostSureTargetInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.TargetSetLowerInitializer
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.set
import kotlin.math.min
import kotlin.random.Random

class ProbLazyChecker<SC : ExprState, SA : ExprState, A : StmtAction>(
    // Model properties
    private val getStdCommands: (SC) -> Collection<ProbabilisticCommand<A>>,
    private val getErrorCommands: (SC) -> Collection<ProbabilisticCommand<A>>,
    private val initState: SC,
    private val topInit: SA,

    private val domain: LazyDomain<SC, SA, A>,

    private val goal: Goal,

    // Checker Configuration
    private val useMayStandard: Boolean = true,
    private val useMustStandard: Boolean = false,
    private val useMayTarget: Boolean = true,
    private val useMustTarget: Boolean = false,
    private val verboseLogging: Boolean = false,
    private val logger: Logger = ConsoleLogger(Logger.Level.VERBOSE),
    private val resetOnUncover: Boolean = true,
    private val useMonotonicBellman: Boolean = false,
    private val useSeq: Boolean = false,
    private val useGameRefinement: Boolean = false,
    private val useQualitativePreprocessing: Boolean = false,
    private val mergeSameSCNodes: Boolean = true
) {
    private var numCoveredNodes = 0
    private var numRealCovers = 0

    private fun reset() {
        waitlist.clear()
        numCoveredNodes = 0
        numRealCovers = 0
    }

    init {
        if(!(useMayStandard || useMustStandard)) throw RuntimeException("No abstraction type (must/may/both) specified!")
    }

    val waitlist = ArrayDeque<Node>()

    private var nextNodeId = 0

    var currReachedSet: TrieReachedSet<Node, *>? = null
    val globalScToNode: HashMap<SC, List<Node>>? =
        if(mergeSameSCNodes) hashMapOf() else null

    inner class Node(
        val sc: SC, sa: SA
    ): ExpandableNode<Node> {
        var sa: SA = sa
            private set(v) {
                val tracked = currReachedSet?.delete(this) ?: false
                field = v
                if(tracked) currReachedSet?.add(this)
            }

        var onUncover: ((Node)->Unit)? = null

        val id: Int = nextNodeId++

        var isExpanded = false

        fun isComplete() = isExpanded || isCovered || isErrorNode
        override fun isExpanded(): Boolean {
            return isExpanded
        }

        override fun expand(): ExpansionResult<Node> {
            return this@ProbLazyChecker.expand(
                this,
                getStdCommands(this.sc),
                getErrorCommands(this.sc),
                if(mergeSameSCNodes) globalScToNode else null
            )
        }

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
        fun createEdge(
            target: FiniteDistribution<Pair<A, Node>>,
            guard: Expr<BoolType>,
            surelyEnabled: Boolean,
            relatedCommand: ProbabilisticCommand<A>
        ): Edge {
            val newEdge = Edge(this, target, guard, surelyEnabled, relatedCommand)
            outEdges.add(newEdge)
            target.support.forEach { (a, n) ->
                n.backEdges.add(newEdge)
            }
            return newEdge
        }

        private var realCovered = false
        fun coverWith(coverer: Node) {
            require(!isCovered)
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

        fun strengthenWithSeq(toblock: Expr<BoolType>) {
            var currNode = this
            val nodes = arrayListOf(currNode)
            val guards = arrayListOf<Expr<BoolType>>()
            val actions = arrayListOf<A>()

            // TODO: better handling of the init node
            while (currNode.backEdges.isNotEmpty() && currNode.id != 0) {
                val backEdge = currNode.backEdges.first()
                guards.add(0, backEdge.guard)
                actions.add(0, backEdge.getActionFor(currNode))
                currNode = backEdge.source
                require(!nodes.contains(currNode))
                nodes.add(0, currNode)
            }

            val newLabels = domain.blockSeq(nodes, guards, actions, toblock)
            val forceCovers = arrayListOf<Node>()

            // As only a single path to the root can be strengthened at once, we need to deal with the other parents later
            val secondaryParentStrengthenings = arrayListOf(this to this.backEdges.drop(1))

            for ((i, node) in nodes.withIndex()) {
                if(newLabels[i] == node.sa) continue
                node.sa = newLabels[i]
                secondaryParentStrengthenings.add(node to node.backEdges.drop(1))

                val cpy = ArrayList(node.coveredNodes) // copy because removeCover() modifies it inside the loop
                for (coveredNode in cpy) {
                    if (!domain.checkContainment(coveredNode.sc, node.sa)) {
                        coveredNode.removeCover()
                        require(coveredNode !in waitlist)
                        waitlist.add(coveredNode)
                    } else {
                        forceCovers.add(coveredNode)
                    }
                }
                forceCovers.forEach {
                    if(it.isCovered) // the previous strengthenForCovering calls might have removed the cover of the current node
                        it.strengthenForCovering()
                }
            }

            for ((node, inEdges) in secondaryParentStrengthenings) {
                for (inEdge in inEdges) {
                    val action = inEdge.getActionFor(node)
                    val preImage = domain.preImage(node.sa, action)
                    inEdge.source.strengthenWithSeq(Not(preImage))
                }
            }
        }

        fun changeAbstractLabel(
            newLabel: SA
        ) {
            if (newLabel == sa) return
            sa = newLabel
            val cpy = ArrayList(coveredNodes)
            for (coveredNode in cpy) { // copy because removeCover() modifies it inside the loop
                if (!coveredNode.isCovered) continue // strengthening of other covered nodes might have removed the cover
                if (!domain.checkContainment(coveredNode.sc, this.sa)) {
                    coveredNode.removeCover()
                    waitlist.add(coveredNode)
                } else {
                    coveredNode.strengthenForCovering()
                }
            }

            // strengthening the parent
            strengthenParents()
        }

        fun strengthenParents() {
            for (backEdge in backEdges) {
                val parent = backEdge.source
                val action = backEdge.getActionFor(this)
                val preImage = domain.preImage(this.sa, action)
                if (useSeq) {
                    parent.strengthenWithSeq(Not(preImage))
                }
                else {
                    val constrainedToPreImage = domain.block(
                        parent.sa,
                        Not(preImage),
                        parent.sc
                    )
                    parent.changeAbstractLabel(constrainedToPreImage)
                }
            }
        }

        fun markAsErrorNode() {
            isErrorNode = true
        }

        fun strengthenForCovering() {
            require(isCovered)
            val coverer = coveringNode!!
            if (!domain.isLeq(sa, coverer.sa)) {
                if (useSeq) strengthenWithSeq(Not(coverer.sa.toExpr()))
                else {
                    val newExpr =
                        if(sa.toExpr() == True()) coverer.sa
                        else domain.block(sa, Not(coverer.sa.toExpr()), sc)
                    changeAbstractLabel(newExpr)
                }
            }
        }

        fun strengthenAgainstCommand(
            c: ProbabilisticCommand<A>,
            negate: Boolean = false
        ) {
            val toblock =
                if (negate) Not(c.guard)
                else c.guard

            if(useSeq) strengthenWithSeq(toblock)
            else {
                val modifiedAbstract = domain.block(sa, toblock, sc)
                changeAbstractLabel(modifiedAbstract)
            }
        }

        fun strengthenAgainstCommands(
            negatedCommands: List<ProbabilisticCommand<A>>,
            nonNegatedCommands: List<ProbabilisticCommand<A>>
        ) {
            val toblock =
                SmartBoolExprs.Or(
                negatedCommands.map { Not(it.guard) } + nonNegatedCommands.map { it.guard })

            if(useSeq) strengthenWithSeq(toblock)
            else {
                val modifiedAbstract = domain.block(sa, toblock, sc)
                changeAbstractLabel(modifiedAbstract)
            }
        }

        override fun toString(): String {
            return "Node[$id](c: $sc, a: $sa)"
        }

        fun getChildren(): List<Node> = this.getOutgoingEdges().flatMap { it.targetList.map { it.second } }

        /**
         * Recursively get all action-edge descendants. Does not traverse cover edges.
         */
        fun getDescendants(): List<Node> {
            val children = getChildren()
            return children + children.flatMap(ProbLazyChecker<SC, SA, A>.Node::getDescendants)
        }
    }

    inner class Edge(
        val source: Node, val target: FiniteDistribution<Pair<A, Node>>, val guard: Expr<BoolType>,
        surelyEnabled: Boolean, val relatedCommand: ProbabilisticCommand<A>
    ) {
        var targetList = target.support.toList()

        var surelyEnabled = surelyEnabled
            private set

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

        fun makeSurelyEnabled() {
            surelyEnabled = true
        }

        override fun toString(): String {
            return target.transform { it.first }.toString()
        }
    }

    private val random = Random(123)
    fun randomSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal,
        merged: Map<Node, Pair<Set<Node>, List<Edge>>>
    ): Node {
        // first we select the best action according to U if maxing/L if mining so that the policy is optimistic
        // O for optimistic
        val O = if (goal == Goal.MAX) U else L
        val actionVals = merged[currNode]!!.second.associateWith {
            it.target.expectedValue { O.getValue(it.second) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        // then sample from its result
        val result = best.target.sample(random)
        return result.second
    }

    fun diffBasedSelection(
    currNode: Node,
    U: Map<Node, Double>, L: Map<Node, Double>,
    goal: Goal,
    merged: Map<Node, Pair<Set<Node>, List<Edge>>>
    ): Node {
        val O = if (goal == Goal.MAX) U else L
        val actionVals = merged[currNode]!!.second.associateWith {
            it.target.expectedValue { O.getValue(it.second) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        val nextNodes = best.targetList
        var sum = 0.0
        val pmf = nextNodes.associateWith {
            val d = U[it.second]!! - L[it.second]!!
            sum += d
            d
        }.toMutableMap()

        if(sum == 0.0) {
            // If every successor has already converged, we chose uniformly
            // (should actually stop the simulation, but that is the responsibility of the BRTDP loop)
            return nextNodes.random(random).second
        }
        else {
            for (nextNode in nextNodes) {
                pmf[nextNode] = pmf[nextNode]!! / sum
            }
            val result = FiniteDistribution(pmf).sample(random)
            return result.second
        }
    }

    fun weightedRandomSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal,
        merged: Map<Node, Pair<Set<Node>, List<Edge>>>
    ): Node {
        val O = if (goal == Goal.MAX) U else L
        val actionVals = merged[currNode]!!.second.associateWith {
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
            return actionResult.support.random(random).second
        }
        val pmf = weights.associate { it.first to it.second / sum }
        val result = FiniteDistribution(pmf).sample(random)
        return result
    }

    fun roundRobinSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal,
        merged: Map<Node, Pair<Set<Node>, List<Edge>>>
    ): Node {
        val O = if (goal == Goal.MAX) U else L
        val actionVals = merged[currNode]!!.second.associateWith {
            it.target.expectedValue { O.getValue(it.second) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        return best.chooseNextRR().second
    }

    fun findMEC(
        root: Node,
        initAvailableEdges: (Node)->List<Edge> = ProbLazyChecker<SC, SA, A>.Node::getOutgoingEdges
    ): Set<Node> {
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
        var availableEdges: (Node) -> List<Edge> = initAvailableEdges
        do {
            val prevSCC = scc
            scc = findSCC(root, availableEdges)
            availableEdges = { n: ProbLazyChecker<SC, SA, A>.Node ->
                initAvailableEdges(n).filter { it.targetList.all { it.second in scc } }
            }
        } while (scc.size != prevSCC.size)
        return scc
    }

    fun brtdp(
        successorSelection:
            (
            currNode: Node,
            U: Map<Node, Double>, L: Map<Node, Double>,
            goal: Goal,
            merged: Map<Node, Pair<Set<Node>, List<Edge>>>
        ) -> Node,
        threshold: Double = 1e-7
    ) = if(useGameRefinement) brtdpWithGameRefinement(successorSelection, threshold)
        else simpleBrtdp(successorSelection, threshold)

    fun simpleBrtdp(
        successorSelection:
            (
            currNode: Node,
            U: Map<Node, Double>, L: Map<Node, Double>,
            goal: Goal,
            merged: Map<Node, Pair<Set<Node>, List<Edge>>>
        ) -> Node,
        threshold: Double = 1e-7
    ): Double {
        reset()
        val timer = Stopwatch.createStarted()
        val initNode = Node(initState, topInit)

        waitlist.add(initNode)
        
        val reachedSet = hashSetOf(initNode)
        val scToNode = hashMapOf(initNode.sc to arrayListOf(initNode))
        var U = hashMapOf(initNode to 1.0)
        var L = hashMapOf(initNode to 0.0)


        // virtually merged end components, also maintaining a set of edges that leave the EC for each of them
        val merged = hashMapOf(initNode to (setOf(initNode) to initNode.getOutgoingEdges()))
        var checkForMEC = arrayListOf<Node>()

        fun onUncover(n: Node) {
            if(resetOnUncover) {
                U[n] = 1.0
                L[n] = 0.0
            }
            for (node in merged[n]!!.first) {
                merged[node] = setOf(node) to node.getOutgoingEdges()
                if(node != n)
                    checkForMEC.add(n)
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
            checkForMEC.clear()

            // TODO: probability-based bound for trace length (see learning algorithms paper)
            while (
                !((trace.last().isExpanded && trace.last().getOutgoingEdges()
                    .isEmpty()) || (trace.size > reachedSet.size * 3))
            ) {
                val lastNode = trace.last()
                if (!lastNode.isExpanded) {
                    val (newlyDiscovered, revisited) = expand(
                        lastNode,
                        getStdCommands(lastNode.sc),
                        getErrorCommands(lastNode.sc),
                        if(mergeSameSCNodes) scToNode else null
                    )
                    if(mergeSameSCNodes) {
                        checkForMEC.addAll(revisited)
                    }
                    // as the node has just been expanded, its outgoing edges have been changed,
                    // so the merged map needs to be updated as well
                    if (merged[lastNode]!!.first.size == 1)
                        merged[lastNode] = setOf(lastNode) to lastNode.getOutgoingEdges()

                    for (newNode in newlyDiscovered) {
                        newNode.onUncover = ::onUncover

                        // treating each node as its own EC at first so that value computations can be done
                        // solely based on the _merged_ map
                        merged[newNode] = setOf(newNode) to newNode.getOutgoingEdges()
                        close(newNode, reachedSet, scToNode)
                        reachedSet.add(newNode)
                        if(newNode.sc in scToNode) {
                            scToNode[newNode.sc]!!.add(newNode)
                        } else {
                            scToNode[newNode.sc] = arrayListOf(newNode)
                        }
                        if (newNode.isCovered) {
                            checkForMEC.add(newNode)
                            U[newNode] = U.getOrDefault(newNode.coveringNode!!, 1.0)
                            L[newNode] = L.getOrDefault(newNode.coveringNode!!, 0.0)
                        } else if (newNode.isErrorNode) {
                            // TODO: this will actually never happen, as marking as error node happens during exploration
                            U[newNode] = 1.0
                            L[newNode] = 1.0
                        } else {
                            U[newNode] = 1.0
                            L[newNode] = 0.0
                        }
                    }

                    if (newlyDiscovered.isEmpty()) {
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

                // stop if a non-exitable MEC is reached
                if(merged[lastNode]!!.second.isEmpty())
                    break

                val nextNode = successorSelection(lastNode, U, L, goal, merged)


                trace.add(nextNode)
                // this would lead to infinite traces in MECs, but the trace length bound will stop the loop
                while (nextNode.isCovered)
                    trace.add(nextNode.coveringNode!!)
            }

            //TODO: remove and do something similar that makes sense
//            for (node in reachedSet) {
//                merged[node] = setOf(node) to node.getOutgoingEdges()
//            }
//            newCovered.clear()
//            newCovered.addAll(reachedSet.filter { it.isCovering })

            // for each new covered node added there is a chance that a new EC has been created
            while (checkForMEC.isNotEmpty()) {
                // the covered node then must be part of the EC, so it is enough to perform EC search on the subgraph
                // reachable from this node
                // this also means that at most one new EC can exist (which might be a superset of an existing one)
                val node = checkForMEC.first()

                val mec = findMEC(node)
                val edgesLeavingMEC = mec.flatMap {
                    it.getOutgoingEdges().filter {
                        it.targetList.any { it.second !in mec }
                    }
                }
                if(mergeSameSCNodes) TODO("handle self-loops (1-node MECS)")
                if (mec.size > 1) {
                    val zero = goal == Goal.MIN || (edgesLeavingMEC.isEmpty() && mec.all { it.isExpanded || it.isCovered })
                    for (n in mec) {
                        merged[n] = mec to edgesLeavingMEC
                        if (zero) U[n] = 0.0
                    }
                }
                checkForMEC.removeAll(mec)
            }

            val Unew = HashMap(U)
            val Lnew = HashMap(L)
            // value propagation using the merged map
            for (node in trace.reversed().distinct()) {
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

                    if (useMonotonicBellman) {
                        unew = minOf(unew, Unew[node]!!)
                        lnew = maxOf(lnew, Lnew[node]!!)
                    }

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
            "All nodes: ${reachedSet.size}\n" +
                    "Non-covered nodes: ${reachedSet.filterNot { it.isCovered }.size}\n" +
                    "Real covers: ${reachedSet.filter { it.isCovered && it.coveringNode!!.sc != it.sc }.size}\n" +
            "Result range: [${L[initNode]}, ${U[initNode]}], d=${U[initNode]!! - L[initNode]!!}"
        )
        timer.stop()
        println("Total time (ms): ${timer.elapsed(TimeUnit.MILLISECONDS)}")

        return U[initNode]!!
    }

    fun brtdpWithGameRefinement(
        successorSelection:
            (
            currNode: Node,
            U: Map<Node, Double>, L: Map<Node, Double>,
            goal: Goal,
            merged: Map<Node, Pair<Set<Node>, List<Edge>>>
        ) -> Node,
        threshold: Double = 1e-7
    ): Double {
        require(useMayStandard)
        require(!useMustStandard) {"Not implemented for lower-cover yet"}

        reset()
        val timer = Stopwatch.createStarted()
        val initNode = Node(initState, topInit)

        waitlist.add(initNode)

        val reachedSet = arrayListOf(initNode)
        val scToNode = hashMapOf(initNode.sc to arrayListOf(initNode))
        var UFull = hashMapOf(initNode to 1.0)
        var LFull = hashMapOf(initNode to 0.0)
        var UTrapped = hashMapOf(initNode to 1.0)
        var LTrapped = hashMapOf(initNode to 0.0)


        // virtually merged end components, also maintaining a set of edges that leave the EC for each of them
        val mergedFull = hashMapOf(initNode to (setOf(initNode) to initNode.getOutgoingEdges()))
        val mergedTrapped = hashMapOf(initNode to (setOf(initNode) to initNode.getOutgoingEdges()))
        val checkForMEC = hashSetOf<Node>()

        fun resetMerging(nodes: Collection<Node>) {
            for (node in nodes) {
                mergedFull[node] = setOf(node) to node.getOutgoingEdges()
                checkForMEC.add(node)
            }
            for (node in nodes) {
                mergedTrapped[node] = setOf(node) to node.getOutgoingEdges()
                    .filter { it.surelyEnabled }
            }
        }

        fun onUncover(n: Node) {
            if(resetOnUncover) {
                UFull[n] = 1.0
                UTrapped[n] = 1.0
                LFull[n] = 0.0
                LTrapped[n] = 0.0
            }
            resetMerging(mergedFull[n]!!.first)
        }

        var i = 0

        // Use the largest difference of the 4 approximations for stopping
        while ( UFull[initNode]!! - LTrapped[initNode]!! > threshold) {
            // logging for experiments
            i++
            if (i % 100 == 0)
                if(verboseLogging) {
                    println(
                        "$i: " +
                                "nodes: ${reachedSet.size}, non-covered: ${reachedSet.size-numCoveredNodes}, " +
                                " real covers: $numRealCovers " +
                                "[${LTrapped[initNode]}, ${UTrapped[initNode]}], d=${UTrapped[initNode]!! - LTrapped[initNode]!!}, " +
                                "[${LFull[initNode]}, ${UFull[initNode]}], d=${UFull[initNode]!! - LFull[initNode]!!}, " +
                                "time (ms): ${timer.elapsed(TimeUnit.MILLISECONDS)}"
                    )
                }

            //----------------------------------------------------------------------------------------------------------

            // simulate a single trace
            val trace = arrayListOf(initNode)
            checkForMEC.clear()

            // TODO: probability-based bound for trace length (see learning algorithms paper)
            while (
                !((trace.last().isExpanded && trace.last().getOutgoingEdges()
                    .isEmpty()) || (trace.size > reachedSet.size * 3))
            ) {
                val lastNode = trace.last()
                if (!lastNode.isExpanded) {
                    require(!lastNode.isCovered)
                    val (newlyDiscovered, revisited) = expand(
                        lastNode,
                        getStdCommands(lastNode.sc),
                        getErrorCommands(lastNode.sc),
                        if(mergeSameSCNodes) scToNode else null
                    )
                    if(mergeSameSCNodes) {
                        checkForMEC.addAll(revisited)
                    }

                    require(mergedFull[lastNode]!!.first.size == 1)
                    require(mergedTrapped[lastNode]!!.first.size == 1)
                    // as the node has just been expanded, its outgoing edges have been changed,
                    // so the merged map needs to be updated as well
                    if (mergedFull[lastNode]!!.first.size == 1)
                        mergedFull[lastNode] = setOf(lastNode) to lastNode.getOutgoingEdges()
                    if (mergedTrapped[lastNode]!!.first.size == 1)
                        mergedTrapped[lastNode] = setOf(lastNode) to lastNode.getOutgoingEdges()
                            .filter { it.surelyEnabled }

                    for (newNode in newlyDiscovered) {
                        newNode.onUncover = ::onUncover

                        // treating each node as its own EC at first so that value computations can be done
                        // solely based on the _merged_ map
                        mergedFull[newNode] = setOf(newNode) to newNode.getOutgoingEdges() //this should always be empty
                        mergedTrapped[newNode] = setOf(newNode) to newNode.getOutgoingEdges()
                            .filter { it.surelyEnabled } //this will be "even more empty" should always be empty
                        close(newNode, reachedSet, scToNode)
                        reachedSet.add(newNode)
                        if(newNode.sc in scToNode) {
                            scToNode[newNode.sc]!!.add(newNode)
                        } else {
                            scToNode[newNode.sc] = arrayListOf(newNode)
                        }
                        if (newNode.isCovered) {
                            checkForMEC.add(newNode)
                            UFull[newNode] = UFull[newNode.coveringNode]!!//UFull.getOrDefault(newNode.coveringNode!!, 1.0)
                            LFull[newNode] = LFull[newNode.coveringNode]!!//LFull.getOrDefault(newNode.coveringNode!!, 0.0)
                            UTrapped[newNode] = UTrapped[newNode.coveringNode]!!//UTrapped.getOrDefault(newNode.coveringNode!!, 1.0)
                            LTrapped[newNode] = LTrapped[newNode.coveringNode]!!//LTrapped.getOrDefault(newNode.coveringNode!!, 0.0)
                        } else if (newNode.isErrorNode) {
                            // TODO: this will actually never happen, as marking as error node happens during exploration
                            UFull[newNode] = 1.0
                            UTrapped[newNode] = 1.0
                            LFull[newNode] = 1.0
                            LTrapped[newNode] = 1.0
                        } else {
                            UFull[newNode] = 1.0
                            UTrapped[newNode] = 1.0
                            LFull[newNode] = 0.0
                            LTrapped[newNode] = 0.0
                        }
                    }

                    if (newlyDiscovered.isEmpty()) {
                        // marking as error node is done during expanding the node,
                        // so the node might have become an error node since starting the core
                        if (lastNode.isErrorNode) {
                            LFull[lastNode] = 1.0
                            LTrapped[lastNode] = 1.0
                        }
                        // absorbing nodes can never lead to an error node
                        else {
                            UFull[lastNode] = 0.0
                            UTrapped[lastNode] = 0.0
                        }
                        break
                    }
                    if (!lastNode.isErrorNode && lastNode.getOutgoingEdges().none { it.surelyEnabled }) {
                        // The node is absorbing in the trapped MDP
                        UTrapped[lastNode] = 0.0
                    }
                }

                // stop if a non-exitable MEC is reached
                if(mergedFull[lastNode]!!.second.isEmpty())
                    break

                val nextNode =
                    if(UFull[initNode]!! - LFull[initNode]!! > UTrapped[initNode]!! - LTrapped[initNode]!!
                        || mergedTrapped[lastNode]!!.second.isEmpty())
                        successorSelection(lastNode, UFull, LFull, goal, mergedFull)
                    else
                        successorSelection(lastNode, UTrapped, LTrapped, goal, mergedTrapped)


                trace.add(nextNode)
                // this would lead to infinite traces in MECs, but the trace length bound will stop the loop
                while (trace.last().isCovered)
                    trace.add(trace.last().coveringNode!!)
            }

            //TODO: remove and do something similar that makes sense
//            for (node in reachedSet) {
//                merged[node] = setOf(node) to node.getOutgoingEdges()
//            }
//            newCovered.clear()
//            newCovered.addAll(reachedSet.filter { it.isCovering })

            // for each new covered node added there is a chance that a new EC has been created
            updateMECs(checkForMEC, mergedFull, mergedTrapped, UFull, UTrapped)

            val UFullnew = HashMap(UFull)
            val UTrappednew = HashMap(UTrapped)
            val LFullnew = HashMap(LFull)
            val LTrappednew = HashMap(LTrapped)
            // value propagation using the merged map
            for (node in trace.reversed().distinct()) {
                if (node.isCovered) {
                    UFullnew[node] = UFull.getValue(node.coveringNode!!)
                    UTrappednew[node] = UTrapped.getValue(node.coveringNode!!)
                    LFullnew[node] = LFull.getValue(node.coveringNode!!)
                    LTrappednew[node] = LTrapped.getValue(node.coveringNode!!)
                } else {
                    // TODO: based on rewards
                    var ufullnew = if (UFullnew[node] == 0.0) 0.0 else (goal.select(
                        mergedFull[node]!!.second.map {
                            it.target.expectedValue { UFull.getValue(it.second) }
                        }
                    ) ?: 1.0)
                    var utrappednew =
                        if (UTrappednew[node] == 0.0) 0.0
                        else if(node.isErrorNode || (!node.isExpanded && !node.isCovered)) 1.0
                        else (goal.select(
                        mergedTrapped[node]!!.second.map {
                            it.target.expectedValue { UTrapped.getValue(it.second) }
                        }
                    ) ?: 0.0)
                    var lfullnew = if (LFullnew[node] == 1.0) 1.0 else (goal.select(
                        mergedFull[node]!!.second.map { it.target.expectedValue { LFull.getValue(it.second) } }
                    ) ?: 0.0)
                    var ltrappednew = if (LTrappednew[node] == 1.0) 1.0 else (goal.select(
                        mergedTrapped[node]!!.second.map { it.target.expectedValue { LTrapped.getValue(it.second) } }
                    ) ?: 0.0)

                    require(ufullnew >= lfullnew)
                    require(utrappednew >= ltrappednew)

                    if (useMonotonicBellman) {
                        ufullnew = minOf(ufullnew, UFullnew[node]!!)
                        utrappednew = minOf(utrappednew, UTrappednew[node]!!)
                        lfullnew = maxOf(lfullnew, LFullnew[node]!!)
                        ltrappednew = maxOf(ltrappednew, LTrappednew[node]!!)
                    }

                    for (siblingNode in mergedFull[node]!!.first) {
                        UFullnew[siblingNode] = ufullnew
                        LFullnew[siblingNode] = lfullnew
                    }
                    for (siblingNode in mergedTrapped[node]!!.first) {
                        UTrappednew[siblingNode] = utrappednew
                        LTrappednew[siblingNode] = ltrappednew
                    }
                }
            }
            UFull = UFullnew
            UTrapped = UTrappednew
            LFull = LFullnew
            LTrapped = LTrappednew

            val abstractionDiff = (UFull[initNode]!! + LFull[initNode]!!) / 2 - (UTrapped[initNode]!! + LTrapped[initNode]!!) / 2
            if(
                abstractionDiff > (UFull[initNode]!! - LFull[initNode]!!)
                &&
                abstractionDiff > (UTrapped[initNode]!! - LTrapped[initNode]!!)
                ) {
                val maxDiffNode = reachedSet.filter { it.getOutgoingEdges().any { !it.surelyEnabled } }.maxByOrNull {
                    // Both terms would be halved for averaging, but argmax is preserved
                    (UFull[it]!! + LFull[it]!!) - (UTrapped[it]!! - LTrapped[it]!!)
                }!!
                println("Refined node: ${maxDiffNode.id}")
                for (outgoingEdge in maxDiffNode.getOutgoingEdges()) {
                    if (!outgoingEdge.surelyEnabled) {
                        maxDiffNode.strengthenAgainstCommand(outgoingEdge.relatedCommand, negate = true)
                        outgoingEdge.makeSurelyEnabled()
                    }
                }

                // reset the upper approximations in the trapped MDP as it is no longer an upper approx
                // the upper approx of the full MDP is still valid, so we set it to that instead of a trivial approx
                for (node in reachedSet) {
                    UTrapped[node] = UFull[node]!!
                }

                // recompute MECs:
                // covers have been removed -> less MECs
                // new edges have appeared in the trapped MDP -> more MECs

                val mec = mergedTrapped[maxDiffNode]!!.first
                resetMerging(mec)

                // strengthenings might have removed covers, and the new surelyEnabled edges can create new MECs in the trapped MDP
                updateMECs(checkForMEC, mergedFull, mergedTrapped, UFull, UTrapped)

                // No need to reset U and L here, as the game-based refinement is done through a regular strengthening operation,
                // which resets them for the appropriate nodes

            }
        }
        println(
            "final stats: " +
                    "nodes: ${reachedSet.size}, non-covered: ${reachedSet.filterNot { it.isCovered }.size}, " +
                    " real covers: ${reachedSet.filter { it.isCovered && it.coveringNode!!.sc != it.sc }.size} " +
                    "[${LTrapped[initNode]}, ${UTrapped[initNode]}], d=${UTrapped[initNode]!! - LTrapped[initNode]!!}" +
                    "[${LFull[initNode]}, ${UFull[initNode]}], d=${UFull[initNode]!! - LFull[initNode]!!}"
        )
        timer.stop()
        println("Total time (ms): ${timer.elapsed(TimeUnit.MILLISECONDS)}")

        return (UFull[initNode]!!+LFull[initNode]!!+UTrapped[initNode]!!+LTrapped[initNode]!!)/4
    }

    private fun updateMECs(
        newCovered: HashSet<Node>,
        mergedFull: HashMap<Node, Pair<Set<Node>, List<Edge>>>,
        mergedTrapped: HashMap<Node, Pair<Set<Node>, List<Edge>>>,
        UFull: HashMap<Node, Double>,
        UTrapped: HashMap<Node, Double>
    ) {
        while (newCovered.isNotEmpty()) {
            // the covered node then must be part of the EC, so it is enough to perform EC search on the subgraph
            // reachable from this node
            // this also means that at most one new EC can exist (which might be a superset of an existing one)
            val node = newCovered.first()

            val mecFull = findMEC(node)
            val edgesLeavingMEC = mecFull.flatMap {
                it.getOutgoingEdges().filter { it.targetList.any { it.second !in mecFull } }
            }
            if (mecFull.size > 1) {
                val zero =
                    goal == Goal.MIN || (edgesLeavingMEC.isEmpty() && mecFull.all { it.isExpanded || it.isCovered })
                for (n in mecFull) {
                    mergedFull[n] = mecFull to edgesLeavingMEC
                    if (zero) UFull[n] = 0.0
                }
            }
            newCovered.removeAll(mecFull)

            val mecTrapped = findMEC(node) { it.getOutgoingEdges().filter { it.surelyEnabled } }
            val edgesLeavingMECTrapped = mecTrapped.flatMap {
                it.getOutgoingEdges().filter { it.surelyEnabled && it.targetList.any { it.second !in mecTrapped } }
            }
            if (mecTrapped.size > 1) {
                val zero =
                    goal == Goal.MIN || (edgesLeavingMECTrapped.isEmpty() && mecTrapped.all { it.isExpanded || it.isCovered })
                for (n in mecTrapped) {
                    mergedTrapped[n] = mecTrapped to edgesLeavingMECTrapped
                    if (zero) UTrapped[n] = 0.0
                }
            }
        }
    }


    fun fullyExpanded(
        useBVI: Boolean = false,
        threshold: Double,
        extractKeys: (SA) -> List<*> = {_-> listOf(null) },
        timeout: Int = 0,
    ): Double {
        reset()
        val timer = Stopwatch.createStarted()

        //val reachedSet = hashSetOf(initNode)
        val (reachedSet, scToNode, initNode) = explore(extractKeys, timeout, timer, mergeSameSCNodes)

        timer.stop()
        val explorationTime = timer.elapsed(TimeUnit.MILLISECONDS)
        println("Exploration time (ms): $explorationTime")
        val nodes = reachedSet.getAll()

        println("All nodes: ${nodes.size}")
        println("Non-covered nodes: ${nodes.count { !it.isCovered }}")
        println("Strict covers: $numRealCovers")
        timer.reset()
        timer.start()
        //TODO: game refinement with trie
        val errorProb =
            if(useGameRefinement) computeErrorProbWithRefinement(
                initNode, nodes.toMutableSet(), scToNode, useBVI, threshold, threshold
            )
            else computeErrorProb(initNode, nodes, useBVI, threshold)
        timer.stop()
        val probTime = timer.elapsed(TimeUnit.MILLISECONDS)
        println("Probability computation time (ms): $probTime")
        println("Total time (ms): ${explorationTime+probTime}")
        return errorProb
    }

    private inner class ExplorationResult(
        val reachedSet: TrieReachedSet<Node, Any>,
        val scToNode: MutableMap<SC, ArrayList<Node>>,
        val initNode: Node
    ) {
        operator fun component1() = reachedSet
        operator fun component2() = scToNode
        operator fun component3() = initNode
    }

    private fun explore(
        extractKeys: (SA) -> List<*>,
        timeout: Int,
        timer: Stopwatch,
        mergeSameSCNodes: Boolean
    ): ExplorationResult {
        reset()
        val initNode = Node(initState, topInit)
        waitlist.add(initNode)
        val reachedSet = TrieReachedSet<Node, Any> {
            extractKeys(it.sa)
        }
        currReachedSet = reachedSet
        val scToNode = hashMapOf(initNode.sc to arrayListOf(initNode))

        while (!waitlist.isEmpty()) {
            if (timeout != 0 && timer.elapsed(TimeUnit.MILLISECONDS) > timeout)
                throw RuntimeException("Timeout")
            val n = waitlist.removeFirst()
            require(!n.isCovered)
            close(n, reachedSet, scToNode)
            if (n.isCovered) continue
            val (newlyDiscovered, revisited) = expand(
                n,
                getStdCommands(n.sc),
                getErrorCommands(n.sc),
                if (mergeSameSCNodes) scToNode else hashMapOf()
            )
            for (node in revisited) {
                if(node.isExpanded) {
                    node.strengthenParents()
                }
            }
            for (newNode in newlyDiscovered) {
                if (newNode.isCovered)
                    continue
                close(newNode, reachedSet, scToNode)
                if (newNode.sc in scToNode) {
                    scToNode[newNode.sc]!!.add(newNode)
                } else {
                    scToNode[newNode.sc] = arrayListOf(newNode)
                }
                if (!newNode.isCovered) {
                    if (newNode !in waitlist) waitlist.addFirst(newNode)
                } else {
                    waitlist.remove(newNode)
                }
                reachedSet.add(newNode)
            }
        }
        return ExplorationResult(reachedSet, scToNode, initNode)
    }

    private fun expand(
        n: Node,
        stdCommands: Collection<ProbabilisticCommand<A>>,
        errorCommands: Collection<ProbabilisticCommand<A>>,
        scToNode: Map<SC, List<Node>>? = null
    ): ExpansionResult<Node> {

        val negatedCommands = arrayListOf<ProbabilisticCommand<A>>()
        val nonNegatedCommands = arrayListOf<ProbabilisticCommand<A>>()
        for (cmd in errorCommands) {
            if (domain.isEnabled(n.sc, cmd)) {
                n.markAsErrorNode()
                if(useMustTarget && !domain.mustBeEnabled(n.sa, cmd)) {
                    n.strengthenAgainstCommand(cmd, true)
                }

                return ExpansionResult(emptyList(), emptyList()) // keep error nodes absorbing
            } else if (useMayTarget && domain.mayBeEnabled(n.sa, cmd)) {
                nonNegatedCommands.add(cmd)
            }
        }
        val newlyDiscovered = arrayListOf<Node>()
        val revisited = hashSetOf<Node>()
        for (cmd in stdCommands) {
            if (domain.isEnabled(n.sc, cmd)) {
                val target = cmd.result.transform { a ->
                    val nextState = domain.concreteTransFunc(n.sc, a)
                    if(scToNode != null
                        && nextState in scToNode
                        && scToNode[nextState]!!.isNotEmpty()) {
                        val revisNode = scToNode[nextState]!!.first()
                        revisited.add(revisNode)
                        a to revisNode
                    } else {
                        val newNode = Node(nextState, domain.topAfter(n.sa, a))
                        newlyDiscovered.add(newNode)
                        // TODO: move scToNode updates here
                        a to newNode
                    }
                }
                if(useMustStandard && !domain.mustBeEnabled(n.sa, cmd)) {
                    negatedCommands.add(cmd)
                }

                val surelyEnabled =
                    !useGameRefinement || ( // don't care about it if we don't use game refinement
                        (useMayStandard && useMustStandard) || // if an Exact-PARG is built, then the standard refinement takes care of it
                        (useMayStandard && domain.mustBeEnabled(n.sa, cmd))
                        || (useMustStandard && TODO("is this right?") && domain.mayBeEnabled(n.sa, cmd)))

                n.createEdge(target, cmd.guard, surelyEnabled, cmd)
            } else if (useMayStandard && domain.mayBeEnabled(n.sa, cmd)) {
                nonNegatedCommands.add(cmd)
            }
        }
        if(negatedCommands.isNotEmpty() || nonNegatedCommands.isNotEmpty())
            n.strengthenAgainstCommands(negatedCommands, nonNegatedCommands)

        n.isExpanded = true

        return ExpansionResult(newlyDiscovered, revisited.toList())
    }

    private fun close(node: Node, reachedSet: Collection<Node>, scToNode: Map<SC, List<Node>>) {
        scToNode[node.sc]?.find { it != node && !it.isCovered }?.let {
            node.coverWith(it)
            node.strengthenForCovering()
            return
        }
        for (otherNode in reachedSet) {
            if (otherNode != node && !otherNode.isCovered && domain.checkContainment(node.sc, otherNode.sa)) {
                node.coverWith(otherNode)
                node.strengthenForCovering()
                break
            }
        }
    }

    private fun close(node: Node, reachedSet: TrieReachedSet<Node, *>, scToNode: Map<SC, List<Node>>) {
        require(!node.isCovered)
        scToNode[node.sc]?.find { it != node && !it.isCovered }?.let {
            node.coverWith(it)
            node.strengthenForCovering()
            return
        }
        for (otherNode in reachedSet.get(node)) {
            if (otherNode != node && !otherNode.isCovered && domain.checkContainment(node.sc, otherNode.sa)) {
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
        val timer = Stopwatch.createStarted()
        val initializer =
            if(useQualitativePreprocessing)
                MDPAlmostSureTargetInitializer(parg, goal) { it.isErrorNode && !it.isCovered }
            else TargetSetLowerInitializer { it.isErrorNode && !it.isCovered }
        val quantSolver =
            if(useBVI) MDPBVISolver(rewardFunction, initializer, threshold)
            else VISolver(rewardFunction, initializer, threshold, useGS = false)

        val analysisTask = AnalysisTask(parg, { goal })
        val nodes = parg.getAllNodes()
        println("All nodes: ${nodes.size}")
        println("Non-covered nodes: ${nodes.filter { !it.isCovered }.size}")

        if(initializer.isKnown(parg.initialNode)) {
            timer.stop()
            val probTime = timer.elapsed(TimeUnit.MILLISECONDS)
            println("Precomputation sufficient, result: ${initializer.initialLowerBound(parg.initialNode)}")
            println("Probability computation time (ms): $probTime")
            return initializer.initialLowerBound(parg.initialNode)
        }

        val values = quantSolver.solve(analysisTask)
        timer.stop()
        val probTime = timer.elapsed(TimeUnit.MILLISECONDS)
        println("Probability computation time (ms): $probTime")

        return values[initNode]!!
    }

    inner class TrappedPARG(
        val init: Node,
        val reachedSet: Collection<Node>
    ) : ImplicitStochasticGame<Node, PARGAction>() {
        override val initialNode: Node
            get() = init

        override fun getAvailableActions(node: Node) =
            if (node.isCovered) listOf(CoverAction()) else node.getOutgoingEdges().filter {
                it.surelyEnabled
            }.map(::EdgeAction)

        override fun getResult(node: Node, action: PARGAction) =
            if (action is EdgeAction) action.e.target.transform { it.second }
            else dirac(node.coveringNode!!)

        override fun getPlayer(node: Node): Int = 0 // This is an MDP

        override fun getAllNodes(): Collection<Node> {
            return reachedSet
        }
    }

    private fun computeErrorProbWithRefinement(
        initNode: Node,
        reachedSet: MutableSet<Node>,
        scToNode: MutableMap<SC, ArrayList<Node>>,
        useBVI: Boolean,
        innerThreshold: Double,
        outerThreshold: Double
    ): Double {
        val rewardFunction = TargetRewardFunction<Node, PARGAction> { it.isErrorNode && !it.isCovered }
        lateinit var fullInitializer: SGSolutionInitializer<Node, PARGAction>
        lateinit var trappedInitializer: SGSolutionInitializer<Node, PARGAction>
        var parg = PARG(initNode, reachedSet)
        var trappedParg = TrappedPARG(initNode, reachedSet)

        var baseFullInitializer =
            if(useQualitativePreprocessing)
                MDPAlmostSureTargetInitializer(parg, goal) { it.isErrorNode && !it.isCovered }
            else TargetSetLowerInitializer { it.isErrorNode && !it.isCovered }
        var baseTrappedInitializer =
            if(useQualitativePreprocessing)
                MDPAlmostSureTargetInitializer(trappedParg, goal) { it.isErrorNode && !it.isCovered }
            else TargetSetLowerInitializer { it.isErrorNode && !it.isCovered }
        fullInitializer = baseFullInitializer
        trappedInitializer = baseTrappedInitializer

        while(true) {
            val fullAnalysisTask = AnalysisTask(parg, { goal })
            val trappedAnalysisTask = AnalysisTask(trappedParg, { goal })

            lateinit var lowerValues: Map<Node, Double>
            lateinit var upperValues: Map<Node, Double>

            if(useBVI) {
                val fullSolver = MDPBVISolver(rewardFunction, fullInitializer, innerThreshold)
                val trappedSolver = MDPBVISolver(rewardFunction, trappedInitializer, innerThreshold)

                val (Lfull, Ufull) = fullSolver.solveWithRange(fullAnalysisTask)
                val (Ltrapped, Utrapped) = trappedSolver.solveWithRange(trappedAnalysisTask)

                trappedInitializer =
                    FallbackInitializer(
                        Ltrapped, Ufull, baseTrappedInitializer, innerThreshold
                    )
                fullInitializer =
                    FallbackInitializer(
                        Ltrapped, Ufull, baseFullInitializer, innerThreshold
                    )
                lowerValues = Ltrapped
                upperValues = Ufull
            } else {
                val fullSolver = VISolver(rewardFunction, initializer =  fullInitializer, innerThreshold)
                val trappedSolver = VISolver(rewardFunction, initializer =  trappedInitializer, innerThreshold)

                val Lfull = fullSolver.solve(fullAnalysisTask)
                val Ltrapped = trappedSolver.solve(trappedAnalysisTask)

                trappedInitializer =
                    FallbackInitializer(
                        Ltrapped, hashMapOf(), baseTrappedInitializer, innerThreshold
                    )
                fullInitializer =
                    FallbackInitializer(
                        Ltrapped, hashMapOf(), baseFullInitializer, innerThreshold
                    )
                lowerValues = Ltrapped
                upperValues = Lfull
            }

            println("Init node val: [${lowerValues[initNode]}, ${upperValues[initNode]}] ")

            if(upperValues[initNode]!! - lowerValues[initNode]!! < outerThreshold)
                return (upperValues[initNode]!! + lowerValues[initNode]!!)/2

            refineMenuGame(reachedSet, scToNode, lowerValues, upperValues, trappedReachable(initNode))
            parg = PARG(initNode, reachedSet)
            trappedParg = TrappedPARG(initNode, reachedSet)
            baseFullInitializer =
                if(useQualitativePreprocessing)
                    MDPAlmostSureTargetInitializer(parg, goal) { it.isErrorNode && !it.isCovered }
                else TargetSetLowerInitializer { it.isErrorNode && !it.isCovered }
            baseTrappedInitializer =
                if(useQualitativePreprocessing)
                    MDPAlmostSureTargetInitializer(trappedParg, goal) { it.isErrorNode && !it.isCovered }
                else TargetSetLowerInitializer { it.isErrorNode && !it.isCovered }
        }
    }

    private fun trappedReachable(initNode: Node): Collection<Node> {
        // TODO: cache/compute iteratively
        val res = hashSetOf(initNode)
        val waitlist: Queue<Node> = ArrayDeque()
        waitlist.add(initNode)
        while (!waitlist.isEmpty()) {
            val node = waitlist.remove()
            if (node.isCovered) {
                if (node.coveringNode !in res) {
                    res.add(node.coveringNode!!)
                    waitlist.add(node.coveringNode)
                }
            } else {
                for (edge in node.getOutgoingEdges()) {
                    if (!edge.surelyEnabled) continue
                    for ((_, child) in edge.targetList) {
                        if (child !in res) {
                            res.add(child)
                            waitlist.add(child)
                        }
                    }
                }
            }
        }
        return res
    }

    fun refineMenuGame(
        reachedSet: MutableSet<Node>,
        scToNode: MutableMap<SC, ArrayList<Node>>,
        lowerValues: Map<Node, Double>,
        upperValues: Map<Node, Double>,
        allowedNodes: Collection<Node>
    ) {
        require(allowedNodes.isNotEmpty())
        val maxDiffNode = allowedNodes.filter { it.getOutgoingEdges().any {!it.surelyEnabled} }.maxByOrNull {
            upperValues[it]!! - lowerValues[it]!!
        }!!
        println("Refined node: ${maxDiffNode.id}")
        for (outgoingEdge in maxDiffNode.getOutgoingEdges()) {
            if(!outgoingEdge.surelyEnabled) {
                maxDiffNode.strengthenAgainstCommand(outgoingEdge.relatedCommand, negate = true)
                outgoingEdge.makeSurelyEnabled()
            }
        }
        while (!waitlist.isEmpty()) {
            val n = waitlist.removeFirst()
            val (newlyDiscovered, revisited) = expand(
                n,
                getStdCommands(n.sc),
                getErrorCommands(n.sc),
            )
            for (node in revisited) {
                if(node.isExpanded) {
                    node.strengthenParents()
                }
            }
            for (newNode in newlyDiscovered) {
                close(newNode, reachedSet, scToNode)
                if(newNode.sc in scToNode) {
                    scToNode[newNode.sc]!!.add(newNode)
                } else {
                    scToNode[newNode.sc] = arrayListOf(newNode)
                }
                if (!newNode.isCovered) {
                    waitlist.addFirst(newNode)
                }
                reachedSet.add(newNode)
            }
        }
    }

}