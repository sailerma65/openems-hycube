package io.openems.edge.hycube.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;
import io.openems.edge.hycube.ess.HycubeEssImpl;

/**
 * Handles the RUNNING state - active power control operation.
 *
 * <p>
 * Note: Actual power setpoints are written by {@link HycubeEssImpl#applyPower}
 * which calls {@code batteryInverter.run()}. This handler only manages the
 * state machine transitions and ensures the inverter is ready for operation.
 */
public class RunningHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		// Check for faults - transition to UNDEFINED if problems detected
		if (ess.hasFaults()) {
			return State.UNDEFINED;
		}

		// Mark as started
		ess._setStartStop(StartStop.START);

		ess.getBattery().start();
		
		return State.RUNNING;
	}
}
