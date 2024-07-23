package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.InitFunc
import hu.bme.mit.theta.analysis.PartialOrd
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.analysis.waitlist.FifoWaitlist
import hu.bme.mit.theta.analysis.waitlist.Waitlist
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommandLTS
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.FiniteDistribution.Companion.dirac
import hu.bme.mit.theta.probabilistic.ImplicitStochasticGame
import hu.bme.mit.theta.probabilistic.gamesolvers.ExpandableNode

class BLASTMenuGame<S : ExprState, A : StmtAction, P : Prec>(
    val lts: ProbabilisticCommandLTS<S, A>,
    val init: InitFunc<S, P>,
    val transFunc: MenuGameTransFunc<S, A, P>,
    val targetExpr: Expr<BoolType>,
    val maySatisfy: (S, Expr<BoolType>) -> Boolean,
    val mustSatisfy: (S, Expr<BoolType>) -> Boolean,
    val initialPrec: P,
    val ord: PartialOrd<S>,
    val extendPrec: P.(P) -> P
) : ImplicitStochasticGame<MenuGameNode<S, A>, MenuGameAction<S, A>>() {


    inner class BLASTMenuGameNode(
        val wrappedNode: MenuGameNode<S, A>,
        /**
         * The precision used when computing the post operator from this node
         */
        var supportPrecision: P
    ) : ExpandableNode<BLASTMenuGameNode> {
        val outgoingEdges = hashMapOf<MenuGameAction<S, A>, BLASTMenuGameEdge>()
        val incomingEdges = arrayListOf<BLASTMenuGameEdge>()

        var coveringNode: BLASTMenuGameNode? = null
        var coveredNodes: MutableList<BLASTMenuGameNode> = arrayListOf()

        private var expanded = false

        fun getLastCoverer(): BLASTMenuGameNode {
            var res = this
            while (true) res = res.coveringNode ?: return res
        }

        override fun isExpanded(): Boolean {
            return expanded
        }

        fun makeUnexpanded() {
            expanded = false
        }

        override fun expand(): Pair<List<BLASTMenuGameNode>, List<BLASTMenuGameNode>> {
            val newlyCreated = hashSetOf<BLASTMenuGameNode>()
            val revisited = hashSetOf<BLASTMenuGameNode>()
            for (action in this@BLASTMenuGame.getAvailableActions(this)) {
                if (action !in outgoingEdges) {
                    val expansionResult = expand(this, action)
                    newlyCreated.addAll(expansionResult.newlyCreated)
                    revisited.addAll(expansionResult.revisited)
                }
            }
            expanded = true
            // TODO: what if some node has been created for one of the actions and has been revisited by another?
            return newlyCreated.toList() to revisited.toList()
        }

        fun coverWith(coverer: BLASTMenuGameNode) {
            require(coverer != this)
            require(coveringNode == null)
            coveringNode = coverer
            coverer.coveredNodes.add(this)
        }

        fun removeCover() {
            require(coveringNode != null)
            coveringNode!!.coveredNodes.remove(this)
            coveringNode = null
        }

        fun extendSupportPrecision(newPrec: P) {
            supportPrecision.extendPrec(newPrec)
        }
    }


    inner class BLASTMenuGameEdge(
        val wrappedAction: MenuGameAction<S, A>,
        val start: BLASTMenuGameNode,
        val end: FiniteDistribution<BLASTMenuGameNode>
    )

    private fun createEdge(
        wrappedAction: MenuGameAction<S, A>,
        start: BLASTMenuGameNode,
        end: FiniteDistribution<BLASTMenuGameNode>
    ): BLASTMenuGameEdge {
        val newEdge = BLASTMenuGameEdge(wrappedAction, start, end)
        start.outgoingEdges[wrappedAction] = newEdge
        end.support.forEach { it.incomingEdges.add(newEdge) }
        return newEdge
    }

    private lateinit var _initialNode: BLASTMenuGameNode

    init {
        initialize(initialPrec)
    }

    private fun initialize(initialNodePrec: P) {
        val initState = init.getInitStates(initialNodePrec).first() // TODO: cannot handle multiple abstract inits yet
        val mayBeTarget = maySatisfy(initState, targetExpr)
        val mustBeTarget = mustSatisfy(initState, targetExpr)
        _initialNode = BLASTMenuGameNode(
            MenuGameNode.StateNode(
                initState,
                if (mayBeTarget) 1 else 0,
                if (mustBeTarget) 1 else 0,
                targetExpr,
                null,
                mustBeTarget
            ),
            initialNodePrec
        )
    }

    val nodes = hashMapOf(_initialNode.wrappedNode to _initialNode)
    val waitlist: Waitlist<BLASTMenuGameNode> = FifoWaitlist.create(listOf(_initialNode))


    fun getOrCreateNode(wrappedNode: MenuGameNode<S, A>, supportPrecision: P): BLASTMenuGameNode =
        nodes.getOrPut(wrappedNode) { BLASTMenuGameNode(wrappedNode, supportPrecision) }

    override val initialNode: MenuGameNode<S, A>
        get() = _initialNode.wrappedNode

    val trapNode = MenuGameNode.TrapNode<S, A>()
    val trapDecision = MenuGameAction.EnterTrap<S, A>()
    val trapDirac = dirac(trapNode as MenuGameNode<S, A>)

    override fun getPlayer(node: MenuGameNode<S, A>): Int = node.player

    inner class ExpansionResult(
        val newEdge: BLASTMenuGameEdge,
        val newlyCreated: List<BLASTMenuGameNode>,
        val revisited: List<BLASTMenuGameNode>
    )

    fun expand(
        node: BLASTMenuGameNode,
        action: MenuGameAction<S, A>
    ): ExpansionResult {
        if (action in node.outgoingEdges) throw IllegalStateException("Node-action pair already explored!")
        val result = when (node.wrappedNode) {
            is MenuGameNode.StateNode -> when (action) {
                is MenuGameAction.AbstractionDecision -> throw IllegalArgumentException("Result called for unavailable action $action on node $node")
                is MenuGameAction.ChosenCommand -> dirac(
                    MenuGameNode.ResultNode(node.wrappedNode.s, action.command)
                )

                is MenuGameAction.EnterTrap -> throw IllegalArgumentException("Result called for unavailable action $action on node $node")
            }

            is MenuGameNode.ResultNode -> when (action) {
                is MenuGameAction.AbstractionDecision ->
                    action.result.transform {
                        val mayBeTarget = maySatisfy(it.second, targetExpr)
                        val mustBeTarget = mustSatisfy(it.second, targetExpr)
                        require(mustBeTarget == mayBeTarget) {
                            "The abstraction must be exact with respect to the target labels/rewards for now"
                        }
                        MenuGameNode.StateNode(
                            it.second,
                            if (mayBeTarget) 1 else 0,
                            if (mustBeTarget) 1 else 0,
                            targetExpr,
                            null,
                            mustBeTarget
                        )
                    }

                is MenuGameAction.ChosenCommand -> throw IllegalArgumentException("Result called for unavailable action $action on node $node")
                is MenuGameAction.EnterTrap -> trapDirac
            }

            is MenuGameNode.TrapNode -> throw IllegalArgumentException("Result called for unavailable action $action on node $node")
        }
        val newlyCreated = arrayListOf<BLASTMenuGameNode>()
        val revisited = arrayListOf<BLASTMenuGameNode>()
        for (menuGameNode in result.support) {
            if (menuGameNode in nodes) {
                TODO("how to handle covering here?")
                revisited.add(nodes[menuGameNode]!!.getLastCoverer())
            } else {
                val newNode = getOrCreateNode(menuGameNode, node.supportPrecision)
                newlyCreated.add(newNode)
                TODO("check covering here, or when removed from the waitlist?")
            }
        }
        val newEdge = createEdge(action, node, result.transform { nodes[it]!!.getLastCoverer() })
        return ExpansionResult(newEdge, newlyCreated, revisited)
    }

    fun getAvailableActions(node: BLASTMenuGameNode): Collection<MenuGameAction<S, A>> {
        return when (node.wrappedNode) {
            is MenuGameNode.StateNode -> transFuncCache.getOrPut(node.wrappedNode to null) {
                if (node.wrappedNode.absorbing || node.wrappedNode.s.isBottom) listOf()
                else lts.getAvailableCommands(node.wrappedNode.s).map { MenuGameAction.ChosenCommand(it) }
            }

            is MenuGameNode.ResultNode -> {
                val prec: P = node.supportPrecision
                transFuncCache.getOrPut(node.wrappedNode to prec) {
                    val transFuncResult = transFunc.getNextStates(node.wrappedNode.s, node.wrappedNode.a, prec)
                    val res = transFuncResult.succStates.map {
                        MenuGameAction.AbstractionDecision<S, A>(it)
                    }
                    if (transFuncResult.canBeDisabled) res + trapDecision
                    else res
                }
            }

            is MenuGameNode.TrapNode -> listOf()
        }

    }

    override fun getResult(
        node: MenuGameNode<S, A>,
        action: MenuGameAction<S, A>
    ): FiniteDistribution<MenuGameNode<S, A>> {
        return (nodes[node]?.outgoingEdges?.get(action)?.end?.transform { it.wrappedNode })
            ?: throw IllegalStateException("node-action pair not available")
    }

    val transFuncCache = hashMapOf<Pair<MenuGameNode<S, A>, P?>, Collection<MenuGameAction<S, A>>>()
    override fun getAvailableActions(node: MenuGameNode<S, A>): Collection<MenuGameAction<S, A>> {
        require(nodes[node]!!.isExpanded()) { "getAvailableActions can only be called on expanded nodes" }
        return nodes[node]!!.outgoingEdges.keys
    }


    fun fullyExplore() {
        while (!waitlist.isEmpty) {
            val currNode = waitlist.remove()
            close(currNode)
            if (currNode.coveringNode == null) {
                val expansionResult = currNode.expand()
                waitlist.addAll(expansionResult.first)
            }
        }
    }

    fun close(node: BLASTMenuGameNode) {
        if (node.wrappedNode is MenuGameNode.StateNode) {
            for ((_, bNode) in nodes) {
                val coverer = nodes.values.find { bNode canBeCoveredBy it }
                if (coverer != null) {
                    bNode.coverWith(coverer)
                    break
                }
            }
        }
    }

    infix fun BLASTMenuGameNode.canBeCoveredBy(potentialCoverer: BLASTMenuGameNode): Boolean {
        if (this.wrappedNode is MenuGameNode.StateNode && potentialCoverer.wrappedNode is MenuGameNode.StateNode)
            return this != potentialCoverer
                    && potentialCoverer.coveringNode == null
                    && ord.isLeq(this.wrappedNode.s, potentialCoverer.wrappedNode.s)
        return false
    }

    // ****************
    // Refinement stuff
    // ****************

    private fun removeEdge(edge: BLASTMenuGameEdge) {
        edge.start.outgoingEdges.remove(edge.wrappedAction)
        waitlist.add(edge.start)
        edge.start.makeUnexpanded()
        edge.end.support.forEach {
            it.incomingEdges.remove(edge)
            if (it.incomingEdges.isEmpty() && it != _initialNode) {
                removeNode(it)
            }
        }
    }

    private fun removeNode(node: BLASTMenuGameNode) {
        if (nodes.containsKey(node.wrappedNode)) {
            nodes.remove(node.wrappedNode)
            for (outgoingEdge in node.outgoingEdges.values) {
                removeEdge(outgoingEdge)
            }
            for (incomingEdge in node.incomingEdges) {
                removeEdge(incomingEdge)
            }
            for (coveredNode in node.coveredNodes) {
                coveredNode.removeCover()
            }
        }
    }

    private fun prune(node: MenuGameNode<S, A>) {
        if (node == initialNode) throw IllegalArgumentException("Should have called reinitialize instead")
        removeNode(nodes[node] ?: throw IllegalStateException("Node to prune does not exist"))
    }

    private fun reinitialize(newInitPrec: P) {
        nodes.clear()
        initialize(newInitPrec)
    }

    fun refine(refinementResult: MenuGameRefiner.RefinementResult<S, A, P>) {
        if (refinementResult.pivotNode == initialNode) {
            reinitialize(refinementResult.newPrec)
        } else {
            val parents = nodes[refinementResult.pivotNode]!!.incomingEdges.map { it.start }
            parents.forEach { it.extendSupportPrecision(refinementResult.newPrec) }
            prune(refinementResult.pivotNode)
        }
    }

}