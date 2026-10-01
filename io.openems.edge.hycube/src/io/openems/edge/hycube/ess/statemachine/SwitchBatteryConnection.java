package io.openems.edge.hycube.ess.statemachine;

import java.time.Duration;
import java.time.Instant;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.ess.HycubeEss;
import io.openems.edge.hycube.ess.HycubeEssImpl;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

/**
 * Handles the GO_RUNNING state - transition from stopped/undefined to running.
 *
 * <p>
 */
public class SwitchBatteryConnection extends StateHandler<State, Context> {


	private Instant entryAt = Instant.MIN;
	
	private static final int WAIT_TIME = 10;
	
	private enum Steps
	{
		SOFTSTART_ON,
		WAIT,
		BATTERY_ON,
		SOFTSTART_OFF
	}
	
	private Steps step = Steps.SOFTSTART_ON;
	
	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		this.entryAt = Instant.now();
		step = Steps.SOFTSTART_ON;
	}


	@Override
	protected String debugLog() {
		return State.SWITCH_ON.toString();
	}

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final HycubeEssImpl ess = context.getParent();

		// Check for faults before proceeding
		if (ess.hasFaults() || ess.getBattery().hasFaults() ) {
			return State.ERROR;
		}
		
		if (Duration.between(this.entryAt, Instant.now()).getSeconds() < WAIT_TIME) {
			// Try again
			return State.SWITCH_ON;
		}

		switch( step )
		{
		case SOFTSTART_ON:
			ess.setSoftStart( true );
			step =Steps.WAIT; 
			break;
		case WAIT:
			step = Steps.BATTERY_ON;
			break;
		case BATTERY_ON:
			ess.connectBattery();
			step = Steps.SOFTSTART_OFF;
			break;
		case SOFTSTART_OFF:
			ess.setSoftStart(false);
			return State.INIT_WRITE_REGISTERS;
		}
		
		return State.SWITCH_ON;
	}
}
