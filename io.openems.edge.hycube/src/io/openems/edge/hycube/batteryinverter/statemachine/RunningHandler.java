package io.openems.edge.hycube.batteryinverter.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.batteryinverter.statemachine.StateMachine.State;
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
		final var inverter = context.getParent();

		// Check for faults - transition to UNDEFINED if problems detected
		if (inverter.hasFaults()) {
			return State.UNDEFINED;
		}

		// Mark as started
		inverter._setStartStop(StartStop.START);

		// Enable soft start for smooth power ramp-up
		inverter.softStart(true);

		inverter._setBatteryPowerTargetValue(context.setActivePower);
		
		return State.RUNNING;
	}
}
