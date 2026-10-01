package io.openems.edge.hycube.ess.statemachine;

import io.openems.edge.common.channel.ChannelId;

public class InitValidation {
	private ChannelId channelId;
	private Integer initialValue;
	
	public InitValidation( ChannelId i_channel, int i_initialValue )
	{
		channelId = i_channel;
		initialValue = i_initialValue;
	}
	
	public ChannelId getChannelId()
	{
		return channelId;
	}
	
	public int getInitialValue()
	{
		return initialValue;
	}

}
