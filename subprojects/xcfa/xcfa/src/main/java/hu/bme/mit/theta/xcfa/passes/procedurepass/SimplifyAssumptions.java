/*
 * Copyright 2021 Budapest University of Technology and Economics
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hu.bme.mit.theta.xcfa.passes.procedurepass;

import hu.bme.mit.theta.core.stmt.AssumeStmt;
import hu.bme.mit.theta.core.stmt.Stmt;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.abstracttype.EqExpr;
import hu.bme.mit.theta.core.type.abstracttype.NeqExpr;
import hu.bme.mit.theta.core.type.anytype.IteExpr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.xcfa.model.XcfaEdge;
import hu.bme.mit.theta.frontend.FrontendMetadata;
import hu.bme.mit.theta.xcfa.model.XcfaProcedure;
import hu.bme.mit.theta.frontend.transformation.model.types.complex.CComplexType;

import java.util.ArrayList;
import java.util.List;

import static hu.bme.mit.theta.core.stmt.Stmts.Assume;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.Not;

public class SimplifyAssumptions extends ProcedurePass {
	@Override
	public XcfaProcedure.Builder run(XcfaProcedure.Builder builder) {
		for (XcfaEdge edge : new ArrayList<>(builder.getEdges())) {
			boolean found = false;
			List<Stmt> newStmts = new ArrayList<>();
			for (Stmt stmt : edge.getStmts()) {
				if(stmt instanceof AssumeStmt) {
					Expr<BoolType> cond = ((AssumeStmt) stmt).getCond();
					if(cond instanceof EqExpr || cond instanceof NeqExpr) {
						List<? extends Expr<?>> ops = cond.getOps();
						Expr<?> leftOp = ops.get(0);
						Expr<?> rightOp = ops.get(1);
						if(leftOp instanceof  IteExpr && ((IteExpr<?>) leftOp).getElse().equals(rightOp)) {
							Expr<BoolType> expr;
							if(cond instanceof NeqExpr) expr = ((IteExpr<?>) leftOp).getCond();
							else expr = Not(((IteExpr<?>) leftOp).getCond());
							found = true;
							FrontendMetadata.create(expr, "cType", CComplexType.getType(leftOp));
							newStmts.add(Assume(expr));
						}
					}
				} else newStmts.add(stmt);
			}
			if(found) {
				builder.removeEdge(edge);
				builder.addEdge(new XcfaEdge(edge.getSource(), edge.getTarget(), newStmts));
			}
		}
		return builder;
	}

	@Override
	public boolean isPostInlining() {
		return true;
	}
}
