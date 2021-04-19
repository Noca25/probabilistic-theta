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

import hu.bme.mit.theta.analysis.Action;
import hu.bme.mit.theta.analysis.Counterexample;
import hu.bme.mit.theta.analysis.Prec;
import hu.bme.mit.theta.analysis.State;
import hu.bme.mit.theta.analysis.algorithm.Abstraction;
import hu.bme.mit.theta.analysis.algorithm.cegar.RefinerResult;

/**
 * Common interface for refiners. It takes an ARG and a precision, checks if the
 * counterexample in the ARG is feasible and if not, it refines the precision
 * and may also prune the ARG.
 */
public interface Refiner<
		TState extends State,
		TAction extends Action,
		TAbstraction extends Abstraction<TState, TAction>,
		TPrec extends Prec,
		TCex extends Counterexample<TState, TAction>
		> {

	/**
	 * Checks if the counterexample in the Abstraction is feasible. If not, refines the
	 * precision and prunes the Abstraction.
	 *
	 * @param arg
	 * @param prec
	 * @return
	 */
	RefinerResult<TState, TAction, TPrec, TCex> refine(TAbstraction arg, TPrec prec);
}
