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

package hu.bme.mit.theta.xcfa.analysis

import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.expr.StmtAction
import hu.bme.mit.theta.core.stmt.Stmt
import hu.bme.mit.theta.xcfa.model.NopLabel
import hu.bme.mit.theta.xcfa.model.XcfaEdge
import hu.bme.mit.theta.xcfa.model.XcfaLabel
import hu.bme.mit.theta.xcfa.model.XcfaLocation
import java.util.*

class XcfaAction (val pid: Int, edge: XcfaEdge) : StmtAction() {
    val source: XcfaLocation = edge.source
    val target: XcfaLocation = edge.target
    val label: XcfaLabel = edge.label

    constructor(pid: Int, source: XcfaLocation, target: XcfaLocation, label: XcfaLabel = NopLabel) : this(pid, XcfaEdge(source, target, label))

    override fun getStmts(): List<Stmt> {
        return listOf(label.toStmt())
    }

    override fun toString(): String {
        return "XcfaAction(pid=$pid, source=$source, target=$target, label=$label)"
    }


}