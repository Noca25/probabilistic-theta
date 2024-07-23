package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.InitFunc
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommandLTS
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.FiniteDistribution.Companion.dirac
import hu.bme.mit.theta.probabilistic.GameRewardFunction
import hu.bme.mit.theta.probabilistic.ImplicitStochasticGame
import hu.bme.mit.theta.probabilistic.StochasticGame

class MenuGameAbstractor<S : State, A : Action, P : Prec>(
    val lts: ProbabilisticCommandLTS<S, A>,
    val init: InitFunc<S, P>,
    val transFunc: MenuGameTransFunc<S, A, P>,
    val targetExpr: Expr<BoolType>,
    val maySatisfy: (S, Expr<BoolType>) -> Boolean,
    val mustSatisfy: (S, Expr<BoolType>) -> Boolean,
) {

    data class AbstractionResult<S : State, A : Action>(
        val game: StochasticGame<MenuGameNode<S, A>, MenuGameAction<S, A>>,
        val rewardMin: GameRewardFunction<MenuGameNode<S, A>, MenuGameAction<S, A>>,
        val rewardMax: GameRewardFunction<MenuGameNode<S, A>, MenuGameAction<S, A>>
    )

    fun computeAbstraction(prec: P): AbstractionResult<S, A> {

        val rewardFunMax = MenuGameUpperRewardFunc<S, A>()

        val rewardFunMin = MenuGameLowerRewardFunc<S, A>()

        val game = object : ImplicitStochasticGame<MenuGameNode<S, A>, MenuGameAction<S, A>>() {

            private val _initialNode: MenuGameNode<S, A>

            init {
                val initState = init.getInitStates(prec).first() // TODO: cannot handle multiple abstract inits yet
                val mayBeTarget = maySatisfy(initState, targetExpr)
                val mustBeTarget = mustSatisfy(initState, targetExpr)
                _initialNode = MenuGameNode.StateNode(
                    initState,
                    if (mayBeTarget) 1 else 0,
                    if (mustBeTarget) 1 else 0,
                    targetExpr,
                    null,
                    mustBeTarget
                )
            }

            override val initialNode: MenuGameNode<S, A>
                get() = _initialNode

            val trapNode = MenuGameNode.TrapNode<S, A>()
            val trapDecision = MenuGameAction.EnterTrap<S, A>()
            val trapDirac = dirac(trapNode as MenuGameNode<S, A>)

            override fun getPlayer(node: MenuGameNode<S, A>): Int = node.player

            override fun getResult(
                node: MenuGameNode<S, A>,
                action: MenuGameAction<S, A>
            ): FiniteDistribution<MenuGameNode<S, A>> {
                return when (node) {
                    is MenuGameNode.StateNode -> when (action) {
                        is MenuGameAction.AbstractionDecision -> throw IllegalArgumentException("Result called for unavailable action $action on node $node")
                        is MenuGameAction.ChosenCommand -> dirac(
                            MenuGameNode.ResultNode(node.s, action.command)
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
            }

            val transFuncCache = hashMapOf<MenuGameNode<S, A>, Collection<MenuGameAction<S, A>>>()
            override fun getAvailableActions(node: MenuGameNode<S, A>): Collection<MenuGameAction<S, A>> {
                return when (node) {
                    is MenuGameNode.StateNode -> transFuncCache.getOrPut(node) {
                        if (node.absorbing || node.s.isBottom) listOf()
                        else lts.getAvailableCommands(node.s).map { MenuGameAction.ChosenCommand(it) }
                    }
                    is MenuGameNode.ResultNode -> transFuncCache.getOrPut(node) {
                        val transFuncResult = transFunc.getNextStates(node.s, node.a, prec)
                        val res = transFuncResult.succStates.map {
                            MenuGameAction.AbstractionDecision<S, A>(it)
                        }
                        if (transFuncResult.canBeDisabled) res + trapDecision
                        else res
                    }

                    is MenuGameNode.TrapNode -> listOf()
                }
            }

        }

        return AbstractionResult(game, rewardFunMin, rewardFunMax)
    }

}