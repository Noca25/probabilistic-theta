package hu.bme.mit.theta.prob.analysis.besttransformer

import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.analysis.pred.PredState
import hu.bme.mit.theta.core.decl.ConstDecl
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.And
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.core.utils.PathUtils
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.jani.SMDPCommandAction
import hu.bme.mit.theta.prob.analysis.jani.SMDPState
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.utils.WithPushPop

private val actLits = arrayListOf<ConstDecl<BoolType>>()

fun <A : StmtAction> explGetGuardSatisfactionConfigs(solver: Solver) =
    fun(state: ExplState, commands: List<ProbabilisticCommand<A>>): List<List<ProbabilisticCommand<A>>> {
        val simplifiedGuards = commands.map { ExprUtils.simplify(it.guard, state) }
        val variablesPresent = hashSetOf<VarDecl<*>>()
        var solverNeeded = false

        // If the simplified guards are independent, there is no need to call a solver
        // As deciding exact semantic independence is expensive, we conservatively approximate it
        // by checking whether there is any variable that is present in multiple guards
        for (expr in simplifiedGuards) {
            for (varDecl in ExprUtils.getVars(expr)) {
                if (varDecl in variablesPresent) {
                    solverNeeded = true
                    break
                }
            }
        }

        val mustBeSatisfied = simplifiedGuards.mapIndexedNotNull { idx, guard ->
            if (guard == True()) commands[idx] else null
        }
        val mayBeSatisfied = simplifiedGuards.mapIndexedNotNull { idx, guard ->
            if (guard != True() && guard != False()) commands[idx] else null
        }

        if (solverNeeded) {
            val res = arrayListOf<List<ProbabilisticCommand<A>>>()
            WithPushPop(solver).use {
                var i = 0
                for (guard in simplifiedGuards) {
                    if(guard == True() || guard == False())
                        continue
                    if(actLits.size < i) {
                        actLits.add(Decls.Const("__guardconfigs_actlit_$i", Bool()))
                    }
                    solver.add(Iff(actLits[i].ref, PathUtils.unfold(guard, 0)))
                    i++
                }
                while (solver.check().isSat) {
                    val model = solver.model
                    val config = mustBeSatisfied.toMutableList()
                    val feedback = arrayListOf<Expr<BoolType>>(True())
                    for(i in mayBeSatisfied.indices) {
                        val eval = model.eval(actLits[i])
                        if(eval.isPresent && eval.get() == False())
                            feedback.add(Not(actLits[i].ref))
                        else {
                            config.add(mayBeSatisfied[i])
                            feedback.add(actLits[i].ref)
                        }
                    }
                    res.add(config)
                    solver.add(Not(And(feedback)))
                }
            }
            return res
        } else {
            return mayBeSatisfied.fold(listOf(mustBeSatisfied)) { acc, curr ->
                acc + acc.map { it + curr }
            }
        }
    }

fun <A : StmtAction> predGetGuardSatisfactionConfigs(solver: Solver) =
    fun(state: PredState, commands: List<ProbabilisticCommand<A>>): List<List<ProbabilisticCommand<A>>> {
        val res = arrayListOf<List<ProbabilisticCommand<A>>>()
        WithPushPop(solver).use {
            solver.add(PathUtils.unfold(state.toExpr(), 0))
            var i = 0
            for (command in commands) {
                val guard = command.guard
                if(actLits.size < i) {
                    actLits.add(Decls.Const("__guardconfigs_actlit_$i", Bool()))
                }
                solver.add(Iff(actLits[i].ref, PathUtils.unfold(guard, 0)))
                i++
            }
            while (solver.check().isSat) {
                val model = solver.model
                val config = arrayListOf<ProbabilisticCommand<A>>()
                val feedback = arrayListOf<Expr<BoolType>>(True())
                for(i in commands.indices) {
                    val eval = model.eval(actLits[i])
                    if(eval.isPresent && eval.get() == False())
                        feedback.add(Not(actLits[i].ref))
                    else {
                        config.add(commands[i])
                        feedback.add(actLits[i].ref)
                    }
                }
                res.add(config)
                solver.add(Not(And(feedback)))
            }
        }
        return res
    }

fun <D: ExprState> smdpGetGuardSatisfactionConfigs(
    baseGetGuardSatisfactionConfigs: (state: D, commands: List<ProbabilisticCommand<SMDPCommandAction>>) -> List<List<ProbabilisticCommand<SMDPCommandAction>>>
) = fun(state: SMDPState<D>, commands: List<ProbabilisticCommand<SMDPCommandAction>>) = baseGetGuardSatisfactionConfigs(state.domainState, commands)