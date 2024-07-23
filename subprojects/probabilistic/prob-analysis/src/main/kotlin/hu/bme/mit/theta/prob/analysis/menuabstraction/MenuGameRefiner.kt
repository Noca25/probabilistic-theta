package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.stmt.SequenceStmt
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.utils.WpState
import hu.bme.mit.theta.probabilistic.StochasticGame
import hu.bme.mit.theta.solver.Solver

class MenuGameRefiner<S: ExprState, A: StmtAction, P: Prec>(
    val solver: Solver,
    val extend: P.(basedOn: Expr<BoolType>) -> P
) {

    data class RefinementResult<S: ExprState, A: StmtAction, P: Prec>(
        val newPrec: P,
        val pivotNode: MenuGameNode.ResultNode<S, A>
    )

    fun  refine(
        sg: StochasticGame<
                MenuGameNode<S, A>,
                MenuGameAction<S, A>
                >,
        valueFunctionMax: Map<MenuGameNode<S, A>, Double>,
        valueFunctionMin: Map<MenuGameNode<S, A>, Double>,
        prec: P,
        nodesToConsider: Collection<MenuGameNode.StateNode<S, A>>
        = sg.getAllNodes().filterIsInstance<MenuGameNode.StateNode<S, A>>(),
        tolerance: Double = 0.0
    ): RefinementResult<S, A, P> {
        return refine(sg, valueFunctionMax, valueFunctionMin, {prec}, nodesToConsider, tolerance)
    }

    fun  refine(
        sg: StochasticGame<
                MenuGameNode<S, A>,
                MenuGameAction<S, A>
                >,
        valueFunctionMax: Map<MenuGameNode<S, A>, Double>,
        valueFunctionMin: Map<MenuGameNode<S, A>, Double>,
        prec: (MenuGameNode<S,A>) -> P,
        nodesToConsider: Collection<MenuGameNode.StateNode<S, A>>
            = sg.getAllNodes().filterIsInstance<MenuGameNode.StateNode<S, A>>(),
        tolerance: Double = 0.0
    ): RefinementResult<S, A, P> {
        fun minMaxChoiceDifference(node: MenuGameNode.ResultNode<S, A>): Set<MenuGameAction<S, A>> {
            val abstractionChoices = sg.getAvailableActions(node)
            val max = abstractionChoices.maxOf {
                sg.getResult(node, it).expectedValue { valueFunctionMax[it]!! }
            }
            val min = abstractionChoices.minOf {
                sg.getResult(node, it).expectedValue { valueFunctionMin[it]!! }
            }
            val maxChoices = abstractionChoices.filter {
                sg.getResult(node, it).expectedValue { valueFunctionMax[it]!! } == max
            }
            val minChoices = abstractionChoices.filter {
                sg.getResult(node, it).expectedValue { valueFunctionMin[it]!! } == min
            }
            if(maxChoices != minChoices) return maxChoices.minus(minChoices) union minChoices.minus(maxChoices)
            else return setOf()
        }

        fun refinable(node: MenuGameNode.ResultNode<S, A>): Boolean = minMaxChoiceDifference(node).isNotEmpty()

        fun refinable(node: MenuGameNode.StateNode<S, A>): Boolean =
            node.minReward != node.maxReward || sg.getAvailableActions(node).any { a->
                val resNode = sg.getResult(node, a).support.first() as MenuGameNode.ResultNode // always dirac
                refinable(resNode)
            }

        val nodeToRefine = nodesToConsider.find {
            valueFunctionMax[it]!! - valueFunctionMin[it]!! > tolerance
                    && refinable(it)
        } ?: throw IllegalArgumentException("Unable to refine menu game, no refinable node found")
        val actions = sg.getAvailableActions(nodeToRefine)
        var resnodeToRefine: MenuGameNode.ResultNode<S, A>? = null
        for (a in actions) {
            val resnode = sg.getResult(nodeToRefine, a).support.first() as MenuGameNode.ResultNode<S, A>
            if(refinable(resnode)) {
                resnodeToRefine = resnode
                break
            }
        }
        if (resnodeToRefine == null) throw IllegalArgumentException("Unable to refine menu game, no refinable node found")
        val diff = minMaxChoiceDifference(resnodeToRefine)

        var newPrec = prec(resnodeToRefine)
        for(abstractionDecision in diff) {
            when(abstractionDecision){
                is MenuGameAction.EnterTrap -> {
                    val command = resnodeToRefine.a
                    newPrec = newPrec.extend(command.guard)
                }
                is MenuGameAction.AbstractionDecision -> {
                    newPrec = abstractionDecision.result.support.map {
                        (action, nextState) ->
                        WpState.of(nextState.toExpr()).wp(SequenceStmt.of(action.stmts)).expr
                    }.fold(newPrec) { p, expr -> p.extend(expr) }
                }
                is MenuGameAction.ChosenCommand -> throw RuntimeException("WTF")
            }
        }

        return RefinementResult(newPrec, resnodeToRefine)

    }

}