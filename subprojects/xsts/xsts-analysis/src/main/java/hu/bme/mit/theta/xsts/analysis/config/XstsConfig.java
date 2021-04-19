package hu.bme.mit.theta.xsts.analysis.config;

import hu.bme.mit.theta.analysis.Action;
import hu.bme.mit.theta.analysis.Counterexample;
import hu.bme.mit.theta.analysis.Prec;
import hu.bme.mit.theta.analysis.State;
import hu.bme.mit.theta.analysis.algorithm.Abstraction;
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker;
import hu.bme.mit.theta.analysis.algorithm.SafetyResult;

public final class XstsConfig<
		S extends State, A extends Action, P extends Prec,
		AA extends Abstraction<S, A>, C extends Counterexample<S, A>
		> {

	private final SafetyChecker<S, A, P, AA, C> checker;
	private final P initPrec;

	private XstsConfig(final SafetyChecker<S, A, P, AA, C> checker, final P initPrec) {
		this.checker = checker;
		this.initPrec = initPrec;
	}

	public static <S extends State, A extends Action, P extends Prec,
			AA extends Abstraction<S,A>, C extends Counterexample<S,A>> XstsConfig<S, A, P, AA, C> create(
			final SafetyChecker<S, A, P, AA, C> checker, final P initPrec) {
		return new XstsConfig<>(checker, initPrec);
	}

	public SafetyResult<S, A, AA, C> check() {
		return checker.check(initPrec);
	}

}