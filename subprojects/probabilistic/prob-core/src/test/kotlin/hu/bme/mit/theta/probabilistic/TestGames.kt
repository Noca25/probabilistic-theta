package hu.bme.mit.theta.probabilistic

data class TestInput(
    val game: ExplicitStochasticGame,
    val targets: List<ExplicitStochasticGame.Node>,
    val expectedReachability: List<Pair<(Int)->Goal, Double>>
)

/**
 * A simple tree-like stochastic game with strictly alternating players (except for absorbing states).
 */
fun treeGame(addSelfLoops: Boolean = false): TestInput {
    val builder = ExplicitStochasticGame.Builder()
    val a = builder.addNode("a", 0)
    builder.setInitNode(a)

    val b = builder.addNode("b", 1)
    builder.addEdge(a, FiniteDistribution(b to 1.0))

    val c1 = builder.addNode("c1", 0)
    val c2 = builder.addNode("c2", 0)
    builder.addEdge(b, FiniteDistribution(c1 to 1.0))
    builder.addEdge(b, FiniteDistribution(c2 to 1.0))

    val d11 = builder.addNode("d11", 1)
    val d12 = builder.addNode("d12", 1)
    val d21 = builder.addNode("d21", 1)
    val d22 = builder.addNode("d22", 1)
    builder.addEdge(c1, FiniteDistribution(d11 to 1.0))
    builder.addEdge(c1, FiniteDistribution(d12 to 1.0))
    builder.addEdge(c2, FiniteDistribution(d21 to 1.0))
    builder.addEdge(c2, FiniteDistribution(d22 to 1.0))

    val e111 = builder.addNode("e111", 0)
    val e112 = builder.addNode("e112", 0)
    val e121 = builder.addNode("e121", 0)
    val e122 = builder.addNode("e122", 0)
    val e211 = builder.addNode("e211", 0)
    val e212 = builder.addNode("e212", 0)
    val e221 = builder.addNode("e221", 0)
    val e222 = builder.addNode("e222", 0)
    builder.addEdge(d11, FiniteDistribution(e111 to 0.1, e112 to 0.9))
    builder.addEdge(d12, FiniteDistribution(e121 to 0.2, e122 to 0.8))
    builder.addEdge(d21, FiniteDistribution(e211 to 0.3, e212 to 0.7))
    builder.addEdge(d22, FiniteDistribution(e221 to 0.4, e222 to 0.6))

    if(addSelfLoops) builder.addSelfLoops()

    val (game, mapping) = builder.build()
    val targets = listOf(e112, e122, e212, e222).mapNotNull(mapping::get)
    val expectedReachability = listOf(
        setGoal(0 to Goal.MIN, 1 to Goal.MIN) to 0.6,
        setGoal(0 to Goal.MIN, 1 to Goal.MAX) to 0.8,
        setGoal(0 to Goal.MAX, 1 to Goal.MIN) to 0.7,
        setGoal(0 to Goal.MAX, 1 to Goal.MAX) to 0.9,
    )
    return TestInput(game, targets, expectedReachability)
}


/**
 * A simple ring-like stochastic game with strictly alternating players (except for absorbing states).
 * In each node of the ring, the owner can decide to go on in the ring surely, or exit to an absorbing state
 * with 0.9 probability. Each "outer" node is either a target, or a non-target absorbing state.
 */
fun ringGame(addSelfLoops: Boolean = false): TestInput {
    val builder = ExplicitStochasticGame.Builder()
    val ringNodes = arrayListOf<ExplicitStochasticGame.Builder.Node>()
    val outerNodes = arrayListOf<ExplicitStochasticGame.Builder.Node>()

    val size = 8

    for (i in 0 until size) {
        val newRingNode = builder.addNode("r$i", i % 2)
        ringNodes.add(newRingNode)

        val newOuter = builder.addNode("o$i", (i+1)%2)
        outerNodes.add(newOuter)
    }
    for (i in 0 until size) {
        val nextRingNode = ringNodes[(i + 1) % size]
        builder.addEdge(ringNodes[i], FiniteDistribution(nextRingNode to 0.1, outerNodes[i] to 0.9))
        builder.addEdge(ringNodes[i], FiniteDistribution.dirac(nextRingNode))
    }
    builder.setInitNode(ringNodes.first())
    if(addSelfLoops) builder.addSelfLoops()
    val targets = listOf(0, 3, 4, 5).map(outerNodes::get)

    val (game, mapping) = builder.build()
    val expectedReachability = listOf(
        setGoal(0 to Goal.MIN, 1 to Goal.MIN) to 0.0,
        setGoal(0 to Goal.MIN, 1 to Goal.MAX) to 0.0, // TODO: calc
        setGoal(0 to Goal.MAX, 1 to Goal.MIN) to 0.0, // TODO: calc
        setGoal(0 to Goal.MAX, 1 to Goal.MAX) to 1.0
    )
    return TestInput(game, targets.mapNotNull(mapping::get), expectedReachability)
}

/**
 * This test game aims to contain as many edge cases in it as possible.
 * This does not include targeting false convergence for standard value iteration though.
 * It has multiple end components, some of them having only a single node (absorbing node).
 * Players are not strictly alternating. Unreachable components are also present.
 */
fun complexGame(): TestInput {
    TODO()
}