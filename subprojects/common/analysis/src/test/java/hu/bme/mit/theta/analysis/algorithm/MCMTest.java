/*
 *  Copyright 2017 Budapest University of Technology and Economics
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
package hu.bme.mit.theta.analysis.algorithm;

import hu.bme.mit.theta.analysis.*;
import hu.bme.mit.theta.analysis.algorithm.mcm.*;
import hu.bme.mit.theta.analysis.algorithm.mcm.rules.Inverse;
import hu.bme.mit.theta.analysis.algorithm.mcm.rules.Sequence;
import hu.bme.mit.theta.analysis.algorithm.mcm.rules.Union;
import hu.bme.mit.theta.analysis.expl.ExplState;
import hu.bme.mit.theta.analysis.expr.ExprState;
import hu.bme.mit.theta.analysis.expr.StmtAction;
import hu.bme.mit.theta.common.logging.NullLogger;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.stmt.Stmt;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.solver.z3.Z3SolverFactory;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static hu.bme.mit.theta.core.decl.Decls.Var;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.True;
import static hu.bme.mit.theta.core.type.inttype.IntExprs.Int;

public class MCMTest {
	@Test
	public void test() {
		final VarDecl<?> var = Var("x", Int());
		final TestState thrd1_loc3 = new TestState(List.of());
		final TestState thrd1_loc2 = new TestState(List.of(new TestAction(new MemoryEvent.Write(-3, var, null, Set.of(), "meta"), thrd1_loc3)));
		final TestState thrd1_loc1 = new TestState(List.of(new TestAction(new MemoryEvent.Write(-3, var, null, Set.of(), "meta"), thrd1_loc2)));

		final TestState thrd2_loc3 = new TestState(List.of());
		final TestState thrd2_loc2 = new TestState(List.of(new TestAction(new MemoryEvent.Read(-3, var, null, "meta"), thrd2_loc3)));
		final TestState thrd2_loc1 = new TestState(List.of(new TestAction(new MemoryEvent.Read(-3, var, null, "meta"), thrd2_loc2)));

		MCM mcm = new MCM("example");
		MCMRelation sc = new MCMRelation(2, "sc");
		MCMRelation po = new MCMRelation(2, "po");
		MCMRelation rf = new MCMRelation(2, "rf");
		MCMRelation fr = new MCMRelation(2, "fr");
		MCMRelation fr1 = new MCMRelation(2, "fr1");
		MCMRelation co = new MCMRelation(2, "co");
		MCMRelation com = new MCMRelation(2, "com");
		MCMRelation com1 = new MCMRelation(2, "com1");
		sc.addRule(new Union(po, com));
		com1.addRule(new Union(rf, fr));
		com.addRule(new Union(com1, co));
		fr1.addRule(new Inverse(rf));
		fr.addRule(new Sequence(fr1, co));
		mcm.addConstraint(new MCMConstraint(sc, MCMConstraint.ConstraintType.ACYCLIC));


		MCMChecker<TestState, TestAction, TestPrec> mcmChecker = new MCMChecker<>(
				new TestMemoryEventProvider(),
				new MultiprocLTS<>(Map.of(-1, new TestLTS(), -2, new TestLTS())),
				new MultiprocInitFunc<>(Map.of(-1, new TestInitFunc(thrd1_loc1), -2, new TestInitFunc(thrd2_loc1))),
				new MultiprocTransFunc<>(Map.of(-1, new TestTransFunc(), -2, new TestTransFunc())),
				List.of(-1, -2), List.<MemoryEvent.Write>of(), new TestPartialOrder(), ExplState.top(), Z3SolverFactory.getInstance().createSolver(), mcm, NullLogger.getInstance());

//		mcmChecker.check(new TestPrec());
	}

	private class TestMemoryEventProvider implements MemoryEventProvider<TestState, TestAction> {
		@Override
		public Collection<ResultElement<TestAction>> getPiecewiseAction(TestState state, TestAction action) {
			return List.of(new ResultElement<>(action.event));
		}

		@Override
		public int getVarId(VarDecl<?> var) {
			return -3;
		}

		@Override
		public TestAction createAction(TestState s, List<Stmt> stmt) {
			return null;
		}
	}
	private class TestLTS implements LTS<TestState, TestAction> {

		@Override
		public Collection<TestAction> getEnabledActionsFor(TestState state) {
			return state.outgoingActions;
		}
	}
	private class TestInitFunc implements InitFunc<TestState, TestPrec> {
		private final TestState initState;

		private TestInitFunc(TestState initState) {
			this.initState = initState;
		}

		@Override
		public Collection<? extends TestState> getInitStates(TestPrec prec) {
			return List.of(initState);
		}
	}
	private class TestTransFunc implements TransFunc<TestState, TestAction, TestPrec> {

		@Override
		public Collection<? extends TestState> getSuccStates(TestState state, TestAction action, TestPrec prec) {
			return List.of(action.target);
		}
	}

	private class TestState implements ExprState {
		private final Collection<TestAction> outgoingActions;

		private TestState(Collection<TestAction> outgoingActions) {
			this.outgoingActions = outgoingActions;
		}

		@Override
		public boolean isBottom() {
			return false;
		}

		@Override
		public Expr<BoolType> toExpr() {
			return True();
		}
	}

	private class TestAction extends StmtAction {
		private final MemoryEvent event;
		private final TestState target;

		private TestAction(MemoryEvent event, TestState target) {
			this.event = event;
			this.target = target;
		}

		@Override
		public List<Stmt> getStmts() {
			return List.of();
		}
	}

	private class TestPrec implements Prec {
		@Override
		public Collection<VarDecl<?>> getUsedVars() {
			return null;
		}
	}

	private class TestPartialOrder implements PartialOrd<TestState> {
		@Override
		public boolean isLeq(TestState state1, TestState state2) {
			return false;
		}
	}
}