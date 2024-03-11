package hu.bme.mit.theta.probabilistic

import hu.bme.mit.theta.probabilistic.FiniteDistribution.Companion.dirac
import hu.bme.mit.theta.probabilistic.gamesolvers.almostSureMaxForMDP
import hu.bme.mit.theta.probabilistic.gamesolvers.computeMECs
import hu.bme.mit.theta.probabilistic.gamesolvers.computeSCCs
import org.junit.Assert.*
import org.junit.Test

class VIComponentTest {
    @Test
    fun graphSCCComputationTest() {
        val sccs1 = computeSCCs(listOf(listOf()), 1)
        assertEquals(listOf(setOf(0)), sccs1)

        val sccs2 = computeSCCs(listOf(listOf(0)), 1)
        assertEquals(listOf(setOf(0)), sccs2)
    }


    @Test
    fun mecComputationTest() {
        val ringGame = ringGame()
        val ringMecs = computeMECs(ringGame.game)
        assertEquals(9, ringMecs.size) // Each outer node is its own MEC, and the ring itself is another one
        assertEquals(8, ringMecs.count {it.size == 1})
        assertEquals(1, ringMecs.count {it.size == 8})

        val treeGame = treeGame()
        val treeMecs = computeMECs(treeGame.game)
        assertEquals(8, treeMecs.size) // Only the tree nodes are MECs
        assertEquals(8, treeMecs.count {it.size == 1})
    }

    @Test
    fun almostSureComputationTest() {
        val mdpBuilder = ExplicitStochasticGame.builder()

        val A = mdpBuilder.addNode("A", 0)
        val B = mdpBuilder.addNode("B", 0)
        val C = mdpBuilder.addNode("C", 0)
        val D = mdpBuilder.addNode("D", 0)
        val E = mdpBuilder.addNode("E", 0)
        val F = mdpBuilder.addNode("F", 0)
        val G = mdpBuilder.addNode("G", 0)
        val H = mdpBuilder.addNode("H", 0)
        mdpBuilder.setInitNode(A)

        mdpBuilder.addEdge(A, FiniteDistribution(B to 0.2, D to 0.8))
        mdpBuilder.addEdge(B, FiniteDistribution(C to 0.9, A to 0.1))
        mdpBuilder.addEdge(D, dirac(E))
        mdpBuilder.addEdge(D, dirac(B))
        mdpBuilder.addEdge(E, dirac(C))
        mdpBuilder.addEdge(E, FiniteDistribution(F to 0.2, D to 0.8))
        mdpBuilder.addEdge(F, FiniteDistribution(G to 0.2, H to 0.8))


        val (mdp, mapping) = mdpBuilder.build()

        val almostSure = almostSureMaxForMDP(mdp, listOf(mapping.getValue(G), mapping.getValue(H)))

        assertEquals(listOf(D, E, F, G, H).map(mapping::getValue).toSet(), almostSure.toSet())
    }
}