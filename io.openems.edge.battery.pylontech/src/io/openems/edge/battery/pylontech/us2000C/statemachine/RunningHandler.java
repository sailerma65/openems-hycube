package io.openems.edge.battery.pylontech.us2000C.statemachine;

import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine.State;
import io.openems.edge.common.statemachine.StateHandler;

public class RunningHandler extends StateHandler<State, Context> {
	@Override
	protected String debugLog() {
		return State.RUNNING.toString();
	}

	@Override
	public State runAndGetNextState(Context context) {
		var battery = context.getParent();

		if( !battery.checkCommunication() )
		{
			return State.ERROR;
		}
		if (battery.hasFaults()  ) {
			return State.UNDEFINED;
		}

		if (!context.isBatteryAwake()) {
			return State.GO_RUNNING;
		}

		return State.RUNNING;
	}
}