package io.openems.edge.battery.pylontech.us2000C.statemachine;

import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine.State;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.statemachine.StateHandler;

public class GoRunningHandler extends StateHandler<State, Context> {
	@Override
	protected String debugLog() {
		return State.GO_RUNNING.toString();
	}


	@Override
	public State runAndGetNextState(Context context) {
		var battery = context.getParent();

		// Mark as started
		battery._setStartStop(StartStop.START);

		if (battery.hasFaults() || !battery.checkCommunication() ) {
			return State.UNDEFINED;
		}

		if (!context.isBatteryAwake()) {
			return State.UNDEFINED;
		}

		return State.RUNNING;
	}
}