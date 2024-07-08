package hu.bme.mit.theta.prob.analysis.direct

import com.google.common.base.Stopwatch
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.analysis.TransFunc
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.jani.*
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.Algorithm.*
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.BRTDPStrategy.*
import hu.bme.mit.theta.probabilistic.*
import hu.bme.mit.theta.probabilistic.gamesolvers.ExpandableNode
import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.MDPAlmostSureTargetInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.initializers.TargetSetLowerInitializer
import java.util.*
import java.util.concurrent.TimeUnit

typealias DirectCheckerNode<S, A> = DirectChecker<S, A>.DirectCheckerMDP.NodeClass
class DirectChecker<S: State, A: StmtAction>(
    val getStdCommands: (S) -> Collection<ProbabilisticCommand<A>>,
    val isEnabled: (S, ProbabilisticCommand<A>) -> Boolean,
    val isTarget: (S) -> Boolean,
    val initState: S,

    val transFunc: TransFunc<S, in A, ExplPrec>,
    val fullPrec: ExplPrec,
    val mdpSolverSupplier:
        (rewardFunction: GameRewardFunction<DirectCheckerNode<S, A>, FiniteDistribution<DirectCheckerNode<S, A>>>,
         initializer: SGSolutionInitializer<DirectCheckerNode<S, A>, FiniteDistribution<DirectCheckerNode<S, A>>>)
    -> StochasticGameSolver<DirectCheckerNode<S, A>, FiniteDistribution<DirectCheckerNode<S, A>>>,
    val useQualitativePreprocessing: Boolean = false,
    val verboseLogging: Boolean = false
) {

    fun check(
        goal: Goal,
        measureExplorationTime: Boolean = false
    ): Double {

        val game = DirectCheckerMDP()

        val rewardFunction =
            TargetRewardFunction<DirectCheckerNode<S, A>, FiniteDistribution<DirectCheckerNode<S, A>>> {
                it.isTargetNode
            }

        val timer = Stopwatch.createStarted()
        if(measureExplorationTime) {
            game.getAllNodes() // this forces full exploration of the game
            timer.stop()
            val explorationTime = timer.elapsed(TimeUnit.MILLISECONDS)
            println("Exploration time (ms): $explorationTime")
            timer.reset()
            timer.start()
        }

        val initializer =
            if(useQualitativePreprocessing)
                MDPAlmostSureTargetInitializer(game, goal, DirectCheckerNode<S, A>::isTargetNode)
            else TargetSetLowerInitializer {
                it.isTargetNode
            }

        if(initializer.isKnown(game.initNode)) {
            timer.stop()
            val probTime = timer.elapsed(TimeUnit.MILLISECONDS)
            println("Precomputation sufficient, result: ${initializer.initialLowerBound(game.initNode)}")
            println("Probability computation time (ms): $probTime")
            println("All nodes: ${game.reachedSet.size}")
            return initializer.initialLowerBound(game.initNode)
        }
        val quantSolver = mdpSolverSupplier(rewardFunction, initializer)

        val analysisTask = AnalysisTask(game, {goal})
        val values = quantSolver.solve(analysisTask)

        timer.stop()
        val probTime = timer.elapsed(TimeUnit.MILLISECONDS)
        println("Probability computation time (ms): $probTime")
        println("All nodes: ${game.reachedSet.size}")

        return values[game.initNode]!!
    }

    companion object {
        private var nextId = 0
    }
    
    inner class DirectCheckerMDP : StochasticGame<DirectCheckerMDP.NodeClass, FiniteDistribution<DirectCheckerNode<S, A>>> {
        val initNode = NodeClass(initState)
        val waitlist: Queue<NodeClass> = ArrayDeque<NodeClass>().apply { add(initNode) }
        val reachedSet = hashMapOf(initState to initNode)

        override fun materialize(): Pair<ExplicitStochasticGame, Map<NodeClass, ExplicitStochasticGame.Node>> {
            getAllNodes() //forces full exploration to make sure all nodes have been expanded
            return super.materialize()
        }

        override val initialNode: NodeClass
            get() = initNode

        override fun getAllNodes(): Collection<NodeClass> {

            while (!waitlist.isEmpty()) {
                val node = waitlist.remove()
                if(!node.isTargetNode) {
                    node.expand()
                }
            }
            return reachedSet.values
        }

        override fun getPlayer(node: NodeClass): Int = 0

        override fun getResult(node: NodeClass, action: FiniteDistribution<NodeClass>): FiniteDistribution<NodeClass> {
            require(node.expanded)
            return action
        }

        override fun getAvailableActions(node: NodeClass): List<FiniteDistribution<NodeClass>> {
            require(node.expanded)
            return node.getOutgoingEdges()
        }

        inner class NodeClass(val state: S): ExpandableNode<NodeClass> {

            private val id = nextId++
            private val outEdges = arrayListOf<FiniteDistribution<NodeClass>>()
            var expanded: Boolean = false
            var isTargetNode: Boolean = false
            fun getOutgoingEdges(): List<FiniteDistribution<NodeClass>> = outEdges
            fun createEdge(target: FiniteDistribution<NodeClass>) {
                outEdges.add(target)
            }

            override fun hashCode(): Int {
                return Objects.hashCode(this.id)
            }

            override fun isExpanded(): Boolean {
                return expanded
            }

            override fun expand(
            ): Pair<List<DirectCheckerNode<S, A>>, List<DirectCheckerNode<S, A>>> {
                val stdCommands = getStdCommands(this.state)
                this.expanded = true
                if (this.isTargetNode) return Pair(listOf(), listOf())

                val currState = this.state
                val newChildren = arrayListOf<DirectCheckerNode<S, A>>()
                val revisited = arrayListOf<DirectCheckerNode<S, A>>()
                for (cmd in stdCommands) {
                    if (isEnabled(currState, cmd)) {
                        val target = cmd.result.transform { a ->
                            val nextState =
                                transFunc
                                    .getSuccStates(currState, a, fullPrec)
                                    // TODO: this works only with deterministic actions now
                                    .first()
                            val newNode = if (reachedSet.containsKey(nextState)) {
                                val n = reachedSet.getValue(nextState)
                                revisited.add(n)
                                n
                            } else {
                                val n = NodeClass(nextState)
                                newChildren.add(n)
                                reachedSet[nextState] = n
                                if (isTarget(n.state)) {
                                    n.isTargetNode = true
                                    n.expanded = true
                                }
                                n
                            }
                            newNode
                        }
                        this.createEdge(target)
                    }
                }
                waitlist?.addAll(newChildren)
                return Pair(newChildren, revisited)
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as DirectCheckerNode<S, A>

                return id == other.id
            }

        }

    }
}