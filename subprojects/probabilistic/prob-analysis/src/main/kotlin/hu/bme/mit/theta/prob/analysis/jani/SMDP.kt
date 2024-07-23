package hu.bme.mit.theta.prob.analysis.jani

import hu.bme.mit.theta.analysis.InitFunc
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.decl.Decl
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.core.stmt.AssignStmt
import hu.bme.mit.theta.core.stmt.Stmt
import hu.bme.mit.theta.core.stmt.Stmts.SequenceStmt
import hu.bme.mit.theta.core.stmt.Stmts.SimultaneousStmt
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.Type
import hu.bme.mit.theta.core.type.abstracttype.AbstractExprs
import hu.bme.mit.theta.core.type.anytype.Exprs
import hu.bme.mit.theta.core.type.anytype.RefExpr
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs
import hu.bme.mit.theta.core.type.inttype.IntExprs
import hu.bme.mit.theta.core.type.inttype.IntType
import hu.bme.mit.theta.core.type.rattype.RatExprs
import hu.bme.mit.theta.core.type.rattype.RatLitExpr
import hu.bme.mit.theta.core.type.rattype.RatType
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommandLTS
import hu.bme.mit.theta.prob.analysis.jani.SMDP.ActionLabel.InnerActionLabel
import hu.bme.mit.theta.prob.analysis.jani.SMDP.ActionLabel.StandardActionLabel
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.Goal

/**
 * A symbolic MDP class based on the MDP subset of JANI models.
 */
class SMDP(
    val globalVars: Collection<VarDecl<*>>,
    val automata: List<AutomatonInstance>,
    val syncVecs: List<List<StandardActionLabel?>>,
    val initExprs: Collection<Expr<BoolType>>,
    val properties: List<SMDPProperty>,
    val transientInitialValueMap: Map<VarDecl<*>, Expr<*>>,
    val constantsValuation: Valuation
) {
    class Location(
        val name: String,
        val outEdges: MutableList<Edge>,
        val parent: AutomatonInstance? = null,
        val transientMap: Map<VarDecl<*>, Expr<*>> = mapOf()
    ) {
        override fun toString(): String {
            return name
        }
    }
    sealed class ActionLabel(val name: String) {
        object InnerActionLabel : ActionLabel("<inner>")
        class StandardActionLabel(name: String) : ActionLabel(name)

        override fun toString(): String {
            return name
        }
    }
    class Assignment(val ref: VarDecl<*>, val expr: Expr<*>, val index: Int) {
        fun toStmt() =
            if(ref.type == expr.type)
                AssignStmt.create<Type>(ref, expr)
            // TODO: generalize this using castable
            else if(expr.type == IntType.getInstance() && ref.type == RatType.getInstance())
                AssignStmt.create<Type>(ref, IntExprs.ToRat(expr as Expr<IntType>) )
            else
                throw RuntimeException("$expr of type ${expr.type} cannot be assigned to $ref of type ${ref.type}")

    }
    class Edge(
        val sourceLoc: Location,
        val guard: Expr<BoolType>,
        val action: ActionLabel?,
        val destinations: List<Destination>
    ) {
        init {sourceLoc.outEdges.add(this)}
    }
    class Destination(
        val probability: Expr<RatType>,
        val assignments: List<Assignment>,
        val loc: Location
    )
    class ComposedDestination(
        val probability: Expr<RatType>,
        val assignments: List<Assignment>,
        val locs: List<Location>
    )

    class Automaton(
        val locations: Collection<Location>,
        val initLocs: Collection<Location>,
        val actions: Collection<ActionLabel>,
        val localVars: Collection<VarDecl<*>>,
        val edges: Collection<Edge>,
        val initExprs: Collection<Expr<BoolType>>,
        val transientInitialValueMap: Map<VarDecl<*>, Expr<*>>
    ) { var numInstances = 0 }

    class AutomatonInstance(template: Automaton) {
        val id = template.numInstances++

        private val varLUT = template.localVars.associateWith {
            Decls.Var(it.name+"_$id", it.type)
        }
        val localVars = varLUT.values.toList()

        val initExprs = template.initExprs.map(::replaceVars)

        val resetTransientsStmt: Stmt = SequenceStmt(
            template.transientInitialValueMap.entries.map {
                AssignStmt.create<Type>(varLUT[it.key]!!, replaceVars(it.value))
            }
        )

        private fun <T: Type> replaceVars(e: Expr<T>): Expr<T> =
            if (e is RefExpr) {
                val d = e.decl as Decl<T>
                if (d is VarDecl<T> && varLUT.containsKey(d)) Exprs.Ref(varLUT[d] as VarDecl<T>)
                else e
            } else {
                e.withOps(e.ops.map { replaceVars(it) })
            }

        private val locLUT = template.locations.associateWith {
            val replacedTransientMap = it.transientMap.entries.associate {
                if(varLUT.containsKey(it.key)) {
                    varLUT[it.key]!! to replaceVars(it.value)
                } else {
                    it.key to replaceVars(it.value)
                }
            }
            Location(it.name, arrayListOf(), this, replacedTransientMap)
        }

        val locs = locLUT.values.toList()
        val edges = template.edges.map { edge ->
            Edge(locLUT[edge.sourceLoc]!!, replaceVars(edge.guard), edge.action, edge.destinations.map { dest ->
                Destination(replaceVars(dest.probability), dest.assignments.map {
                    val newExpr = replaceVars(it.expr)
                    val newRef = if(varLUT.containsKey(it.ref)) varLUT[it.ref] else it.ref
                    Assignment(newRef as VarDecl<Type>, newExpr as Expr<Type>, it.index)
                }, locLUT[dest.loc]!!)
            })
        }

        val initLocs = template.initLocs.map { locLUT[it]!! }
    }

    fun resetTransientsStmt(): Stmt = SequenceStmt(transientInitialValueMap.entries.mapNotNull {
        AssignStmt.create<Type>(it.key, it.value)
    } + automata.map { it.resetTransientsStmt })

    fun getFullInitExpr(): Expr<BoolType> = SmartBoolExprs.And(
        (initExprs + automata.flatMap { it.initExprs }).ifEmpty { listOf(BoolExprs.True()) }
    )

    fun getAllVars() =
        globalVars + automata.flatMap(AutomatonInstance::localVars)
}

data class SMDPState<D: ExprState>(
    val domainState: D,
    val locs: List<SMDP.Location>
): ExprState {
    override fun isBottom(): Boolean = domainState.isBottom

    override fun toExpr(): Expr<BoolType> {
        // TODO: maybe add the locs?
        return domainState.toExpr()
    }
}

data class SMDPReachabilityTask(
    val targetExpr: Expr<BoolType>,
    val goal: Goal,
    val negateResult: Boolean,
    val constraint: Expr<BoolType>
)

class SMDPCommandAction(
    val destination: SMDP.ComposedDestination,
    val smdp: SMDP
) : StmtAction() {
    companion object {
        fun skipAt(locs: List<SMDP.Location>, smdp: SMDP) =
            SMDPCommandAction(
                SMDP.ComposedDestination(
                    RatExprs.Rat(1, 1),
                    listOf(),
                    locs
                ), smdp
            )
    }

    override fun getStmts() =
        // Apply transition
        this.destination.assignments.groupBy { it.index }.toSortedMap().map {
            SimultaneousStmt(it.value.map(SMDP.Assignment::toStmt))
        } +
        // then reset all transient variables
        smdp.resetTransientsStmt() +
        // then set all transient variables based on the target locations, if it gives them a value
        this.destination.locs.flatMap {
            it.transientMap.entries.map {
                AssignStmt.create<Type>(it.key, it.value)
            }
        }

    override fun toString(): String {
        return stmts.toString()
    }
}


class SmdpCommandLts<D: ExprState>(val smdp: SMDP): ProbabilisticCommandLTS<SMDPState<D>, SMDPCommandAction> {
    private val cache = hashMapOf<
            List<SMDP.Location>,
            List<ProbabilisticCommand<SMDPCommandAction>>
            >()

    private fun edgesToCommand(es: List<SMDP.Edge>): ProbabilisticCommand<SMDPCommandAction> {
        val fullGuard = BoolExprs.And(es.map { it.guard })
        val resolutions = es.fold(listOf<List<SMDP.Destination>>(listOf())) { acc, curr ->
            acc.flatMap { prefix ->
                curr.destinations.map { new -> prefix + new  }
            }
        }
        val resultDistr = resolutions.associate {
            val probExpr = AbstractExprs.Mul(it.map(SMDP.Destination::probability)) as Expr<RatType>

            // TODO: replacing constant decls with their values should be done in the ModelToSMDP step
            val evaluated = probExpr.eval(smdp.constantsValuation) as RatLitExpr
            val prob = evaluated.num.toDouble() / evaluated.denom.toDouble()
            SMDPCommandAction(SMDP.ComposedDestination(
                probExpr,
                it.flatMap { it.assignments },
                it.map { it.loc }
            ), smdp) to prob
        }

        return ProbabilisticCommand(fullGuard, FiniteDistribution(resultDistr))
    }

    private fun computeCommands(state: SMDPState<D>): List<ProbabilisticCommand<SMDPCommandAction>> {
        val res = arrayListOf<ProbabilisticCommand<SMDPCommandAction>>()
        syncs@ for (syncVec in smdp.syncVecs) {
            var resolutions: List<List<SMDP.Edge>> = arrayListOf(listOf())
            parts@ for ((idx, action) in syncVec.withIndex()) {
                if(action == null) continue@parts

                val available = state.locs[idx].outEdges.filter { it.action == action }
                if(available.isEmpty()) continue@syncs
                resolutions = resolutions.flatMap { prev -> available.map { new -> prev + new } }
            }
            res.addAll(resolutions.map { edgesToCommand(it) })
        }

        val nonSyncEdges = state.locs.flatMapIndexed { idx, loc ->
            loc.outEdges.filter { it.action is InnerActionLabel }.map {
                edgesToCommand(listOf(it))
            }
        }
        res.addAll(nonSyncEdges)

        return res
    }

    fun getCommandsFor(state: SMDPState<D>): List<ProbabilisticCommand<SMDPCommandAction>> {
        return cache.computeIfAbsent(state.locs) { computeCommands(state) }
    }

    override fun getAvailableCommands(state: SMDPState<D>): Collection<ProbabilisticCommand<SMDPCommandAction>> {
        return getCommandsFor(state)
    }
}

class SmdpInitFunc<D: ExprState, P: Prec>(
    val subInitFunc: InitFunc<D, P>,
    val smdp: SMDP
): InitFunc<SMDPState<D>, P> {
    private fun computeLocConfigs(): List<List<SMDP.Location>> {
        val locLists = smdp.automata.map(SMDP.AutomatonInstance::initLocs)
        var res = listOf<List<SMDP.Location>>(listOf())
        for (locList in locLists) {
            val next = res.flatMap { prev -> locList.map { new -> prev+new } }
            res = next
        }
        return res
    }

    private val initLocConfigs = computeLocConfigs()

    override fun getInitStates(prec: P): Collection<SMDPState<D>> {
        return subInitFunc.getInitStates(prec).flatMap {
            initLocConfigs.map { initLocConfig -> SMDPState(it, initLocConfig) }
        }
    }
}

fun nextLocs(currLocs: List<SMDP.Location>, dest: SMDP.ComposedDestination): List<SMDP.Location> {
    val res = ArrayList(currLocs)
    for (loc in dest.locs) {
        var i = -1
        for (currLoc in res) {
            i++
            if (currLoc.parent == loc.parent) break
        }
        res[i] = loc
    }
    return res
}

