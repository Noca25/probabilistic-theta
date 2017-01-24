package hu.bme.mit.theta.analysis.tcfa.lawi;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;

import java.util.Collection;

import hu.bme.mit.theta.analysis.TransferFunction;
import hu.bme.mit.theta.analysis.expl.ExplState;
import hu.bme.mit.theta.analysis.impl.NullPrecision;
import hu.bme.mit.theta.analysis.loc.LocState;
import hu.bme.mit.theta.analysis.prod.Prod2State;
import hu.bme.mit.theta.analysis.tcfa.TcfaAction;
import hu.bme.mit.theta.analysis.tcfa.zone.itp.ItpZoneState;
import hu.bme.mit.theta.formalism.tcfa.TcfaEdge;
import hu.bme.mit.theta.formalism.tcfa.TcfaLoc;

final class TcfaLawiTransferFunction implements TransferFunction<TcfaLawiState, TcfaAction, NullPrecision> {

	private final TransferFunction<LocState<Prod2State<ExplState, ItpZoneState>, TcfaLoc, TcfaEdge>, TcfaAction, NullPrecision> transferFunction;

	private TcfaLawiTransferFunction(
			final TransferFunction<LocState<Prod2State<ExplState, ItpZoneState>, TcfaLoc, TcfaEdge>, TcfaAction, NullPrecision> transferFunction) {
		this.transferFunction = checkNotNull(transferFunction);
	}

	public static TcfaLawiTransferFunction create(
			final TransferFunction<LocState<Prod2State<ExplState, ItpZoneState>, TcfaLoc, TcfaEdge>, TcfaAction, NullPrecision> transferFunction) {
		return new TcfaLawiTransferFunction(transferFunction);
	}

	@Override
	public Collection<? extends TcfaLawiState> getSuccStates(final TcfaLawiState state, final TcfaAction action,
			final NullPrecision precision) {
		return transferFunction.getSuccStates(state.getState(), action, precision).stream().map(TcfaLawiState::create)
				.collect(toList());
	}

}