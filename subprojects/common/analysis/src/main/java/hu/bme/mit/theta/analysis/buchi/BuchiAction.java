package hu.bme.mit.theta.analysis.buchi;

import hu.bme.mit.theta.analysis.expr.StmtAction;
import hu.bme.mit.theta.analysis.pred.PredState;
import hu.bme.mit.theta.common.Utils;
import hu.bme.mit.theta.core.stmt.AssumeStmt;
import hu.bme.mit.theta.core.stmt.Stmt;
import hu.bme.mit.theta.core.stmt.Stmts;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.type.inttype.IntExprs;

import java.util.ArrayList;
import java.util.List;

import static hu.bme.mit.theta.core.stmt.Stmts.*;
import static hu.bme.mit.theta.core.type.inttype.IntExprs.*;

public class BuchiAction extends StmtAction {
    private final Expr<BoolType> cond;
    private final BuchiState target;

    public BuchiAction(Expr<BoolType> cond, BuchiState target) {
        this.cond = cond;
        this.target = target;
    }

    public Expr<BoolType> getCond() {
        return cond;
    }

    public BuchiState getTarget() {
        return target;
    }

    @Override
    public List<Stmt> getStmts() {
        return List.of(SequenceStmt(List.of(
                Assume(cond),
                Assign(target.getAutomaton().getStateVar(), Int(target.getId()))
        )));
    }

    public String toString(){
        return Utils.lispStringBuilder(getClass().getSimpleName()).body().add(AssumeStmt.of(cond)).toString();
    }
}
