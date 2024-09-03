package hu.bme.mit.theta.prob.analysis.besttransformer

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameAction
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameNode
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameNode.AbstractionChoiceNode
import hu.bme.mit.theta.probabilistic.StochasticGame
import java.util.*

class ReachableMostUncertain(): PivotSelectionStrategy {
    override fun <S : State, A : Action> selectPivot(
        sg: StochasticGame<BestTransformerGameNode<S, A>, BestTransformerGameAction<S, A>>,
        refinableNodes: List<AbstractionChoiceNode<S, A>>,
        valueFunctionMax: Map<BestTransformerGameNode<S, A>, Double>,
        valueFunctionMin: Map<BestTransformerGameNode<S, A>, Double>,
        strategyMax: Map<BestTransformerGameNode<S, A>, BestTransformerGameAction<S, A>>,
        strategyMin: Map<BestTransformerGameNode<S, A>, BestTransformerGameAction<S, A>>
    ): AbstractionChoiceNode<S, A> {
        val reachable = hashSetOf(sg.initialNode)
        val waitList: Queue<BestTransformerGameNode<S, A>> = ArrayDeque()
        for(strategy in listOf(strategyMax, strategyMin)) {
            waitList.add(sg.initialNode)
            while (waitList.isNotEmpty()) {
                val curr = waitList.remove()
                if (curr !in strategy) continue // curr should be absorbing in this case
                for (nextNode in sg.getResult(curr, strategy[curr]!!).support) {
                    if (nextNode !in reachable) {
                        reachable.add(nextNode)
                        waitList.add(nextNode)
                    }
                }
            }
        }
        return reachable
            .intersect(refinableNodes)
            .filterIsInstance<AbstractionChoiceNode<S, A>>()
            .maxByOrNull { valueFunctionMax[it]!! - valueFunctionMin[it]!! }!!
    }
}