package hu.bme.mit.theta.prob.analysis.menuabstraction

import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expl.ExplState.bottom
import hu.bme.mit.theta.analysis.expl.StmtApplier
import hu.bme.mit.theta.analysis.expl.StmtApplier.ApplyResult
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.decl.IndexedConstDecl
import hu.bme.mit.theta.core.decl.MultiIndexedConstDecl
import hu.bme.mit.theta.core.model.ImmutableValuation
import hu.bme.mit.theta.core.model.MutableValuation
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.core.stmt.Stmts.Assume
import hu.bme.mit.theta.core.stmt.Stmts.SequenceStmt
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.*
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.FalseExpr
import hu.bme.mit.theta.core.type.booltype.TrueExpr
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.core.utils.PathUtils
import hu.bme.mit.theta.core.utils.StmtUtils
import hu.bme.mit.theta.core.utils.indexings.VarIndexing
import hu.bme.mit.theta.core.utils.indexings.VarIndexingFactory
import hu.bme.mit.theta.prob.analysis.ProbabilisticCommand
import hu.bme.mit.theta.prob.analysis.addNewIndexToNonZero
import hu.bme.mit.theta.prob.analysis.extractMultiIndexValuation
import hu.bme.mit.theta.probabilistic.FiniteDistribution
import hu.bme.mit.theta.probabilistic.FiniteDistribution.Companion.dirac
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.utils.WithPushPop

class ExplProbabilisticCommandTransFunc(
    val maxEnum: Int = 0,
    val solver: Solver
) : ProbabilisticCommandTransFunc<ExplState, StmtAction, ExplPrec> {
    init {
        require(maxEnum >= 0) {
            "The number of states to enumerate (maxEnum) must be non-negative, it was set to $maxEnum"
        }
    }

    override fun getNextStates(
        state: ExplState, command: ProbabilisticCommand<StmtAction>, prec: ExplPrec
    ): Collection<FiniteDistribution<ExplState>> {
        val valuation = state.`val`
        val substitutedGuard = PathUtils.unfold(ExprUtils.simplify(command.guard, valuation),0)

        // Compute whether the command can fail/succeed in the given state taking only its guard into account
        // (as the resulting action part is assumed to be only assignment-like)
        val (canFail, canSucceed) = when (substitutedGuard) {
            TrueExpr.getInstance() -> Pair(false, true)
            FalseExpr.getInstance() -> Pair(true, false)
            else -> Pair(WithPushPop(solver).use {
                solver.add(Not(substitutedGuard))
                solver.check()
                return@use solver.status.isSat
            }, WithPushPop(solver).use {
                solver.add(substitutedGuard)
                solver.check()
                return@use solver.status.isSat
            })
        }
        // If the current abstract state violates the guard, the result will always be BOT;
        // this should not really happen as list of available actions should not include one with
        // a surely violated guard, but even if the responsibility of checking this is removed from the LTS
        // (which is a rational decision as LTS is mostly created independently of the type of abstraction),
        // then returning only BOT should keep the algorithm correct
        if (!canSucceed) return listOf(dirac(bottom()))

        val stmtApplicationResults = arrayListOf<ApplyResult>()
        val res = command.result.transform {
            val resultValuation = MutableValuation.copyOf(state)
            StmtApplier.apply(SequenceStmt(listOf(Assume(command.guard))+it.stmts), resultValuation, true)
            prec.createState(resultValuation)
        }
        // If maxEnum is set to 1, then non-deterministic results are always merged to TOP,
        // Which can be handled by the stmt applier
        if (maxEnum == 1) {
            return if (canFail) listOf(res, dirac(bottom())) else listOf(res)
        }
        // This should not happen as the result part of a command is supposed to contain
        // only assignment-like statements, but better safe than sorry
        if (stmtApplicationResults.contains(ApplyResult.BOTTOM)) return listOf(dirac(bottom()))

        var i = 0
        val auxIndex = hashMapOf<Expr<BoolType>, Int>()
        val targetIndexing = hashMapOf<Expr<BoolType>, VarIndexing>()
        val resultExprDistr = command.result.transform {
            val stmtUnfoldResult =
                StmtUtils.toExpr(it.stmts, VarIndexingFactory.indexing(0))

            val expr =
                PathUtils.unfold(
                    //TODO: simplify does not take primes into account, it should be called with a const valuation on the unfolded version
//                    ExprUtils.simplify(
                        And(stmtUnfoldResult.exprs),
//                    valuation),
                    0
                )
            val multiIndexedExpr = addNewIndexToNonZero(expr, i)
            auxIndex[multiIndexedExpr] = i
            i++
            targetIndexing[multiIndexedExpr] = stmtUnfoldResult.indexing
            return@transform multiIndexedExpr
        }

        val results = arrayListOf<FiniteDistribution<ExplState>>()
        WithPushPop(solver).use {
            solver.add(PathUtils.unfold(And(state.toExpr(), command.guard), 0))
            solver.add(And(resultExprDistr.support))
            while (solver.check().isSat) {
                val model = solver.model
                val filteredVals = arrayListOf<Valuation>()
                val newResult =
                    resultExprDistr.transform { e ->
                        val target = targetIndexing[e]!!
                        val aux = auxIndex[e]!!

                        // Creating the new explicit state:
                        // a valuation for varDecls is extracted from the SMT model
                        val newValuation = extractMultiIndexValuation(
                            model,
                            prec.vars,
                            target, aux, true
                        )

                        // The SMT model is filtered so that only those constDecl values
                        // are visible in the feedback that are present in the newly created state
                        val filteredVal = ImmutableValuation.builder().apply {
                            for ((decl, value) in model.toMap().entries) {
                                if (decl is IndexedConstDecl &&
                                    decl.varDecl in prec.vars &&
                                    target[decl.varDecl] == 0 &&
                                    decl.index == 0
                                ) put(decl, value)
                                else if (decl is MultiIndexedConstDecl &&
                                    decl.varDecl in prec.vars &&
                                    decl.indices[0] == target[decl.varDecl] &&
                                    decl.indices[1] == aux
                                ) put(decl, value)
                            }
                        }.build()
                        filteredVals.add(filteredVal)

                        prec.createState(
                            newValuation
                        )
                    }
                results.add(newResult)
                // feedback for all-sat with respect to the resulting abstract state
                solver.add(Not(And(filteredVals.map { it.toExpr() })))
                if (maxEnum != 0 && results.size > maxEnum) {
                    return if (canFail) listOf(res, dirac(bottom())) else listOf(res)
                }
            }
        }

        if(canFail) results.add(dirac(bottom()))
        return results
    }
}