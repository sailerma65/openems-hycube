package io.openems.edge.battery.pylontech.us2000C.statemachine;

import java.time.Duration;
import java.time.Instant;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine.State;
import io.openems.edge.common.statemachine.StateHandler;

public class StartCommHandler extends StateHandler<State, Context> {
	private Instant entryAt = Instant.MIN;
	private static final int WAIT_SECONDS = 10;

	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		this.entryAt = Instant.now();
	}

	@Override
	public State runAndGetNextState(Context context) {

		var battery = context.getParent();

		return switch (battery.getStartStopTarget()) {
		case UNDEFINED -> State.UNDEFINED; // Stuck in undefined state
		case START -> {
			if( !battery.checkCommunication() && Duration.between(this.entryAt, Instant.now()).getSeconds() > WAIT_SECONDS) {
				yield State.UNDEFINED;
			}

			if (battery.hasFaults()) {
				yield State.ERROR; // Faults exist - handle errors
			} else if( battery.checkCommunication() ){
				yield State.GO_RUNNING; // No faults, start the battery
			}
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