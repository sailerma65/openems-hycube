package io.openems.edge.hycube.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter.TargetGridMode;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.hycube.ess.HycubeEss;
import io.openems.edge.hycube.ess.HycubeEssImpl;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

/**
 * Handles the GO_RUNNING state - transition from stopped/undefined to running.
 *
 * <p>
 * Victron inverters are typically "always running" when connected via Modbus,
 * so this handler primarily sets the grid mode and transitions to RUNNING.
 */
public class InitializingHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final HycubeEssImpl ess = context.getParent();

		// Check for faults before proceeding
		if (ess.hasFaults()) {
			return State.ERROR;
		}

		for( InitValidation validation : ess.getInitChannelList() )
		{
			if( !validation.isValidated() )
			{
				IntegerWriteChannel remoteControlChannel = ess.channel( validation.getChannelId() );
				
				remoteControlChannel.setNextWriteValue( validation.getCheckValue() );

				validation.validate();
		
				// set one value in each cycle (approximately one per second)
				// The reason is: It is not clear why HYCUBE software also sets the iniatial values quite slowly.
				// Maybe it is intended because of eeprom write operations.
				return State.INITIALIZING;
			}
		}
		
		// all values are initialized:
		
		IntegerWriteChannel remoteControlChannel = ess.channel( HycubeEss.ChannelId.INIT_REMOTE_CONTROL );

		// set Remote Control ON
		remoteControlChannel.setNextValue( 0xFF00 );
		
		return State.GO_RUNNING;
	}
}
