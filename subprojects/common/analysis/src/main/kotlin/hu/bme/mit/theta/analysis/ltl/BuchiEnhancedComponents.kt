package hu.bme.mit.theta.analysis.ltl

import hu.bme.mit.theta.analysis.*
import hu.bme.mit.theta.analysis.buchi.BuchiAction
import hu.bme.mit.theta.analysis.buchi.BuchiAutomaton
import hu.bme.mit.theta.analysis.buchi.BuchiState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.analysis.prod2.Prod2Ord
import hu.bme.mit.theta.analysis.prod2.Prod2State
import hu.bme.mit.theta.core.stmt.Stmt
import hu.bme.mit.theta.core.stmt.Stmts

class BuchiEnhancedLTS<S: State, A: StmtAction>(
    val baseLTS: LTS<S, A>,
    val automaton: BuchiAutomaton
): LTS<Prod2State<S, BuchiState>, BuchiEnhancedAction<A>> {
    override fun getEnabledActionsFor(state: Prod2State<S, BuchiState>): Collection<BuchiEnhancedAction<A>> {
        val baseActions = baseLTS.getEnabledActionsFor(state.state1)
        val buchiActions = state.state2.actions
        return baseActions.flatMap { base -> buchiActions.map { buchi -> BuchiEnhancedAction(base, buchi) } }
    }
}

data class BuchiEnhancedAction<A: StmtAction>(val baseAction: A, val buchiAction: BuchiAction): StmtAction() {
    override fun getStmts(): List<Stmt> = baseAction.stmts + buchiAction.stmts
}

class BuchiEnhancedTransFunc<S: State, A: StmtAction, P: Prec>(
    val baseTransFunc: TransFunc<S, A, P>,
    val stmtReplacer: (A, Stmt) -> A
): TransFunc<Prod2State<S, BuchiState>, BuchiEnhancedAction<A>, P> {
    override fun getSuccStates(
        state: Prod2State<S, BuchiState>,
        action: BuchiEnhancedAction<A>,
        prec: P
    ): Collection<Prod2State<S, BuchiState>> {
        val constrainedBaseAction =
            stmtReplacer(action.baseAction, Stmts.SequenceStmt(action.stmts))
        val baseNextStates = baseTransFunc.getSuccStates(state.state1, constrainedBaseAction, prec)
        val buchiNextState =  action.buchiAction.target
        return baseNextStates.map { base -> Prod2State.of(base, buchiNextState) }
    }

}

object BuchiPartialOrd: PartialOrd<BuchiState> {
    override fun isLeq(state1: BuchiState, state2: BuchiState) = state1 == state2
}

class BuchiInitFunc<P: Prec>(val automaton: BuchiAutomaton) : InitFunc<BuchiState, P> {
    override fun getInitStates(prec: P) = listOf(automaton.initial)

}

class BuchiEnhancedInitFunc<S: State, P: Prec>(val baseInitFunc: InitFunc<S, P>, val automaton: BuchiAutomaton): InitFunc<Prod2State<S,BuchiState>, P> {
    override fun getInitStates(prec: P) = baseInitFunc.getInitStates(prec).map { Prod2State.of(it, automaton.initial) }

}

class BuchiEnhancedAnalysis<S: State, A: StmtAction, P: Prec>(
    val baseAnalysis: Analysis<S, A, P>,
    val automaton: BuchiAutomaton,
    val stmtReplacer: (A, Stmt) -> A
): Analysis<Prod2State<S, BuchiState>, BuchiEnhancedAction<A>, P> {

    val ord = Prod2Ord.create(baseAnalysis.partialOrd, BuchiPartialOrd)
    val initFunc = BuchiEnhancedInitFunc(baseAnalysis.initFunc, automaton)

    override fun getPartialOrd(): PartialOrd<Prod2State<S, BuchiState>> =
        ord

    override fun getInitFunc(): InitFunc<Prod2State<S, BuchiState>, P> =
        initFunc

    override fun getTransFunc(): TransFunc<Prod2State<S, BuchiState>, BuchiEnhancedAction<A>, P> =
        BuchiEnhancedTransFunc(baseAnalysis.transFunc, stmtReplacer)

}