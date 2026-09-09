package io.openems.edge.battery.pylontech.us2000C.statemachine;

import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine.State;
import io.openems.edge.common.statemachine.StateHandler;

public class UndefinedHandler extends StateHandler<State, Context> {

	@Override
	protected String debugLog() {
		return State.UNDEFINED.toString();
	}

	@Override
	public State runAndGetNextState(Context context) {

		var battery = context.getParent();

		return switch (battery.getStartStopTarget()) {
		case UNDEFINED -> State.UNDEFINED; // Stuck in undefined state
		case START -> {
			battery.startCommunication();
			yield State.INIT_COMM;
		}
		case STOP -> State.STOPPED; // Target state is stop -> stop it
		default -> {
			assert false : "Unexpected StartStopTarget state"; // Should never happen
			yield State.UNDEFINED; // Fallback
		}
		};
	}

}