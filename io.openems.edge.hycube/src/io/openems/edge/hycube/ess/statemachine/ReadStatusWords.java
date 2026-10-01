package io.openems.edge.hycube.ess.statemachine;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.hycube.ess.HycubeEss;
import io.openems.edge.hycube.ess.HycubeEssImpl;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

/**
 * Handles the GO_RUNNING state - transition from stopped/undefined to running.
 *
 * <p>
 */
public class ReadStatusWords extends StateHandler<State, Context> {


	private Instant entryAt = Instant.MIN;
	
	private boolean statusWordsEnabled = false;
	
	private boolean statusWordReceived = false;
	
	private static final int TIMEOUT = 20;
	
	private Consumer<Value<Integer>> statusWordCallback = this::onStatusWord;
	
	private IntegerReadChannel statusWordChannel;
	
	private HycubeEssImpl ess;
	
	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		this.entryAt = Instant.now();
		statusWordsEnabled = false;
		statusWordReceived = false;
	}


	@Override
	protected String debugLog() {
		return State.READ_STATUS_WORDS.toString();
	}

	private void onStatusWord( Value<Integer> val )
	{
		ess.doLogDebug( "Received status word 0x40B" );
		ess.getModbusProtocol().removeTask( ess.getReadStatusWordsTask() );
		
		statusWordReceived = true;
		
		statusWordChannel.removeOnSetNextValueCallback(statusWordCallback);
	}
	
	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		ess = context.getParent();

		// Check for faults before proceeding
		if (ess.hasFaults() || ess.getBattery().hasFaults() ) {
			return State.ERROR;
		}

		if( !statusWordsEnabled )
		{
			statusWordChannel =  ess.channel( HycubeEss.ChannelId.STATUS_WORD_404B );
			
			statusWordChannel.onSetNextValue( statusWordCallback );
			
			ess.getModbusProtocol().addTask( ess.getReadStatusWordsTask() );
			
			statusWordsEnabled = true;
		}

		if( statusWordReceived )
		{
			ess.doLog( "Status words received");
			return State.BEFORE_SWITCH_ON;
		}

		if (Duration.between(this.entryAt, Instant.now()).getSeconds() > TIMEOUT ) {

			ess.doLog( "Status words not received -> go to state ERROR");
			return State.ERROR;
		}
		return State.READ_STATUS_WORDS;
	}
}

