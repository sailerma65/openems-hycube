package io.openems.edge.hycube.ess.statemachine;

import java.time.Duration;
import java.time.Instant;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.ess.HycubeEssImpl;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

/**
 * Handles the GO_RUNNING state - transition from stopped/undefined to running.
 *
 * <p>
 */
public class InitBeforeSwitchOn extends StateHandler<State, Context> {


	private Instant entryAt = Instant.MIN;
	
	private int index = 0;
	
	private boolean switchedOff = false;
	
	private static final int WAIT_TIME = 10;
	
	private HycubeEssImpl ess;
	
	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		this.entryAt = Instant.now();
		index = 0;
		switchedOff = false;
	}


	@Override
	protected String debugLog() {
		return State.BEFORE_SWITCH_ON.toString();
	}

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		ess = context.getParent();

		// Check for faults before proceeding
		if (ess.hasFaults() || ess.getBattery().hasFaults() ) {
			return State.ERROR;
		}

		if( !switchedOff )
		{
			ess.doLogDebug( "Switching Remote Control OFF" );
			ess.setRemoteControl(false);
			
			switchedOff = true;
			
		}
		if (Duration.between(this.entryAt, Instant.now()).getSeconds() < WAIT_TIME) {
				// Try again
			return State.BEFORE_SWITCH_ON;
		}
		
		InitValidation[] list = ess.getInitChannelListBeforeSwitch();
		
		if( index >= list.length )
		{
			return State.SWITCH_ON;
		}
		
		InitValidation validation = list[ index ];

		index = index + 1;
		
		IntegerWriteChannel remoteControlChannel = ess.channel( validation.getChannelId() );
		
		remoteControlChannel.setNextWriteValue( validation.getInitialValue() );
		
		ess.doLogDebug( "Setting write channel " + validation.getChannelId() + ": " + validation.getInitialValue() );

		return State.BEFORE_SWITCH_ON;
	}
}
