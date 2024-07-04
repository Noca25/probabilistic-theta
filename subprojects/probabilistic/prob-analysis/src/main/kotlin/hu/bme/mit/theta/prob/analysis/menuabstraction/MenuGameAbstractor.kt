package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.InitFunc
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.inttype.IntType
import hu.bme.mit.theta.prob.analysis.P_ABSTRACTION
import hu.bme.mit.theta.prob.analysis.P_CONCRETE
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommandLTS
import hu.bme.mit.theta.prob.analysis.linkedtransfuncs.LinkedTransFunc
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.FiniteDistribution.Companion.dirac
import hu.bme.mit.theta.probabilistic.GameRewardFunction
import hu.bme.mit.theta.probabilistic.ImplicitStochasticGame
import hu.bme.mit.theta.probabilistic.StochasticGame

data class MenuGameTransFuncResult<S : State, A : Action>(
    val succStates: List<FiniteDistribution<Pair<A, S>>>,
    val canBeDisabled: Boolean
)

interface MenuGameTransFunc<S : State, A : Action, P : Prec> {
    /**
     * Computes a list of possible next state distributions after executing the given command in the given abstract state.
     * The result is projected to the precision in the argument.
     */
    fun getNextStates(state: S, command: ProbabilisticCommand<A>, prec: P): MenuGameTransFuncResult<S, A>
}

class BasicMenuGameTransFunc<S : State, A : Action, P : Prec>(
    val baseTransFunc: LinkedTransFunc<S, A, P>,
    val canBeDisabled: (S, Expr<BoolType>) -> Boolean
) : MenuGameTransFunc<S, A, P> {
    override fun getNextStates(state: S, command: ProbabilisticCommand<A>, prec: P): MenuGameTransFuncResult<S, A> {

        val effectDistr = command.result.pmf.entries
        val actions = effectDistr.map { it.key }
        val probs = effectDistr.map { it.value }
        val canTrap = canBeDisabled(state, command.guard)
        val succStates =
            baseTransFunc.getSuccStates(state, command.guard, actions, prec).map {
                FiniteDistribution((actions.zip(it)).zip(probs).toMap())
            }


        return MenuGameTransFuncResult(succStates, canTrap)
    }

}

class MenuGameAbstractor<S : State, A : Action, P : Prec>(
    val lts: ProbabilisticCommandLTS<S, A>,
    val init: InitFunc<S, P>,
    val transFunc: MenuGameTransFunc<S, A, P>,
    val targetExpr: Expr<BoolType>,
    val maySatisfy: (S, Expr<BoolType>) -> Boolean,
    val mustSatisfy: (S, Expr<BoolType>) -> Boolean,
) {

    sealed class MenuGameNode<S : State, A : Action>(val player: Int) {
        data class StateNode<S : State, A : Action>(
            val s: S,
            val maxReward: Int = 0,
            val minReward: Int = 0,

            // If the reward split expression evaluates to true in a concrete state,
            // then that provides maxReward; if not, it provides minReward
            val rewardSplitExpr: Expr<BoolType>? = null,

            // Expression specifying the reward given by a concrete state
            val rewardExpr: Expr<IntType>? = null,

            // Whether the node should be made absorbing regardless of whether any action is enabled originally
            // Used for making the target states absorbing for reachability queries
            val absorbing: Boolean = false
        ) : MenuGameNode<S, A>(P_CONCRETE) {
            init {
                require(maxReward == minReward || rewardSplitExpr != null || rewardExpr != null) {
                    "If maximal and minimal rewards are not the same, then either a reward split expression or a reward expression is needed"
                }
            }
        }

        data class ResultNode<S : State, A : Action>(
            val s: S, val a: ProbabilisticCommand<A>
        ) : MenuGameNode<S, A>(P_ABSTRACTION)

        class TrapNode<S : State, A : Action> : MenuGameNode<S, A>(P_CONCRETE) {
            override fun toString(): String {
                return "TRAP"
            }
        }
    }

    sealed class MenuGameAction<S : State, A : Action>() {
        data class ChosenCommand<S : State, A : Action>(val command: ProbabilisticCommand<A>) : MenuGameAction<S, A>()
        data class AbstractionDecision<S : State, A : Action>(
            val result: FiniteDistribution<Pair<A, S>>
        ) : MenuGameAction<S, A>()

        class EnterTrap<S : State, A : Action>() : MenuGameAction<S, A>() {
            override fun toString(): String {
                return "ENTER TRAP"
            }
        }
    }

    data class AbstractionResult<S : State, A : Action>(
        val game: StochasticGame<MenuGameNode<S, A>, MenuGameAction<S, A>>,
        val rewardMin: GameRewardFunction<MenuGameNode<S, A>, MenuGameAction<S, A>>,
        val rewardMax: GameRewardFunction<MenuGameNode<S, A>, MenuGameAction<S, A>>
    )

    fun computeAbstraction(prec: P): AbstractionResult<S, A> {

        val rewardFunMax = object : GameRewardFunction<MenuGameNode<S, A>, MenuGameAction<S, A>> {
            override fun getStateReward(n: MenuGameNode<S, A>): Double {
                return when (n) {
                    is MenuGameNode.StateNode -> n.maxReward.toDouble()
                    is MenuGameNode.ResultNode -> 0.0
                    is MenuGameNode.TrapNode -> 0.0
                }
            }

            override fun getEdgeReward(
                source: MenuGameNode<S, A>,
                action: MenuGameAction<S, A>,
                target: MenuGameNode<S, A>
            ): Double {
                return 0.0 // for now
            }
        }

        val rewardFunMin = object : GameRewardFunction<MenuGameNode<S, A>, MenuGameAction<S, A>> {
            override fun getStateReward(n: MenuGameNode<S, A>): Double {
                return when (n) {
                    is MenuGameNode.StateNode -> n.minReward.toDouble()
                    is MenuGameNode.ResultNode -> 0.0
                    is MenuGameNode.TrapNode -> 0.0
                }
            }

            override fun getEdgeReward(
                source: MenuGameNode<S, A>,
                action: MenuGameAction<S, A>,
                target: MenuGameNode<S, A>
            ): Double {
                return 0.0 // for now
            }
        }

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