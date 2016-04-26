package hu.bme.mit.inf.ttmc.analysis.impl;

import static com.google.common.base.Preconditions.checkNotNull;

import hu.bme.mit.inf.ttmc.analysis.MergeOperator;
import hu.bme.mit.inf.ttmc.analysis.State;

public class SepMergeOperator<S extends State> implements MergeOperator<S> {

	@Override
	public S merge(final S state1, final S state2) {
		checkNotNull(state1);
		checkNotNull(state2);
		return state2;
	}

}
