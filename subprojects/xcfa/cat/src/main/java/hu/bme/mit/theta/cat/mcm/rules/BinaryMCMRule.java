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

package hu.bme.mit.theta.cat.mcm.rules;

import hu.bme.mit.theta.cat.mcm.MCMRelation;
import hu.bme.mit.theta.cat.mcm.MCMRule;

import java.util.Map;

public abstract class BinaryMCMRule extends MCMRule {
    protected final MCMRelation e1;
    protected final MCMRelation e2;

    protected BinaryMCMRule(MCMRelation e1, MCMRelation e2) {
        this.e1 = e1;
        this.e2 = e2;
    }

    @Override
    public void collectRelations(final Map<String, MCMRelation> relations) {
        e1.collectRelations(relations);
        e2.collectRelations(relations);
    }
}
