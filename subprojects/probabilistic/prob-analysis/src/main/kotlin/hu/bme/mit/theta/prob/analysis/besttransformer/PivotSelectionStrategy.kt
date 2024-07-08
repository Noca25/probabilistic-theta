package hu.bme.mit.theta.prob.analysis.besttransformer

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameAction
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameNode
import hu.bme.mit.theta.prob.analysis.besttransformer.BestTransformerAbstractor.BestTransformerGameNode.AbstractionChoiceNode
import hu.bme.mit.theta.probabilistic.StochasticGame

interface PivotSelectionStrategy {
    fun <S: State, A: Action> selectPivot(
        sg: StochasticGame<BestTransformerGameNode<S, A>,
                BestTransformerGameAction<S, A>>,
        refinableNodes: List<AbstractionChoiceNode<S, A>>,
        valueFunctionMax: Map<BestTransformerGameNode<S, A>, Double>,
        valueFunctionMin: Map<BestTransformerGameNode<S, A>, Double>,
        strategyMax: Map<BestTransformerGameNode<S, A>, BestTransformerGameAction<S, A>>,
        strategyMin: Map<BestTransformerGameNode<S, A>, BestTransformerGameAction<S, A>>,
    ): AbstractionChoiceNode<S, A>
}