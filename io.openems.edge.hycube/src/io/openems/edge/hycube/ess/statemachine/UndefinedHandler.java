package io.openems.edge.hycube.ess.statemachine;

import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

public class UndefinedHandler extends StateHandler<State, Context> {

	
	@Override
	protected String debugLog() {
		return State.UNDEFINED.toString();
	}

	@Override
	public State runAndGetNextState(Context context) {
		final var ess = context.getParent();
		return switch (ess.getStartStopTarget()) {
		case UNDEFINED ->
			// Stuck in UNDEFINED State
			State.UNDEFINED;

		case START -> {
			if( !ess.getModbusCommunicationFailed() )
			{
				if( ess.setRemoteControl( false ) )
					yield State.INIT_BATTERY;
			}
			yield State.UNDEFINED;
		}
		case STOP ->
			// force STOP
			State.GO_STOPPED;
		};
	}
}
