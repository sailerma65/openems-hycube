package io.openems.edge.hycube.ess.statemachine;

import java.time.Duration;
import java.time.Instant;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
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
 */
public class ReWriteRegisters extends StateHandler<State, Context> {


	private Instant entryAt = Instant.MIN;
	
	private int index = 0;
	
	private boolean nextModbusProtocol = false;
	
	private boolean dspVersionChecked = false;
	
	private static final int WAIT_TIME = 10;
	
	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		this.entryAt = Instant.now();
		index = 0;
		nextModbusProtocol = false;
		dspVersionChecked = false;
	}


	@Override
	protected String debugLog() {
		return State.REWRITE_REGISTERS.toString();
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
			return State.REWRITE_REGISTERS;
		}
		
		if( !nextModbusProtocol )
		{
			nextModbusProtocol = true;
			ess.getModbusProtocol().addTask( ess.getReadDspVersionTask() );
		}

		InitValidation[] list = ess.getCompleteInitChannelList();
		
		if( index >= list.length )
		{
			return State.GO_RUNNING;
		}
		
		InitValidation validation = list[ index ];

		index = index + 1;
		
		IntegerWriteChannel validationChannel = ess.channel( validation.getChannelId() );
		
		validationChannel.setNextWriteValue( validation.getInitialValue() );
		
		LongReadChannel channel = ess.channel( HycubeEss.ChannelId.DSP_VERSION );
		
		ess.doLogDebug( "Setting write channel " + validation.getChannelId() + ": " + validation.getInitialValue() );

		if( channel.value().get() != null && !dspVersionChecked )
		{
			dspVersionChecked = true;
			
			Long dspVersion = channel.value().get();
			
			if( dspVersion == null || ( dspVersion & 0xFFFF0000 ) != 0x007A0000 ) 
			{
				ess.doLog( "Invalid DSP VERSION: %08X".formatted( dspVersion ) );
				return State.ERROR;
			}
			else
			{
				ess.doLogDebug( "DSP version is %08X".formatted(dspVersion)  );
			}
		}		

		return State.REWRITE_REGISTERS;
	}
}
