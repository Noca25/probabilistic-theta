package hu.bme.mit.theta.probabilistic

import hu.bme.mit.theta.probabilistic.gamesolvers.SGBVISolver
import hu.bme.mit.theta.probabilistic.gamesolvers.VISolver
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.TargetSetLowerInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.UntargetSetUpperInitializer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(value = Parameterized::class)
class SolverTest(
    val input: TestInput
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun data() = listOf(
            arrayOf(treeGame()),
//            arrayOf(ringGame()),
//            arrayOf(complexGame())
        )
    }

    @Test
    fun viSolverTest() {
        val tolerance = 1e-8
        val solver = VISolver<ExplicitStochasticGame.Node, ExplicitStochasticGame.Edge>(
            tolerance,
            TargetRewardFunction(input.targets::contains),
            false,
            TargetSetLowerInitializer(input.targets::contains)
        )
        for ((goal, expectedResult) in input.expectedReachability) {
            val analysisTask = AnalysisTask(input.game, goal)
            assert(
                // This assertion does not hold for all possible games even if VI is implemented correctly,
                // but as the test games are not constructed to be counterexamples for standard VI, it hopefully
                // does for them
                solver.solve(analysisTask)[input.game.initialNode]!!.equals(expectedResult, tolerance)
            )
        }
    }

    @Test
    fun viSolverGSTest() {
        val tolerance = 1e-8
        val solver = VISolver<ExplicitStochasticGame.Node, ExplicitStochasticGame.Edge>(
            tolerance,
            TargetRewardFunction(input.targets::contains),
            true, TargetSetLowerInitializer(input.targets::contains),
        )
        for ((goal, expectedResult) in input.expectedReachability) {
            val analysisTask = AnalysisTask(input.game, goal)
            assert(
                // This assertion does not hold for all possible games even if VI is implemented correctly,
                // but as the test games are not constructed to be counterexamples for standard VI, it hopefully
                // does for them
                solver.solve(analysisTask)[input.game.initialNode]!!.equals(expectedResult, tolerance)
            )
        }
    }

    @Test
    fun bviSolverTest() {
        val tolerance = 1e-8
        val solver = SGBVISolver<ExplicitStochasticGame.Node, ExplicitStochasticGame.Edge>(
            tolerance,
            lowerInitializer = TargetSetLowerInitializer(input.targets::contains),
            upperInitilizer = UntargetSetUpperInitializer {
                it.outgoingEdges.size == 1 && it.outgoingEdges.first().end.support == setOf(it) && it !in input.targets
            },
            false
        )
        for ((goal, expectedResult) in input.expectedReachability) {
            val analysisTask = AnalysisTask(input.game, goal)
            val solution = solver.solve(analysisTask)
            assert(
                solution[input.game.initialNode]!!.equals(expectedResult, tolerance)
            )
            input.targets.forEach {
                // One check to see that deflation did not break anything
                assert(solution[it] == 1.0)
            }
        }
    }
}