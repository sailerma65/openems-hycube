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
public class InitBatteryHandler extends StateHandler<State, Context> {
	private Instant entryAt = Instant.MIN;
	
	private static final int WAIT_SECONDS = 20;
	
	private boolean batteryStarted = false;
	
	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		this.entryAt = Instant.now();
		batteryStarted = false;
	}

	@Override
	protected String debugLog() {
		return State.INIT_BATTERY.toString();
	}

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final HycubeEssImpl ess = context.getParent();

		// Check for faults before proceeding
		if (ess.hasFaults() || ess.getModbusCommunicationFailed() ) {
			return State.ERROR;
		}

		if( !batteryStarted )
		{
			ess.getBattery().start();
			batteryStarted = true;
			return State.INIT_BATTERY;
		}
		
		if (Duration.between(this.entryAt, Instant.now()).getSeconds() > WAIT_SECONDS) {
			if( ess.getBattery().isStarted() && !ess.getBattery().hasFaults() )
			{
				ess.connectBattery();
				
				return State.CHECKING;
			}
		}
		
		return State.INIT_BATTERY;
	}
}
