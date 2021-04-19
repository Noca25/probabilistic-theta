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

package hu.bme.mit.theta.analysis.algorithm.cegar;

import com.google.common.base.Stopwatch;
import hu.bme.mit.theta.analysis.Action;
import hu.bme.mit.theta.analysis.Counterexample;
import hu.bme.mit.theta.analysis.Prec;
import hu.bme.mit.theta.analysis.State;
import hu.bme.mit.theta.common.Utils;
import hu.bme.mit.theta.common.logging.Logger;
import hu.bme.mit.theta.common.logging.Logger.Level;
import hu.bme.mit.theta.common.logging.NullLogger;
import hu.bme.mit.theta.analysis.algorithm.Abstraction;
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker;
import hu.bme.mit.theta.analysis.algorithm.SafetyResult;

import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Counterexample-Guided Abstraction Refinement (CEGAR) loop implementation,
 * that uses an Abstractor to explore the abstract state space and a Refiner to
 * check counterexamples and refine them if needed. It also provides certain
 * statistics about its execution.
 */
public class CegarChecker<
		TState extends State,
		TAction extends Action,
		TAbstraction extends Abstraction<TState, TAction>,
		TPrec extends Prec,
		TCex extends Counterexample<TState, TAction>
> implements SafetyChecker<TState, TAction, TPrec, TAbstraction, TCex> {

	private final Abstractor<TState, TAction, TAbstraction, TPrec> abstractor;
	private final Refiner<TState, TAction, TAbstraction, TPrec, TCex> refiner;
	private final Logger logger;

	protected CegarChecker(final Abstractor<TState, TAction, TAbstraction, TPrec> abstractor, final Refiner<TState, TAction, TAbstraction, TPrec, TCex> refiner, final Logger logger) {
		this.abstractor = checkNotNull(abstractor);
		this.refiner = checkNotNull(refiner);
		this.logger = checkNotNull(logger);
	}

	public static <S extends State, A extends Action, AA extends Abstraction<S, A>, P extends Prec, C extends Counterexample<S, A>> CegarChecker<S, A, AA, P, C> create(
			final Abstractor<S, A, AA, P> abstractor, final Refiner<S, A, AA, P, C> refiner) {
		return new CegarChecker<>(abstractor, refiner, NullLogger.getInstance());
	}

	public static <S extends State, A extends Action, AA extends Abstraction<S, A>, P extends Prec, C extends Counterexample<S, A>> CegarChecker<S, A, AA, P, C> create(
			final Abstractor<S, A, AA, P> abstractor, final Refiner<S, A, AA, P, C> refiner, final Logger logger) {
		return new CegarChecker<>(abstractor, refiner, logger);
	}

	@Override
	public SafetyResult<TState, TAction, TAbstraction, TCex> check(final TPrec initPrec) {
		logger.write(Level.INFO, "Configuration: %s%n", this);
		final Stopwatch stopwatch = Stopwatch.createStarted();
		long abstractorTime = 0;
		long refinerTime = 0;
		RefinerResult<TState, TAction, TPrec, TCex> refinerResult = null;
		AbstractorResult abstractorResult = null;
		final TAbstraction arg = abstractor.createAbstraction();
		TPrec prec = initPrec;
		int iteration = 0;
		do {
			++iteration;

			logger.write(Level.MAINSTEP, "Iteration %d%n", iteration);
			logger.write(Level.MAINSTEP, "| Checking abstraction...%n");
			final long abstractorStartTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
			abstractorResult = abstractor.check(arg, prec);
			abstractorTime += stopwatch.elapsed(TimeUnit.MILLISECONDS) - abstractorStartTime;
			logger.write(Level.MAINSTEP, "| Checking abstraction done, result: %s%n", abstractorResult);

			if (abstractorResult.isUnsafe()) {
				logger.write(Level.MAINSTEP, "| Refining abstraction...%n");
				final long refinerStartTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
				refinerResult = refiner.refine(arg, prec);
				refinerTime += stopwatch.elapsed(TimeUnit.MILLISECONDS) - refinerStartTime;
				logger.write(Level.MAINSTEP, "Refining abstraction done, result: %s%n", refinerResult);

				if (refinerResult.isSpurious()) {
					prec = refinerResult.asSpurious().getRefinedPrec();
				}
			}

		} while (!abstractorResult.isSafe() && !refinerResult.isUnsafe());

		stopwatch.stop();
		SafetyResult<TState, TAction, TAbstraction, TCex> cegarResult = null;
		final CegarStatistics stats = new CegarStatistics(stopwatch.elapsed(TimeUnit.MILLISECONDS), abstractorTime,
				refinerTime, iteration);

		assert abstractorResult.isSafe() || (refinerResult != null && refinerResult.isUnsafe());

		if (abstractorResult.isSafe()) {
			cegarResult = SafetyResult.safe(arg, stats);
		} else if (refinerResult.isUnsafe()) {
			cegarResult = SafetyResult.unsafe(refinerResult.asUnsafe().getCex(), arg, stats);
		}

		assert cegarResult != null;
		logger.write(Level.RESULT, "%s%n", cegarResult);
		logger.write(Level.INFO, "%s%n", stats);
		return cegarResult;
	}

	@Override
	public String toString() {
		return Utils.lispStringBuilder(getClass().getSimpleName()).add(abstractor).add(refiner).toString();
	}
}
