package hu.bme.mit.theta.probabilistic

import java.util.*

interface StochasticGame<N, A> {
    val initialNode: N

    /**
     * Returns a collection of actions that can be taken in the given node.
     */
    fun getAvailableActions(node: N): Collection<A>

    /**
     * Returns the distribution of nodes resulting from taking the given
     * action in the given node.
     */
    fun getResult(node: N, action: A): FiniteDistribution<N>

    /**
     * Identifier of the player controlling the given node.
     */
    fun getPlayer(node: N): Int

    /**
     * Get a collection of all nodes in the game.
     * Should be called only on games which are known to be finite.
     */
    fun getAllNodes(): Collection<N>

    data class MaterializationResult<N>(
        val materializedGame: ExplicitStochasticGame,
        val originalToMaterializedNodeMapping: Map<N, ExplicitStochasticGame.Node>
    )

    /**
     * "Materializes" the game as an ExplicitStochasticGame, an explicit graph-based
     * representation of the game where graph "edges" and nodes can be directly accessed,
     * including querying precomputed predecessors of a node.
     * Should be called only on games which are known to be finite.
     */
    fun materialize(): MaterializationResult<N> {
        val nodeMapping = hashMapOf<N, ExplicitStochasticGame.Builder.Node>()

        val (resultGame, builderMapping) = ExplicitStochasticGame.Builder().apply {
            val q = ArrayDeque<N>()
            q.add(initialNode)

            while (!q.isEmpty()) {
                val curr = q.pop()
                val materNode = nodeMapping.getOrElse(curr) {
                    val newNode = addNode(curr.toString(), getPlayer(curr))
                    nodeMapping[curr] = newNode
                    newNode
                }
                val acts = getAvailableActions(curr)
                for (act in acts) {
                    val result = getResult(curr, act)
                    for (n in result.support) {
                        if (!nodeMapping.containsKey(n)) {
                            nodeMapping[n] = addNode(n.toString(), getPlayer(n))
                            q.add(n)
                        }
                    }
                    val materResult = result.transform { nodeMapping[it]!! }
                    addEdge(materNode, materResult, act.toString())
                }
            }
            setInitNode(nodeMapping[this@StochasticGame.initialNode]!!)
        }.build()
        return MaterializationResult(
            resultGame,
            nodeMapping.mapValues { builderMapping[it.value]!! }
        )
    }
}

fun <N, A> StochasticGame<N, A>.changePlayers(map: (Int) -> Int) = object: StochasticGame<N, A> {
    override val initialNode get() = this@changePlayers.initialNode
    override fun getPlayer(node: N): Int = map(this@changePlayers.getPlayer(node))
    override fun getResult(node: N, action: A) = this@changePlayers.getResult(node, action)
    override fun getAvailableActions(node: N) = this@changePlayers.getAvailableActions(node)
    override fun getAllNodes() = this@changePlayers.getAllNodes()
}

fun <N, A> StochasticGame<N, A>.changeInitNode(newInitNode: N) = object: StochasticGame<N, A> {
    override val initialNode get() = newInitNode
    override fun getPlayer(node: N): Int = this@changeInitNode.getPlayer(node)
    override fun getResult(node: N, action: A) = this@changeInitNode.getResult(node, action)
    override fun getAvailableActions(node: N) = this@changeInitNode.getAvailableActions(node)
    override fun getAllNodes() = this@changeInitNode.getAllNodes()
}

fun <N, A> StochasticGame<N, A>.makeAbsorbing(filter: (N) -> Boolean) = object : StochasticGame<N, A> {
    override val initialNode get() = this@makeAbsorbing.initialNode
    override fun getPlayer(node: N): Int = this@makeAbsorbing.getPlayer(node)
    override fun getResult(node: N, action: A) = this@makeAbsorbing.getResult(node, action)
    override fun getAvailableActions(node: N) =
        if(filter(node)) listOf()
        else this@makeAbsorbing.getAvailableActions(node)
    override fun getAllNodes() = this@makeAbsorbing.getAllNodes()
}