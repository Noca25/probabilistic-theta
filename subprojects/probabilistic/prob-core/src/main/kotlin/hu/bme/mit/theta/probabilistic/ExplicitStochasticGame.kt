package hu.bme.mit.theta.probabilistic

import hu.bme.mit.theta.common.visualization.EdgeAttributes
import hu.bme.mit.theta.common.visualization.Graph
import hu.bme.mit.theta.common.visualization.NodeAttributes
import hu.bme.mit.theta.common.visualization.Shape
import java.awt.Color

class ExplicitStochasticGame private constructor(
    preNodes: List<Builder.Node>,
    preEdges: List<Builder.Edge>,
    initialPreNode: Builder.Node,
    // Used as an "out" parameter, the mapping created during construction can be used in subsequent operations
    //  for creating related objects, like target sets and reward functions
    nodeMap: MutableMap<Builder.Node, Node>
): StochasticGame<ExplicitStochasticGame.Node, ExplicitStochasticGame.Edge> {
    inner class Edge(
        val start: Node,
        val end: FiniteDistribution<Node>,
        val label: String = ""
    )
    inner class Node(
        val player: Int, val name: String
    ) {
        val outgoingEdges get() = this@ExplicitStochasticGame.outgoingEdges[this] ?: listOf()
        val predecessors get() = this@ExplicitStochasticGame.predecessors[this] ?: listOf()
    }

    private val nodes: Collection<Node>
    private val edges: Collection<Edge>
    private val _initialNode: Node

    init {
        val _nodes = arrayListOf<Node>()
        for (preNode in preNodes) {
            val node = Node(preNode.player, preNode.name)
            nodeMap[preNode] = node
            _nodes.add(node)
        }
        nodes = _nodes.toList()
        _initialNode = nodeMap[initialPreNode]!!

        edges = preEdges.map { preEdge ->
            Edge(
                nodeMap[preEdge.start]!!,
                preEdge.end.transform { nodeMap[it]!! },
                preEdge.label
            )
        }
    }

    override val initialNode get() = _initialNode

    private val outgoingEdges: Map<Node, Collection<Edge>>
    init {
        outgoingEdges = edges.groupBy {
            it.start
        }
    }
    val predecessors: Map<Node, Set<Node>>
    init {
        val preds = hashMapOf<Node, HashSet<Node>>()
        for ((start, edges) in outgoingEdges) {
            for (end in edges) {
                for (node in end.end.support) {
                    preds.computeIfAbsent(node) { hashSetOf() }.add(start)
                }
            }
        }
        predecessors = preds
    }

    override fun getPlayer(node: Node): Int = node.player

    override fun getResult(
        node: Node,
        action: Edge
    ): FiniteDistribution<Node> = action.end

    override fun getAvailableActions(node: Node): Collection<Edge> =
        outgoingEdges[node] ?: listOf()

    override fun materialize() = this to this.nodes.associateWith { it } //TODO identity function instead of a map?

    override fun getAllNodes() = nodes

    fun visualize() = visualize(emptyList())

    fun visualize(targets: Collection<Node>): Graph {
        val g = Graph("Game", "Game")
        var nodeId = 0
        val idMap = nodes.associateWith { nodeId++ }
        for (node in nodes) {
            val attrBuilder = NodeAttributes.builder()
                .label("[${node.player}] ${node.name}")
            if(node in targets) attrBuilder.fillColor(Color.RED)
            g.addNode("n${idMap[node]}", attrBuilder.build())
        }

        var nextAuxId = 0
        for (edge in edges) {
            val attrBuilder = EdgeAttributes.builder()
                .label(edge.label)
            if (edge.end.support.size == 1) {
                g.addEdge(
                    "n${idMap[edge.start]}",
                    "n${idMap[edge.end.support.first()]}",
                    attrBuilder.build()
                )
            } else {
                val auxId = "_AUX${nextAuxId++}"
                g.addNode(
                    auxId,
                    NodeAttributes.builder()
                        .shape(Shape.RECTANGLE)
                        .fillColor(Color.GRAY)
                        .build()
                )
                g.addEdge("n${idMap[edge.start]}", auxId, attrBuilder.build())
                for (n in edge.end.support) {
                    val prob = edge.end[n]
                    g.addEdge(auxId, "n${idMap[n]}", EdgeAttributes.builder().label(prob.toString()).build())
                }
            }
        }
        return g
    }

    fun generatePRISM() {
        val nodes = getAllNodes()
        val idx = nodes.withIndex().associate { (idx, n) -> n to idx }
        val numPlayers = nodes.maxOf { it.player }

        fun distrString(distr: FiniteDistribution<Node>): String {
            return distr.support.map { "${distr[it]} : s'=${idx[it]}" }.joinToString { " + " }
        }

        fun nodeString(node: Node): String {
            return node.outgoingEdges.map {
                TODO("this won't work as all of the actions are labeled with the same action")
                "[a${node.player}] s = ${idx[node]} -> ${distrString(it.end)}"
            }.joinToString { "\n" }
        }

        val res = """
            smg
            ${(0..numPlayers).map { "player p$it \n [a$it] \n endplayer" }.joinToString(separator = "\n")}
            
            module game
                s : [0 .. ${nodes.size-1}] init 0
                ${nodes.map {
                    nodeString(it)
                }.joinToString("\n")}
            endmodule
        """.trimIndent()


    }

    companion object {
        fun builder() = Builder()
    }

    class Builder {
        class Node(val name: String="", val player: Int)
        class Edge(val start: Node, val end: FiniteDistribution<Node>, val label: String = "")

        private val preNodes = arrayListOf<Node>()
        private val preEdges = arrayListOf<Edge>()
        private var initialNode: Node? = null

        /**
         * Adds a new node to the game. The name must be unique among all nodes in the builder.
         */
        fun addNode(name: String, player: Int): Node {
            //TODO: use a more efficient version of checking this precondition (e.g. maintain a *set* of names)
            /*require(!preNodes.any { it.name == name }) {
                "Node name must be unique - another node with name $name already exists!"
            }*/
            return Node(name, player).apply(preNodes::add)
        }

        fun addEdge(start: Node, end: FiniteDistribution<Node>, label: String = "") =
            Edge(start, end, label).apply(preEdges::add)

        fun setInitNode(node: Node) {initialNode = node}

        /**
         * Adds a self-loop to all nodes that currently have no outgoing edges.
         * Useful if the created game serves as an input to an analysis algorithm that assumes all nodes to have
         * at least one outgoing edge, and thus needs absorbing nodes to have a self-loop.
         */
        fun addSelfLoops() {
            for (node in preNodes) {
                if(preEdges.none { it.start == node })
                    addEdge(node, FiniteDistribution(node to 1.0))
            }
        }

        /**
         * Creates an ExplicitStochaticGame based on the nodes and edges set in the builder,
         * and returns it along with the mapping from builder nodes to the ones in the created game.
         */
        fun build(): BuildResult {
            require(initialNode != null) {"Error: Initial node not set"}
            val mapping = hashMapOf<Node, ExplicitStochasticGame.Node>()
            val game = ExplicitStochasticGame(preNodes, preEdges, initialNode!!, mapping)
            return BuildResult(game, mapping)
        }

        data class BuildResult(
            val game: ExplicitStochasticGame,
            val mapping: Map<Node, ExplicitStochasticGame.Node>
        )
    }
}