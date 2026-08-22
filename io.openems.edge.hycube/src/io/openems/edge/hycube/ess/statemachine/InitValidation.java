package io.openems.edge.hycube.ess.statemachine;

import io.openems.edge.common.channel.ChannelId;

public class InitValidation {
	private ChannelId channelId;
	private boolean validated;
	private Integer checkValue;
	private Integer checkMin;
	private Integer checkMax;
	
	public InitValidation( ChannelId i_channel, int i_checkValue )
	{
		channelId = i_channel;
		checkValue = i_checkValue;
		validated = false;
	}
	
	public InitValidation( ChannelId i_channel, int i_min, int i_max, int i_checkValue )
	{
		channelId = i_channel;
		checkValue = i_checkValue;
		checkMin = i_min;
		checkMax = i_max;
		validated = false;
	}
	
	public InitValidation( ChannelId i_channel )
	{
		channelId = i_channel;
		validated = false;
	}

	public boolean isValidated()
	{
		return validated || checkValue == null;
	}
	
	public boolean check( int i_value )
	{
		if( isToBeChecked() && !validated )
		{
			boolean result;
			
			if( checkMax == null )
			{
				result = i_value == checkValue;
			}
			else
			{
				result = ( checkMax >= i_value && i_value >= checkMin );
			}
			
			validated = result;
			return result;
		}
		else
		{
			return true;
		}
	}
	
	public void reset()
	{
		validated = false;
	}
	
	public void validate()
	{
		validated = true;
	}
	
	public boolean isToBeChecked()
	{
		return checkValue != null;
	}
	
	public ChannelId getChannelId()
	{
		return channelId;
	}
	
	public int getCheckValue()
	{
		return checkValue;
	}

}
