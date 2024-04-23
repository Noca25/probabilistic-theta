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
package hu.bme.mit.theta.core.model;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.google.common.collect.ImmutableMap;

import hu.bme.mit.theta.core.decl.Decl;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.LitExpr;
import hu.bme.mit.theta.core.type.Type;
import hu.bme.mit.theta.core.type.booltype.BoolType;

/**
 * Basic, immutable implementation of a valuation. The inner builder class can
 * be used to create a new instance.
 */
public final class ImmutableValuation extends Valuation {
	public static boolean experimental = false;
	public static Decl<?>[] declOrder = null;
	private int[] array;
	private int isPresent;

	private final Map<Decl<?>, LitExpr<?>> declToExpr;
	private volatile Expr<BoolType> expr = null;

	private static final class LazyHolder {
		private static final ImmutableValuation EMPTY = new Builder().build();
	}

	private final int hashCode;

	private ImmutableValuation(final Builder builder) {
		declToExpr = builder.builder.build();
		hashCode = super.hashCode();

		if(experimental && declOrder != null) {
			isPresent = 0;
			array = new int[declToExpr.size()];
			var i = 0;
			var declIdx = 0;
			for (Decl<?> decl : declOrder) {
				if(declToExpr.containsKey(decl)) {
					array[i++] = declToExpr.get(decl).hashCode();
					isPresent |= 1 << declIdx;
				}
				declIdx++;
			}
			if(i != declToExpr.size())
				array = null;
			//else System.out.println("yaay!");
		} else array = null;
	}

	@Override
	public boolean isLeq(Valuation that) {
		if(experimental && that instanceof ImmutableValuation) {
			ImmutableValuation that1 = (ImmutableValuation) that;
			if (this.array == null || that1.array == null) return super.isLeq(that);
			var thisCurr = 0;
			var thatCurr = 0;
			for(int i = 0; i < declOrder.length; i++) {
				boolean thisPresent = (this.isPresent & (1 << i)) != 0;
				boolean thatPresent = (that1.isPresent & (1 << i)) != 0;
				if(thisPresent && thatPresent) {
					if(array[thisCurr] != that1.array[thatCurr]) return false;
				}
				if (thisPresent) {
					thisCurr += 1;
				}
				if (thatPresent) {
					thatCurr += 1;
				}
			}
		}
		return super.isLeq(that);
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	public static ImmutableValuation copyOf(final Valuation val) {
		if (val instanceof ImmutableValuation) {
			return (ImmutableValuation) val;
		} else {
			final Builder builder = builder();
			for (final Decl<?> decl : val.getDecls()) {
				builder.put(decl, val.eval(decl).get());
			}
			return builder.build();
		}
	}

	public static ImmutableValuation empty() {
		return LazyHolder.EMPTY;
	}

	@Override
	public Collection<Decl<?>> getDecls() {
		return declToExpr.keySet();
	}

	@Override
	public <DeclType extends Type> Optional<LitExpr<DeclType>> eval(final Decl<DeclType> decl) {
		checkNotNull(decl);
		@SuppressWarnings("unchecked") final LitExpr<DeclType> val = (LitExpr<DeclType>) declToExpr.get(decl);
		return Optional.ofNullable(val);
	}

	@Override
	public Expr<BoolType> toExpr() {
		Expr<BoolType> result = expr;
		if (result == null) {
			result = super.toExpr();
			expr = result;
		}
		return result;
	}

	@Override
	public Map<Decl<?>, LitExpr<?>> toMap() {
		return declToExpr;
	}

	public static Builder builder() {
		return new Builder();
	}

	public final static class Builder {
		private final ImmutableMap.Builder<Decl<?>, LitExpr<?>> builder;

		private Builder() {
			builder = ImmutableMap.builder();
		}

		public Builder put(final Decl<?> decl, final LitExpr<?> value) {
			checkArgument(value.getType().equals(decl.getType()), "Type mismatch.");
			builder.put(decl, value);
			return this;
		}

		public ImmutableValuation build() {
			return new ImmutableValuation(this);
		}

	}

}
