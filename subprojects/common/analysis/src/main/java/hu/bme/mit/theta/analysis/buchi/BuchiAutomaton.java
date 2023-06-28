package hu.bme.mit.theta.analysis.buchi;

import com.google.errorprone.annotations.Var;
import hu.bme.mit.theta.core.decl.Decls;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.model.Valuation;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolExprs;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.type.inttype.IntExprs;
import hu.bme.mit.theta.core.type.inttype.IntType;

import java.util.HashSet;

import static hu.bme.mit.theta.core.type.inttype.IntExprs.Int;

public class BuchiAutomaton {

    public BuchiState getInitial() {
        return initial;
    }

    private final VarDecl<IntType> stateVar = Decls.Var("__buchiState", Int());

    public VarDecl<IntType> getStateVar() {
        return stateVar;
    }

    public void setInitial(BuchiState initial) {
        this.initial = initial;
    }

    private BuchiState initial;

    public Expr<BoolType> getInitExpr() {
        return IntExprs.Eq(stateVar.getRef(), Int(initial.getId()));
    }

}
