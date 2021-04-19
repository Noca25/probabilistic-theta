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
import hu.bme.mit.theta.analysis.Prec;
import hu.bme.mit.theta.analysis.State;
import hu.bme.mit.theta.analysis.algorithm.Abstraction;

/**
 * Common interface for the abstractor component. It can create an initial Abstraction
 * and check an Abstraction with a given precision.
 */
public interface Abstractor<
        TState extends State,
        TAction extends Action,
        TAbstraction extends Abstraction<TState, TAction>,
        TPrecision extends Prec
        > {
    /**
     * Create initial abstraction.
     *
     * @return
     */
    TAbstraction createAbstraction();

    /**
     * Check the abstraction with given precision.
     *
     * @param abstraction
     * @param prec
     * @return
     */
    AbstractorResult check(TAbstraction abstraction, TPrecision prec);
}
