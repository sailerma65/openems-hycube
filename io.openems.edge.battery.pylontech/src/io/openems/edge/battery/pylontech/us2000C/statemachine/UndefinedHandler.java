package io.openems.edge.battery.pylontech.us2000C.statemachine;

import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine.State;
import io.openems.edge.common.statemachine.StateHandler;

public class UndefinedHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) {

		var battery = context.getParent();

		return switch (battery.getStartStopTarget()) {
		case UNDEFINED -> State.UNDEFINED; // Stuck in undefined state
		case START -> {
			if (battery.hasFaults()) {
				yield State.ERROR; // Faults exist - handle errors
			} else {
				yield State.RUNNING; // No faults, start the battery
			}
		}
		case STOP -> State.STOPPED; // Target state is stop -> stop it
		default -> {
			assert false : "Unexpected StartStopTarget state"; // Should never happen
			yield State.UNDEFINED; // Fallback
		}
		};
	}

}