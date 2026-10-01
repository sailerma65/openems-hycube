package io.openems.edge.hycube.ess.statemachine;

import java.time.Duration;
import java.time.Instant;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.ess.HycubeEssImpl;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

/**
 * Handles the GO_RUNNING state - transition from stopped/undefined to running.
 *
 * <p>
 */
public class GoRunningHandler extends StateHandler<State, Context> {
	@Override
	protected String debugLog() {
		return State.GO_RUNNING.toString();
	}

	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
	}


	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final HycubeEssImpl ess = context.getParent();

		// Check for faults before proceeding
		if (ess.hasFaults() || ess.getBattery().hasFaults() ) {
			return State.ERROR;
		}

			// end of initialization:switches on the CBi RAU (Remote actuator unit with lockout)
			// - use runtime modbus register list
			// - 
		ess.doLogDebug( "Setting runtime modbus protocol and finish startup");

		ess.initializationDone();

		return State.RUNNING;
	}
}
