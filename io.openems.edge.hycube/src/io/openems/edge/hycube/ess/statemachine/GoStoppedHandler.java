package io.openems.edge.hycube.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

/**
 * Handles the GO_STOPPED state - transition from running to stopped.
 */
public class GoStoppedHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		// Mark as stopped
		ess._setStartStop(StartStop.STOP);

		ess.getBattery().stop();
		
		return State.STOPPED;
	}

}
