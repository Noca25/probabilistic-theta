package hu.bme.mit.theta.analysis.expl

import hu.bme.mit.theta.analysis.expl.MultiValuationStmtApplier.ApplyResult
import hu.bme.mit.theta.core.decl.Decls
import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.model.ImmutableValuation
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.core.stmt.IfStmt
import hu.bme.mit.theta.core.stmt.Stmt
import hu.bme.mit.theta.core.stmt.Stmts
import hu.bme.mit.theta.core.stmt.Stmts.*
import hu.bme.mit.theta.core.type.LitExpr
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.inttype.IntExprs
import hu.bme.mit.theta.core.type.inttype.IntExprs.*
import org.junit.Assert
import org.junit.Assert.*
import org.junit.Test

class MultiValuationStmtApplierTest {

    val A = Decls.Var("A", Int())
    val B = Decls.Var("B", Int())
    val C = Decls.Var("C", Int())
    val P = Decls.Var("P", Bool())
    val Q = Decls.Var("Q", Bool())

    private fun valuation(a: Int, b: Int, c: Int, p: Boolean, q: Boolean) =
        ImmutableValuation.builder()
            .put(A, Int(a))
            .put(B, Int(b))
            .put(C, Int(c))
            .put(P, Bool(p))
            .put(Q, Bool(q)).build()

    private fun valuation(vararg vals: Pair<VarDecl<*>, LitExpr<*>>): ImmutableValuation {
        val builder = ImmutableValuation.builder()
        for ((decl, v) in vals) {
            builder.put(decl, v)
        }
        return builder.build()
    }

    private fun check(
        vs: List<Valuation>,
        stmt: Stmt,
        expectedResult: ApplyResult
    ) {
        val applier = MultiValuationStmtApplier()
        val res = applier.apply(stmt, vs, false)
        assertEquals(expectedResult, res)
    }

    @Test
    fun testAssign() {
        check(
            listOf(
                valuation(1, 2, 3, true, false),
                valuation(1, 1, 1, true, true),
                valuation(2, 3, 4, false, false)
            ),
            Assign(A, Int(2)),
            ApplyResult(
                ApplyResult.Status.SUCCESS, listOf(
                    valuation(2, 2, 3, true, false),
                    valuation(2, 1, 1, true, true),
                    valuation(2, 3, 4, false, false)
                )
            )
        )
    }

    @Test
    fun testAssume() {
        check(
            listOf(
                valuation(1, 2, 3, true, false),
                valuation(1, 1, 1, true, true),
                valuation(2, 3, 4, false, false)
            ),
            Stmts.Assume(IntExprs.Eq(A.ref, Int(2))),
            ApplyResult(
                ApplyResult.Status.SUCCESS, listOf(
                    valuation(2, 3, 4, false, false)
                )
            )
        )
    }

    @Test
    fun testHavoc() {
        check(
            listOf(
                valuation(1, 2, 3, true, false),
                valuation(1, 1, 1, true, true),
                valuation(2, 3, 4, false, false)
            ),
            Havoc(A),
            ApplyResult(
                ApplyResult.Status.SUCCESS, listOf(
                    valuation(B to Int(2), C to Int(3), P to True(), Q to False()),
                    valuation(B to Int(1), C to Int(1), P to True(), Q to True()),
                    valuation(B to Int(3), C to Int(4), P to False(), Q to False())
                )
            )
        )
    }

    @Test
    fun testSeqAtomics() {
        check(
            listOf(
                valuation(1, 2, 3, true, false),
                valuation(1, 1, 1, true, true),
                valuation(2, 3, 4, false, false)
            ),
            SequenceStmt(listOf(
                Assign(A, Int(2)),
                Assign(B, A.ref),
                Assign(Q, True())
            )),
            ApplyResult(
                ApplyResult.Status.SUCCESS, listOf(
                    valuation(2, 2, 3, true, true),
                    valuation(2, 2, 1, true, true),
                    valuation(2, 2, 4, false, true)
                )
            )
        )
    }

    @Test
    fun testSeqNested() {
        check(
            listOf(
                valuation(1, 2, 3, true, false),
                valuation(1, 1, 1, true, true),
                valuation(2, 3, 4, false, false)
            ),
            SequenceStmt(listOf(
                SequenceStmt(listOf(Assign(A, Int(2)), Assign(B, A.ref))),
                SequenceStmt(listOf(Assign(Q, True()), Assume(And(Q.ref, P.ref)))),
                Assume(Eq(A.ref, Int(2)))
            )),
            ApplyResult(
                ApplyResult.Status.SUCCESS, listOf(
                    valuation(2, 2, 3, true, true),
                    valuation(2, 2, 1, true, true),
                )
            )
        )
    }

    @Test
    fun testNonDetAtomics() {
        check(
            listOf(
                valuation(1, 2, 3, true, false),
                valuation(1, 1, 1, true, true),
                valuation(2, 3, 4, false, false)
            ),
            NonDetStmt(listOf(
                Assign(A, Int(2)),
                Assign(B, A.ref),
                Assign(Q, True())
            )),
            ApplyResult(
                ApplyResult.Status.SUCCESS, listOf(
                    //orig: valuation(1, 2, 3, true, false),
                    valuation(2, 2, 3, true, false),
                    valuation(1, 1, 3, true, false),
                    valuation(1, 2, 3, true, true),
                    //orig: valuation(1, 1, 1, true, true),
                    valuation(2, 1, 1, true, true),
                    valuation(1, 1, 1, true, true),
                    valuation(1, 1, 1, true, true), //TODO: uniqueness
                    //orig: valuation(2, 3, 4, false, false),
                    valuation(2, 3, 4, false, false),
                    valuation(2, 2, 4, false, false), //TODO: uniqueness
                    valuation(2, 3, 4, false, true)
                )
            )
        )
    }

    @Test
    fun testNonDetNested() {
        check(
            listOf(
                valuation(1, 2, 3, true, false),
                valuation(1, 1, 1, true, true),
                valuation(2, 3, 4, false, false)
            ),
            NonDetStmt(listOf(
                NonDetStmt(listOf(Assign(A, Int(2)), Assign(B, A.ref))),
                Assign(Q, True())
            )),
            ApplyResult(
                ApplyResult.Status.SUCCESS, listOf(
                    //orig: valuation(1, 2, 3, true, false),
                    valuation(2, 2, 3, true, false),
                    valuation(1, 1, 3, true, false),
                    valuation(1, 2, 3, true, true),
                    //orig: valuation(1, 1, 1, true, true),
                    valuation(2, 1, 1, true, true),
                    valuation(1, 1, 1, true, true),
                    valuation(1, 1, 1, true, true), //TODO: uniqueness
                    //orig: valuation(2, 3, 4, false, false),
                    valuation(2, 3, 4, false, false),
                    valuation(2, 2, 4, false, false), //TODO: uniqueness
                    valuation(2, 3, 4, false, true)
                )
            )
        )
    }

    @Test
    fun testIf() {
        check(
            listOf(
                valuation(1, 2, 3, true, false),
                valuation(1, 1, 1, true, true),
                valuation(2, 3, 4, false, false)
            ),
            IfStmt.of(P.ref, Assign(A, Int(3)), Assign(B, Int(4))),
            ApplyResult(
                ApplyResult.Status.SUCCESS, listOf(
                    valuation(3, 2, 3, true, false),
                    valuation(3, 1, 1, true, true),
                    valuation(2, 4, 4, false, false)
                )
            )
        )

    }

    @Test
    fun complexStatementTest() {

        val branch1 = SequenceStmt(listOf(
            Assign(A, Int(0)),
            NonDetStmt(listOf(Assign(B, Int(3)), Assign(B, Int(4)))),
            IfStmt.of(P.ref, Assign(C, B.ref), Assign(A, B.ref))
        ))
        val branch2 = SequenceStmt(listOf(
            Assume(Q.ref),
            NonDetStmt(listOf(
                SequenceStmt(listOf(
                    Assign(P, False()),
                    Assign(B, Int(6))
                )),
                Assign(B, Int(5))
            )),
            Assume(P.ref) //This makes the first option of the inner NonDet always fail
        ))

        check(
            listOf(
                valuation(1, 2, 3, true, false),
                valuation(1, 1, 1, true, true),
                valuation(2, 3, 4, false, false)
            ),
            NonDetStmt(listOf(branch1, branch2)),
            ApplyResult(
                ApplyResult.Status.SUCCESS, listOf(
                    //orig: valuation(1, 2, 3, true, false)
                    //branch 1
                    valuation(0, 3, 3, true, false),
                    valuation(0, 4, 4, true, false),
                    //branch 2 fails

                    //orig: valuation(1, 1, 1, true, true)
                    //branch 1
                    valuation(0, 3, 3, true, true), //TODO: uniqueness
                    valuation(0, 4, 4, true, true), //TODO: uniqueness
                    //branch 2
                    valuation(1, 5, 1, true, true),

                    //orig: valuation(2, 3, 4, false, false)
                    //branch 1
                    valuation(3, 3, 4, false, false),
                    valuation(4, 4, 4, false, false)
                    //branch 2 fails
                )
            )
        )

    }
}