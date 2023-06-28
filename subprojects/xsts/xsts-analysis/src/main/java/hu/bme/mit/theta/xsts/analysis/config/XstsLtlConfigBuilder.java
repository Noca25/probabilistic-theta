/*
 *  Copyright 2022 Budapest University of Technology and Economics
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package hu.bme.mit.theta.xsts.analysis.config;

import hu.bme.mit.theta.analysis.*;
import hu.bme.mit.theta.analysis.algorithm.ArgBuilder;
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker;
import hu.bme.mit.theta.analysis.algorithm.cegar.Abstractor;
import hu.bme.mit.theta.analysis.algorithm.cegar.BasicAbstractor;
import hu.bme.mit.theta.analysis.algorithm.cegar.CegarChecker;
import hu.bme.mit.theta.analysis.algorithm.cegar.Refiner;
import hu.bme.mit.theta.analysis.algorithm.cegar.abstractor.StopCriterions;
import hu.bme.mit.theta.analysis.buchi.BuchiState;
import hu.bme.mit.theta.analysis.expl.*;
import hu.bme.mit.theta.analysis.expr.ExprStatePredicate;
import hu.bme.mit.theta.analysis.expr.refinement.*;
import hu.bme.mit.theta.analysis.live2reach.Live2SafeLTS;
import hu.bme.mit.theta.analysis.ltl.BuchiEnhancedAction;
import hu.bme.mit.theta.analysis.ltl.BuchiEnhancedAnalysis;
import hu.bme.mit.theta.analysis.ltl.BuchiEnhancedLTS;
import hu.bme.mit.theta.analysis.ltl.LTL2Buchi;
import hu.bme.mit.theta.analysis.pred.*;
import hu.bme.mit.theta.analysis.prod2.Prod2Analysis;
import hu.bme.mit.theta.analysis.prod2.Prod2Prec;
import hu.bme.mit.theta.analysis.prod2.Prod2State;
import hu.bme.mit.theta.analysis.prod2.prod2explpred.*;
import hu.bme.mit.theta.analysis.stmtoptimizer.DefaultStmtOptimizer;
import hu.bme.mit.theta.analysis.waitlist.PriorityWaitlist;
import hu.bme.mit.theta.common.logging.Logger;
import hu.bme.mit.theta.common.logging.NullLogger;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.stmt.Stmt;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolExprs;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.solver.Solver;
import hu.bme.mit.theta.solver.SolverFactory;
import hu.bme.mit.theta.xsts.XSTS;
import hu.bme.mit.theta.xsts.analysis.*;
import kotlin.Triple;
import kotlin.jvm.functions.Function2;

import java.util.HashMap;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static hu.bme.mit.theta.xsts.analysis.config.XstsConfigBuilder.*;

public class XstsLtlConfigBuilder {

	private Logger logger = NullLogger.getInstance();
	private final SolverFactory solverFactory;
	private final Domain domain;
	private final Refinement refinement;
	private Search search = Search.BFS;
	private PredSplit predSplit = PredSplit.WHOLE;
	private int maxEnum = 0;
	private InitPrec initPrec = InitPrec.EMPTY;
	private PruneStrategy pruneStrategy = PruneStrategy.LAZY;
	private OptimizeStmts optimizeStmts = OptimizeStmts.ON;
	private AutoExpl autoExpl = AutoExpl.NEWOPERANDS;
	private String ltl = null;
	private boolean noCover = false;

	public XstsLtlConfigBuilder(final Domain domain, final Refinement refinement, final SolverFactory solverFactory) {
		this.domain = domain;
		this.refinement = refinement;
		this.solverFactory = solverFactory;
	}

	public XstsLtlConfigBuilder noCover(boolean noCover) {
		this.noCover = noCover;
		return this;
	}

	public XstsLtlConfigBuilder logger(final Logger logger) {
		this.logger = logger;
		return this;
	}

	public XstsLtlConfigBuilder search(final Search search) {
		this.search = search;
		return this;
	}

	public XstsLtlConfigBuilder predSplit(final PredSplit predSplit) {
		this.predSplit = predSplit;
		return this;
	}

	public XstsLtlConfigBuilder maxEnum(final int maxEnum) {
		this.maxEnum = maxEnum;
		return this;
	}

	public XstsLtlConfigBuilder initPrec(final InitPrec initPrec) {
		this.initPrec = initPrec;
		return this;
	}

	public XstsLtlConfigBuilder pruneStrategy(final PruneStrategy pruneStrategy) {
		this.pruneStrategy = pruneStrategy;
		return this;
	}

	public XstsLtlConfigBuilder optimizeStmts(final OptimizeStmts optimizeStmts) {
		this.optimizeStmts = optimizeStmts;
		return this;
	}

	public XstsLtlConfigBuilder autoExpl(final AutoExpl autoExpl) {
		this.autoExpl = autoExpl;
		return this;
	}

	public XstsLtlConfigBuilder ltl(final String ltl) {
		this.ltl = ltl;
		return this;
	}

	public XstsConfig<? extends State, ? extends Action, ? extends Prec> build(final XSTS xsts) {
		final Solver abstractionSolver = solverFactory.createSolver();
		Function<Prod2State<? extends XstsState<?>, BuchiState>, ?> projection =
				noCover ? (s) -> s
						: (s) -> new Triple<>(s.getState1().isInitialized(), s.getState1().lastActionWasEnv(), s.getState2().getId());

		var litToIntMap = new HashMap<String, Integer>();
		var varMap = new HashMap<String, VarDecl<?>> ();
		for (VarDecl<?> v : xsts.getVars()) {
			varMap.put(v.getName(), v);
		}
		var automaton = new LTL2Buchi().convert(ltl, varMap, litToIntMap);
		Function2<XstsAction, Stmt, XstsAction> stmtReplacer = ((XstsAction a, Stmt newStmt) -> XstsAction.create(newStmt));

		if (domain == Domain.EXPL) {
			final LTS<XstsState<ExplState>, XstsAction> baseLts;
			if(optimizeStmts == OptimizeStmts.ON){
				baseLts = XstsLts.create(xsts, XstsStmtOptimizer.create(ExplStmtOptimizer.getInstance()));
			} else {
				baseLts = XstsLts.create(xsts, XstsStmtOptimizer.create(DefaultStmtOptimizer.create()));
			}

			var ltlLts = new Live2SafeLTS<>(
					new BuchiEnhancedLTS<>(baseLts, automaton), xsts.getVars(),
					(s) -> s.getState2().isAccepting(),
					(s) -> s.getState2().isAccepting(),
					(action, stmt) -> new BuchiEnhancedAction<>(
							stmtReplacer.invoke(action.getBaseAction(), stmt), action.getBuchiAction()
					)
			);

			Expr<BoolType> targetExpr = ltlLts.getTargetExpr();
			final Predicate<XstsState<ExplState>> target = new XstsStatePredicate<>(
					new ExplStatePredicate(targetExpr, abstractionSolver)
			);

			var initFormula = ltlLts.extendInitExpr(BoolExprs.And(xsts.getInitFormula(), automaton.getInitExpr()));

			final Analysis<Prod2State<XstsState<ExplState>, BuchiState>, BuchiEnhancedAction<XstsAction>, ExplPrec> analysis =
					new BuchiEnhancedAnalysis<>(
							XstsAnalysis.create(ExplStmtAnalysis.create(abstractionSolver, initFormula, maxEnum)),
							automaton,
							stmtReplacer
					);
			final ArgBuilder<Prod2State<XstsState<ExplState>, BuchiState>, BuchiEnhancedAction<XstsAction>, ExplPrec> argBuilder =
					ArgBuilder.create(ltlLts, analysis, (x)-> target.test(x.getState1()),
					true);

			final Abstractor<Prod2State<XstsState<ExplState>, BuchiState>, BuchiEnhancedAction<XstsAction>, ExplPrec> abstractor =
					BasicAbstractor.builder(argBuilder)
					.waitlist(PriorityWaitlist.create(search.comparator))
					.stopCriterion(refinement == Refinement.MULTI_SEQ ? StopCriterions.fullExploration()
							: StopCriterions.firstCex())
					.logger(logger)
					.projection(projection)
					.build();

			Refiner<Prod2State<XstsState<ExplState>, BuchiState>, BuchiEnhancedAction<XstsAction>, ExplPrec> refiner = null;

			switch (refinement) {
				case FW_BIN_ITP:
					refiner = SingleExprTraceRefiner.create(ExprTraceFwBinItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver()),
							JoiningPrecRefiner.create(new ItpRefToExplPrec()), pruneStrategy, logger);
					break;
				case BW_BIN_ITP:
					refiner = SingleExprTraceRefiner.create(ExprTraceBwBinItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver()),
							JoiningPrecRefiner.create(new ItpRefToExplPrec()), pruneStrategy, logger);
					break;
				case SEQ_ITP:
					refiner = SingleExprTraceRefiner.create(ExprTraceSeqItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver()),
							JoiningPrecRefiner.create(new ItpRefToExplPrec()), pruneStrategy, logger);
					break;
				case MULTI_SEQ:
					refiner = MultiExprTraceRefiner.create(ExprTraceSeqItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver()),
							JoiningPrecRefiner.create(new ItpRefToExplPrec()), pruneStrategy, logger);
					break;
				case UNSAT_CORE:
					refiner = SingleExprTraceRefiner.create(ExprTraceUnsatCoreChecker.create(initFormula, targetExpr, solverFactory.createUCSolver()),
							JoiningPrecRefiner.create(new VarsRefToExplPrec()), pruneStrategy, logger);
					break;
				default:
					throw new UnsupportedOperationException(domain + " domain does not support " + refinement + " refinement.");
			}

			final SafetyChecker<Prod2State<XstsState<ExplState>, BuchiState>, BuchiEnhancedAction<XstsAction>, ExplPrec> checker =
					CegarChecker.create(abstractor, refiner, logger);
			final ExplPrec prec = initPrec.builder.createExpl(xsts);
			return XstsConfig.create(checker, prec);

		} else if (domain == Domain.PRED_BOOL || domain == Domain.PRED_CART || domain == Domain.PRED_SPLIT) {

			PredAbstractors.PredAbstractor predAbstractor = null;
			switch (domain) {
				case PRED_BOOL:
					predAbstractor = PredAbstractors.booleanAbstractor(abstractionSolver);
					break;
				case PRED_SPLIT:
					predAbstractor = PredAbstractors.booleanSplitAbstractor(abstractionSolver);
					break;
				case PRED_CART:
					predAbstractor = PredAbstractors.cartesianAbstractor(abstractionSolver);
					break;
				default:
					throw new UnsupportedOperationException(domain + " domain is not supported.");
			}

			final LTS<XstsState<PredState>, XstsAction> baseLts;
			if(optimizeStmts == OptimizeStmts.ON){
				baseLts = XstsLts.create(xsts,XstsStmtOptimizer.create(PredStmtOptimizer.getInstance()));
			} else {
				baseLts = XstsLts.create(xsts, XstsStmtOptimizer.create(DefaultStmtOptimizer.create()));
			}
			var ltlLts = new Live2SafeLTS<>(
					new BuchiEnhancedLTS<>(baseLts, automaton), xsts.getVars(),
					(s) -> s.getState2().isAccepting(),
					(s) -> s.getState2().isAccepting(),
					(action, stmt) -> new BuchiEnhancedAction<>(
							stmtReplacer.invoke(action.getBaseAction(), stmt), action.getBuchiAction()
					)
			);

			var targetExpr = ltlLts.getTargetExpr();
			final Predicate<XstsState<PredState>> target = new XstsStatePredicate<>(new ExprStatePredicate(targetExpr, abstractionSolver));
			var initFormula = ltlLts.extendInitExpr(BoolExprs.And(xsts.getInitFormula(), automaton.getInitExpr()));


			final Analysis<Prod2State<XstsState<PredState>, BuchiState>, BuchiEnhancedAction<XstsAction>, PredPrec> analysis =
					new BuchiEnhancedAnalysis<>(
						XstsAnalysis.create(PredAnalysis.create(abstractionSolver, predAbstractor, initFormula)),
						automaton,
						stmtReplacer
					);
			final ArgBuilder<Prod2State<XstsState<PredState>, BuchiState>, BuchiEnhancedAction<XstsAction>, PredPrec> argBuilder =
					ArgBuilder.create(ltlLts, analysis, (x)-> target.test(x.getState1()),
					true);
			final Abstractor<Prod2State<XstsState<PredState>, BuchiState>, BuchiEnhancedAction<XstsAction>, PredPrec> abstractor =
					BasicAbstractor.builder(argBuilder)
					.waitlist(PriorityWaitlist.create(search.comparator))
					.stopCriterion(refinement == Refinement.MULTI_SEQ ? StopCriterions.fullExploration()
							: StopCriterions.firstCex())
					.logger(logger)
					.projection(projection)
					.build();

			ExprTraceChecker<ItpRefutation> exprTraceChecker = null;
			switch (refinement) {
				case FW_BIN_ITP:
					exprTraceChecker = ExprTraceFwBinItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver());
					break;
				case BW_BIN_ITP:
					exprTraceChecker = ExprTraceBwBinItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver());
					break;
				case SEQ_ITP:
					exprTraceChecker = ExprTraceSeqItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver());
					break;
				case MULTI_SEQ:
					exprTraceChecker = ExprTraceSeqItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver());
					break;
				default:
					throw new UnsupportedOperationException(
							domain + " domain does not support " + refinement + " refinement.");
			}
			Refiner<Prod2State<XstsState<PredState>, BuchiState>, BuchiEnhancedAction<XstsAction>, PredPrec> refiner;
			if (refinement == Refinement.MULTI_SEQ) {
				refiner = MultiExprTraceRefiner.create(exprTraceChecker,
						JoiningPrecRefiner.create(new ItpRefToPredPrec(predSplit.splitter)), pruneStrategy, logger);
			} else {
				refiner = SingleExprTraceRefiner.create(exprTraceChecker,
						JoiningPrecRefiner.create(new ItpRefToPredPrec(predSplit.splitter)), pruneStrategy, logger);
			}

			final SafetyChecker<Prod2State<XstsState<PredState>, BuchiState>, BuchiEnhancedAction<XstsAction>, PredPrec> checker =
					CegarChecker.create(abstractor, refiner, logger);

			final PredPrec prec = initPrec.builder.createPred(xsts);
			return XstsConfig.create(checker, prec);
		} else if (domain == Domain.EXPL_PRED_BOOL || domain == Domain.EXPL_PRED_CART || domain == Domain.EXPL_PRED_SPLIT || domain == Domain.EXPL_PRED_COMBINED) {
			final LTS<XstsState<Prod2State<ExplState,PredState>>, XstsAction> baseLts;
			if(optimizeStmts == OptimizeStmts.ON){
				baseLts = XstsLts.create(xsts,XstsStmtOptimizer.create(
						Prod2ExplPredStmtOptimizer.create(
								ExplStmtOptimizer.getInstance()
						)));
			} else {
				baseLts = XstsLts.create(xsts, XstsStmtOptimizer.create(DefaultStmtOptimizer.create()));
			}
			var ltlLts = new Live2SafeLTS<>(
					new BuchiEnhancedLTS<>(baseLts, automaton), xsts.getVars(),
					(s) -> s.getState2().isAccepting(),
					(s) -> s.getState2().isAccepting(),
					(action, stmt) -> new BuchiEnhancedAction<>(
							stmtReplacer.invoke(action.getBaseAction(), stmt), action.getBuchiAction()
					)
			);
			var targetExpr = ltlLts.getTargetExpr();
			var initFormula = ltlLts.extendInitExpr(BoolExprs.And(xsts.getInitFormula(), automaton.getInitExpr()));


			final Analysis<Prod2State<ExplState,PredState>,XstsAction,Prod2Prec<ExplPrec,PredPrec>> prod2Analysis;
			final Predicate<XstsState<Prod2State<ExplState, PredState>>> target =
					new XstsStatePredicate<>(new ExprStatePredicate(targetExpr, abstractionSolver));
			if(domain == Domain.EXPL_PRED_BOOL || domain == Domain.EXPL_PRED_CART || domain == Domain.EXPL_PRED_SPLIT){
				final PredAbstractors.PredAbstractor predAbstractor;
				switch (domain) {
					case EXPL_PRED_BOOL:
						predAbstractor = PredAbstractors.booleanAbstractor(abstractionSolver);
						break;
					case EXPL_PRED_SPLIT:
						predAbstractor = PredAbstractors.booleanSplitAbstractor(abstractionSolver);
						break;
					case EXPL_PRED_CART:
						predAbstractor = PredAbstractors.cartesianAbstractor(abstractionSolver);
						break;
					default:
						throw new UnsupportedOperationException(domain + " domain is not supported.");
				}
				prod2Analysis = Prod2Analysis.create(
						ExplStmtAnalysis.create(abstractionSolver, initFormula, maxEnum),
						PredAnalysis.create(abstractionSolver, predAbstractor, initFormula),
						Prod2ExplPredPreStrengtheningOperator.create(),
						Prod2ExplPredStrengtheningOperator.create(abstractionSolver));
			} else {
				final Prod2ExplPredAbstractors.Prod2ExplPredAbstractor prodAbstractor = Prod2ExplPredAbstractors.booleanAbstractor(abstractionSolver);
				prod2Analysis = Prod2ExplPredAnalysis.create(
						ExplAnalysis.create(abstractionSolver, initFormula),
						PredAnalysis.create(abstractionSolver, PredAbstractors.booleanAbstractor(abstractionSolver), initFormula),
						Prod2ExplPredStrengtheningOperator.create(abstractionSolver),
						prodAbstractor);
			}
			final Analysis<Prod2State<XstsState<Prod2State<ExplState, PredState>>, BuchiState>, BuchiEnhancedAction<XstsAction>, Prod2Prec<ExplPrec, PredPrec>> analysis =
					new BuchiEnhancedAnalysis<>(
							XstsAnalysis.create(prod2Analysis),
							automaton,
							stmtReplacer
					);

			final ArgBuilder<Prod2State<XstsState<Prod2State<ExplState, PredState>>, BuchiState>, BuchiEnhancedAction<XstsAction>, Prod2Prec<ExplPrec, PredPrec>> argBuilder =
					ArgBuilder.create(
							ltlLts,
							analysis,
							(x)->target.test(x.getState1()),
					true);
			final Abstractor<Prod2State<XstsState<Prod2State<ExplState, PredState>>, BuchiState>, BuchiEnhancedAction<XstsAction>, Prod2Prec<ExplPrec, PredPrec>> abstractor =
					BasicAbstractor.builder(argBuilder)
					.waitlist(PriorityWaitlist.create(search.comparator))
					.stopCriterion(refinement == Refinement.MULTI_SEQ ? StopCriterions.fullExploration()
							: StopCriterions.firstCex())
					.logger(logger)
					.projection(projection)
					.build();

			Refiner<Prod2State<XstsState<Prod2State<ExplState, PredState>>, BuchiState>, BuchiEnhancedAction<XstsAction>, Prod2Prec<ExplPrec, PredPrec>> refiner = null;

			final Set<VarDecl<?>> ctrlVars = xsts.getCtrlVars();
			final RefutationToPrec<Prod2Prec<ExplPrec, PredPrec>, ItpRefutation> precRefiner = AutomaticItpRefToProd2ExplPredPrec.create(autoExpl.builder.create(xsts), predSplit.splitter);

			switch (refinement) {
				case FW_BIN_ITP:
					refiner = SingleExprTraceRefiner.create(ExprTraceFwBinItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver()),
							JoiningPrecRefiner.create(precRefiner), pruneStrategy, logger);
					break;
				case BW_BIN_ITP:
					refiner = SingleExprTraceRefiner.create(ExprTraceBwBinItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver()),
							JoiningPrecRefiner.create(precRefiner), pruneStrategy, logger);
					break;
				case SEQ_ITP:
					refiner = SingleExprTraceRefiner.create(ExprTraceSeqItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver()),
							JoiningPrecRefiner.create(precRefiner), pruneStrategy, logger);
					break;
				case MULTI_SEQ:
					refiner = MultiExprTraceRefiner.create(ExprTraceSeqItpChecker.create(initFormula, targetExpr, solverFactory.createItpSolver()),
							JoiningPrecRefiner.create(precRefiner), pruneStrategy, logger);
					break;
				default:
					throw new UnsupportedOperationException(
							domain + " domain does not support " + refinement + " refinement.");
			}

			final SafetyChecker<Prod2State<XstsState<Prod2State<ExplState, PredState>>, BuchiState>, BuchiEnhancedAction<XstsAction>, Prod2Prec<ExplPrec, PredPrec>> checker =
					CegarChecker.create(abstractor, refiner, logger);
			final Prod2Prec<ExplPrec, PredPrec> prec = initPrec.builder.createProd2ExplPred(xsts);
			return XstsConfig.create(checker, prec);
		} else {
			throw new UnsupportedOperationException(domain + " domain is not supported.");
		}
	}


}