package io.openems.edge.hycube.ess.statemachine;

import java.time.Duration;
import java.time.Instant;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.ess.HycubeEss;
import io.openems.edge.hycube.ess.HycubeEssImpl;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

/**
 * Handles the GO_RUNNING state - transition from stopped/undefined to running.
 *
 * <p>
 * waits until all needed modbus register values are available,
 * checks version information and checks if presets are correctly set.
 * If yes -> GO_RUNNING
 * If no -> go to state INITIALIZING -> set preset values
 */

public class CheckingHandler extends StateHandler<State, Context> {

	private Instant entryAt = Instant.MIN;
	
	private static final int WAIT_AFTER_CONNECT_BATTERY = 20;
	
	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		this.entryAt = Instant.now();

	}

	@Override
	protected String debugLog() {
		return State.CHECKING.toString();
	}

	private boolean checkValidReadChannels( HycubeEssImpl ess )
	{
		InitValidation[] channelList = ess.getInitChannelList();
		
		boolean valid[] = new boolean[ channelList.length ];
		
		int i = 0;
		boolean result = true;
		
		for( InitValidation validation : channelList )
		{
			if( ess.channel( validation.getChannelId() ).value().asOptional().isEmpty() )
			{
				result = false;
			}
			else
			{
				valid[ i ] = true;
			}
			i++;
		}
		return result;
	}
	
	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final HycubeEssImpl ess = context.getParent();

		if( ess.getModbusCommunicationFailed() )
		{
			return State.ERROR;
		}
		// Check for faults before proceeding
		if (ess.hasFaults()) {
			return State.ERROR;
		}
		
		if (Duration.between(this.entryAt, Instant.now()).getSeconds() > WAIT_AFTER_CONNECT_BATTERY )
		{
			return State.RUNNING;
		}

		if( checkValidReadChannels(ess))
		{
			// all channel values are valid:
			
			LongReadChannel channel = ess.channel( HycubeEss.ChannelId.DSP_VERSION );
			
			Long dspVersion = channel.value().get();
			
			if( dspVersion == null || ( dspVersion & 0xFFFF0000 ) != 0x007A0000 ) 
			{
				ess.doLog( "Invalid DSP VERSION: %08X".formatted( dspVersion ) );
				return State.ERROR;
			}
			
			ess.resetInitChannelList();
			
			for( InitValidation validation : ess.getInitChannelList() )
			{
				if( validation.isToBeChecked() )
				{
					IntegerReadChannel readChannel = ess.channel( validation.getChannelId() );
					
					if( !validation.check( readChannel.value().get() ) )
					{
						break;
					}
				}
			}
			
			return State.INITIALIZING;
		}
		return State.CHECKING;
	}
}
