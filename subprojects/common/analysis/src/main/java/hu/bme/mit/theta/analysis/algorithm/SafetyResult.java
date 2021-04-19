/*
 * Copyright 2021 Budapest University of Technology and Economics
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package hu.bme.mit.theta.analysis.algorithm;

import hu.bme.mit.theta.analysis.Action;
import hu.bme.mit.theta.analysis.Counterexample;
import hu.bme.mit.theta.analysis.State;
import hu.bme.mit.theta.common.Utils;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class SafetyResult<
		TState extends State,
		TAction extends Action,
		TAbstraction extends Abstraction<TState, TAction>,
		TCex extends Counterexample<TState, TAction>
		> {
	private final TAbstraction abstraction;
	private final Optional<Statistics> stats;

	private SafetyResult(final TAbstraction abstraction, final Optional<Statistics> stats) {
		this.abstraction = checkNotNull(abstraction);
		this.stats = checkNotNull(stats);
	}

	public TAbstraction getAbstraction() {
		return abstraction;
	}

	public Optional<Statistics> getStats() {
		return stats;
	}

	public static <
			S extends State,
			A extends Action,
			AA extends Abstraction<S, A>,
			C extends Counterexample<S, A>
			> Safe<S, A, AA, C> safe(final AA abstraction) {
		return new Safe<>(abstraction, Optional.empty());
	}

	public static <
			S extends State,
			A extends Action,
			AA extends Abstraction<S, A>,
			C extends Counterexample<S, A>
			> Unsafe<S, A, AA, C> unsafe(final C cex, final AA abstraction) {
		return new Unsafe<>(cex, abstraction, Optional.empty());
	}

	public static <
			S extends State,
			A extends Action,
			AA extends Abstraction<S, A>,
			C extends Counterexample<S, A>
			> Safe<S, A, AA, C> safe(final AA abstraction, final Statistics stats) {
		return new Safe<>(abstraction, Optional.of(stats));
	}

	public static <
			S extends State,
			A extends Action,
			AA extends Abstraction<S, A>,
			C extends Counterexample<S, A>
			> Unsafe<S, A, AA, C> unsafe(final C cex, final AA abstraction,
																		  final Statistics stats) {
		return new Unsafe<>(cex, abstraction, Optional.of(stats));
	}

	public abstract boolean isSafe();

	public abstract boolean isUnsafe();

	public abstract Safe<TState, TAction, TAbstraction, TCex> asSafe();

	public abstract Unsafe<TState, TAction, TAbstraction, TCex> asUnsafe();

	////

	public static final class Safe<
			S extends State,
			A extends Action,
			AA extends Abstraction<S, A>,
			C extends Counterexample<S, A>
			> extends SafetyResult<S, A, AA, C> {
		private Safe(final AA arg, final Optional<Statistics> stats) {
			super(arg, stats);
		}

		@Override
		public boolean isSafe() {
			return true;
		}

		@Override
		public boolean isUnsafe() {
			return false;
		}

		@Override
		public Safe<S, A, AA, C> asSafe() {
			return this;
		}

		@Override
		public Unsafe<S, A, AA, C> asUnsafe() {
			throw new ClassCastException(
					"Cannot cast " + Safe.class.getSimpleName() + " to " + Unsafe.class.getSimpleName());
		}

		@Override
		public String toString() {
			return Utils.lispStringBuilder(SafetyResult.class.getSimpleName()).add(Safe.class.getSimpleName())
					.toString();
		}
	}

	public static final class Unsafe<
			S extends State,
			A extends Action,
			AA extends Abstraction<S, A>,
			C extends Counterexample<S, A>
			> extends SafetyResult<S, A, AA, C> {
		private final C cex;

		private Unsafe(final C cex, final AA arg, final Optional<Statistics> stats) {
			super(arg, stats);
			this.cex = checkNotNull(cex);
		}

		public C getTrace() {
			return cex;
		}

		@Override
		public boolean isSafe() {
			return false;
		}

		@Override
		public boolean isUnsafe() {
			return true;
		}

		@Override
		public Safe<S, A, AA, C> asSafe() {
			throw new ClassCastException(
					"Cannot cast " + Unsafe.class.getSimpleName() + " to " + Safe.class.getSimpleName());
		}

		@Override
		public Unsafe<S, A, AA, C> asUnsafe() {
			return this;
		}

		@Override
		public String toString() {
			return Utils.lispStringBuilder(SafetyResult.class.getSimpleName()).add(Unsafe.class.getSimpleName())
					.add("Trace length: " + cex.length()).toString();
		}
	}

}
