package io.openems.edge.hycube.ess.statemachine;

import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

public class UndefinedHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) {
		final var ess = context.getParent();
		return switch (ess.getStartStopTarget()) {
		case UNDEFINED ->
			// Stuck in UNDEFINED State
			State.UNDEFINED;

		case START -> {
			// force START
			if( ess.needsInitialization() )
			{
				yield State.CHECKING;
			}
			if (ess.hasFaults()) {
				// Has Faults -> error handling
				yield State.ERROR;
			} else {
				// No Faults -> start
				yield State.GO_RUNNING;
			}
		}
		case STOP ->
			// force STOP
			State.GO_STOPPED;
		};
	}
}
