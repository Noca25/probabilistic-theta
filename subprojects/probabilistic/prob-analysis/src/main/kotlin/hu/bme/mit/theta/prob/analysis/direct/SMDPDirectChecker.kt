package hu.bme.mit.theta.prob.analysis.direct

import com.google.common.base.Stopwatch
import hu.bme.mit.theta.analysis.expl.ExplInitFunc
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expl.ExplStmtTransFunc
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.prob.analysis.jani.*
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.Algorithm.*
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.BRTDPStrategy.*
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.probabilistic.*
import hu.bme.mit.theta.probabilistic.gamesolvers.MDPBVISolver
import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.VISolver
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.MDPAlmostSureTargetInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.TargetSetLowerInitializer
import hu.bme.mit.theta.solver.Solver
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap
import kotlin.math.min
import kotlin.random.Random

class SMDPDirectChecker(
    val solver: Solver,
    val algorithm: SMDPLazyChecker.Algorithm,
    val verboseLogging: Boolean = false,
    val brtpStrategy: SMDPLazyChecker.BRTDPStrategy = DIFF_BASED,
    val threshold: Double = 1e-7,
    val useQualitativePreprocessing: Boolean = false
) {

    data class Node(val state: SMDPState<ExplState>) {
        companion object {
            private var nextId = 0
        }
        private val id = nextId++
        private val outEdges = arrayListOf<FiniteDistribution<Node>>()
        var isExpanded: Boolean = false
        var isErrorNode: Boolean = false
        fun getOutgoingEdges(): List<FiniteDistribution<Node>> = outEdges
        fun createEdge(target: FiniteDistribution<Node>) {
            outEdges.add(target)
        }

        override fun hashCode(): Int {
            return Objects.hashCode(this.id)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Node

            return id == other.id
        }
    }

    fun check(
        smdp: SMDP,
        smdpReachabilityTask: SMDPReachabilityTask
    ): Double {
        val initFunc = SmdpInitFunc<ExplState, ExplPrec>(
            ExplInitFunc.create(solver, smdp.getFullInitExpr()),
            smdp
        )
        val fullPrec = ExplPrec.of(smdp.getAllVars())
        val initStates = initFunc.getInitStates(fullPrec)
        if(initStates.size != 1)
            throw RuntimeException("initial state must be deterministic")

        val smdpLts = SmdpCommandLts<ExplState>(smdp)
        fun commandsWithPrecondition(state: SMDPState<ExplState>) =
            smdpLts.getCommandsFor(state).map { it.withPrecondition(smdpReachabilityTask.constraint) }

        val strat = when(brtpStrategy) {
            RANDOM -> this::randomSelection
            ROUND_ROBIN -> TODO()
            DIFF_BASED -> this::diffBasedSelection
            WEIGHTED_RANDOM -> this::weightedRandomSelection
        }

        return when(algorithm) {
            BRTDP -> brtdp(
                ::commandsWithPrecondition,
                smdpReachabilityTask.targetExpr,
                initStates.first(),
                smdpReachabilityTask.goal,
                strat,
                threshold,
                fullPrec
            )
            VI -> vi(
                ::commandsWithPrecondition,
                smdpReachabilityTask.targetExpr,
                initStates.first(),
                smdpReachabilityTask.goal,
                threshold,
                fullPrec,
                false
            )
            BVI -> vi(
                ::commandsWithPrecondition,
                smdpReachabilityTask.targetExpr,
                initStates.first(),
                smdpReachabilityTask.goal,
                threshold,
                fullPrec,
                true
            )
        }
    }
    private fun brtdp(
        getStdCommands: (SMDPState<ExplState>) -> Collection<ProbabilisticCommand<SMDPCommandAction>>,
        targetExpression: Expr<BoolType>,
        initState: SMDPState<ExplState>,
        goal: Goal,
        successorSelection:
            (currNode: Node, U: Map<Node, Double>, L: Map<Node, Double>, goal: Goal) -> Node,
        threshold: Double,
        fullPrec: ExplPrec
    ): Double {
        val timer = Stopwatch.createStarted()

        val initNode = Node(initState)
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
            val revisitedNodes = arrayListOf<Node>()
            while (
                !((trace.last().isExpanded && trace.last().getOutgoingEdges().isEmpty())
                        || (trace.size > reachedSet.size * 3))
            ) {
                val lastNode = trace.last()
                if (!lastNode.isExpanded) {
                    val (newlyExpanded, revisited) = expand(
                        lastNode,
                        getStdCommands(lastNode.state),
                        reachedSet,
                        fullPrec
                    )
                    revisitedNodes.addAll(revisited)
                    if (merged[lastNode]!!.first.size == 1)
                        merged[lastNode] = setOf(lastNode) to lastNode.getOutgoingEdges()

                    for (newNode in newlyExpanded) {
                        newNode.isErrorNode = targetExpression.eval(newNode.state.domainState) == True()

                        // treating each node as its own EC at first so that value computations can be done
                        // solely based on the _merged_ map
                        merged[newNode] = setOf(newNode) to newNode.getOutgoingEdges()
                        if (newNode.isErrorNode) {
                            U[newNode] = 1.0
                            L[newNode] = 1.0
                        } else {
                            U[newNode] = 1.0
                            L[newNode] = 0.0
                        }
                    }

                    if (lastNode.getOutgoingEdges().isEmpty()) {
                        if (lastNode.isErrorNode)
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

    private fun vi(
        getStdCommands: (SMDPState<ExplState>) -> Collection<ProbabilisticCommand<SMDPCommandAction>>,
        targetExpression: Expr<BoolType>,
        initState: SMDPState<ExplState>,
        goal: Goal,
        threshold: Double,
        fullPrec: ExplPrec,
        useBVI: Boolean = false
        ): Double {

        return fullyExplored(
            getStdCommands,
            targetExpression,
            initState,
            goal,
            threshold,
            fullPrec,
            if(useBVI) ::MDPBVISolver
            else { threshold, rewardFunction, initializer ->
                VISolver(threshold, rewardFunction, useGS = false, initializer)
            }
        )
    }

    private fun fullyExplored(
        getStdCommands: (SMDPState<ExplState>) -> Collection<ProbabilisticCommand<SMDPCommandAction>>,
        targetExpression: Expr<BoolType>,
        initState: SMDPState<ExplState>,
        goal: Goal,
        threshold: Double,
        fullPrec: ExplPrec,
        solverSupplier:
            (threshold: Double,
             rewardFunction: GameRewardFunction<Node, FiniteDistribution<Node>>,
             initializer: SGSolutionInitializer<Node, FiniteDistribution<Node>>)
        -> StochasticGameSolver<Node, FiniteDistribution<Node>>
    ): Double {
        val timer = Stopwatch.createStarted()

        val initNode = Node(initState)
        val waitlist: Queue<Node> = ArrayDeque()
        waitlist.add(initNode)
        val reachedSet = hashMapOf(initState to initNode)
        val allNodes = arrayListOf(initNode)
        while (!waitlist.isEmpty()) {
            val node = waitlist.remove()
            node.isErrorNode =
                targetExpression.eval(node.state.domainState) == True()
            if(!node.isErrorNode) {
                val (newNodes, _) = expand(
                    node,
                    getStdCommands(node.state),
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

        val game = object : StochasticGame<Node, FiniteDistribution<Node>> {
            override val initialNode: Node
                get() = initNode

            override fun getAllNodes(): Collection<Node> = allNodes

            override fun getPlayer(node: Node): Int = 0

            override fun getResult(node: Node, action: FiniteDistribution<Node>)
                    = action

            override fun getAvailableActions(node: Node)
                    = node.getOutgoingEdges()

        }

        val rewardFunction =
            TargetRewardFunction<Node, FiniteDistribution<Node>> {
                it.isErrorNode
            }
        val initializer =
            if(useQualitativePreprocessing) MDPAlmostSureTargetInitializer(game, goal, Node::isErrorNode)
            else TargetSetLowerInitializer<Node, FiniteDistribution<Node>> {
                it.isErrorNode
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

    private val transFunc = ExplStmtTransFunc.create(solver, 0)
    private fun expand(
        node: Node,
        stdCommands: Collection<ProbabilisticCommand<SMDPCommandAction>>,
        reachedSet: HashMap<SMDPState<ExplState>, Node>,
        fullPrec: ExplPrec
    ): Pair<List<Node>, List<Node>> {
        node.isExpanded = true
        if(node.isErrorNode) return Pair(listOf(),listOf())

        val currState = node.state
        val newChildren = arrayListOf<Node>()
        val revisited = arrayListOf<Node>()
        for (cmd in stdCommands) {
            if (isEnabled(currState.domainState, cmd)) {
                val target = cmd.result.transform { a ->
                    val nextDataState =
                        transFunc
                            .getSuccStates(currState.domainState, a, fullPrec)
                            .first()
                    val l = nextLocs(currState.locs, a.destination)
                    val nextState = SMDPState(nextDataState, l)
                    val newNode = if(reachedSet.containsKey(nextState)) {
                        val n = reachedSet.getValue(nextState)
                        revisited.add(n)
                        n
                    } else {
                        val n = Node(nextState)
                        newChildren.add(n)
                        reachedSet[nextState] = n
                        n
                    }
                    newNode
                }
                node.createEdge(target)
            }
        }
        return Pair(newChildren, revisited)
    }

    private fun isEnabled(state: ExplState, cmd: ProbabilisticCommand<SMDPCommandAction>): Boolean {
        return cmd.guard.eval(state) == True()
    }

    private fun findMEC(root: Node): Set<Node> {
        fun findSCC(
            root: Node,
            availableEdges: (Node) -> List<FiniteDistribution<Node>>
        ): Set<Node> {
            val stack = Stack<Node>()
            val lowlink = hashMapOf<Node, Int>()
            val index = hashMapOf<Node, Int>()
            var currIndex = 0

            fun strongConnect(n: Node): Set<Node> {
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
        var availableEdges: (Node) -> List<FiniteDistribution<Node>> = Node::getOutgoingEdges
        do {
            val prevSCC = scc
            scc = findSCC(root, availableEdges)
            availableEdges = { n: Node ->
                n.getOutgoingEdges().filter { it.support.all { it in scc } }
            }
        } while (scc.size != prevSCC.size)
        return scc
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
            it.expectedValue { O.getValue(it) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        // then sample from its result
        val result = best.sample(random)
        return result
    }

    fun diffBasedSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal
    ): Node {
        val O = if (goal == Goal.MAX) U else L
        val actionVals = currNode.getOutgoingEdges().associateWith {
            it.expectedValue { O.getValue(it) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        val nextNodes = best.support
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

    fun weightedRandomSelection(
        currNode: Node,
        U: Map<Node, Double>, L: Map<Node, Double>,
        goal: Goal
    ): Node {
        val O = if (goal == Goal.MAX) U else L
        val actionVals = currNode.getOutgoingEdges().associateWith {
            it.expectedValue { O.getValue(it) }
        }
        val bestValue = goal.select(actionVals.values)
        val bests = actionVals.filterValues { it == bestValue }.map { it.key }
        val best = bests[random.nextInt(bests.size)]
        val actionResult = best
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
}