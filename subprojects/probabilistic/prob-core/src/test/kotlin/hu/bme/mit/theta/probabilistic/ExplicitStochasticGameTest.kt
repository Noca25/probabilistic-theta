package hu.bme.mit.theta.probabilistic

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplicitStochasticGameTest {

    @Test
    fun creationThroughBuilderTest() {
        val gameBuilder = ExplicitStochasticGame.Builder()
        val s1 = gameBuilder.addNode("s1", 0)
        val s2 = gameBuilder.addNode("s2", 1)
        val s3 = gameBuilder.addNode("s3", 0)
        gameBuilder.addEdge(s1, FiniteDistribution(s1 to 0.2, s2 to 0.8))
        gameBuilder.addEdge(s1, FiniteDistribution(s2 to 0.3, s3 to 0.7))
        gameBuilder.addEdge(s2, FiniteDistribution(s3 to 1.0))
        gameBuilder.setInitNode(s1)
        val game = gameBuilder.build().game

        val allNodes = game.getAllNodes()
        assertEquals(3, allNodes.size)
        val s1Node = allNodes.find { it.name == "s1" }!! // assertNotNull by exception
        assertEquals(0, game.getPlayer(s1Node))
        val s2Node = allNodes.find { it.name == "s2" }!! // assertNotNull by exception
        assertEquals(1, game.getPlayer(s2Node))
        val s3Node = allNodes.find { it.name == "s3" }!! // assertNotNull by exception
        val actions = game.getAvailableActions(s1Node)
        val results = actions.map { act -> game.getResult(s1Node, act) }

        results.contains(FiniteDistribution(s1Node to 0.2, s2Node to 0.8))
        results.contains(FiniteDistribution(s2Node to 0.3, s3Node to 0.7))
    }

    @Test
    fun materializeTest() {
        val materGame = object : StochasticGame<Int, Int> {
            override val initialNode: Int
                get() = 0

            override fun getAvailableActions(node: Int): Collection<Int> {
                return if(node >= 10) emptyList()
                else (0..(10-node)).toList()
            }

            override fun getResult(node: Int, action: Int): FiniteDistribution<Int> {
                if(node+action == 0) return FiniteDistribution(0 to 1.0)
                return FiniteDistribution(0 to 0.2, node+action to 0.8)
            }

            override fun getPlayer(node: Int): Int {
                return node % 2
            }

            override fun getAllNodes(): Collection<Int> = (0..10).toList()
        }.materialize().materializedGame
        val allNodes = materGame.getAllNodes()
        assertEquals(11, allNodes.size)
        val init = materGame.initialNode
        val actions = materGame.getAvailableActions(init)
        assertEquals(11, actions.size)
        //TODO: other checks
    }
}