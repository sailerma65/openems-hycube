package io.openems.edge.battery.pylontech.us2000C.com;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStoppable;

public interface SerialReadWrite extends OpenemsComponent, StartStoppable{
	public static enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		;

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
	
	// check if a line can be read using readline() without blocking
	public boolean isReadLineAvailable();
	
	// reads bytes from serial interface. Lines can be terminated by \r or \n. 
	// Termination character returned as last byte in resulting array.
	// if no complete line is available, method returns null
	public byte[] readLine( int maxTimeMS );
	
	// write a byte array to serial interface. 
	public boolean writeLine( byte[] line );

}
