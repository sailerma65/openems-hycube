package io.openems.edge.battery.pylontech.us2000C.com;

import static io.openems.common.channel.AccessMode.READ_ONLY;
import static io.openems.common.types.OpenemsType.STRING;

import io.openems.common.channel.Level;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStoppable;

public interface SerialReadWrite extends OpenemsComponent, StartStoppable{
	public static enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		BAD_PARAMETERS(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Interface parameters wrong")),

		COMMUNICATION_FAILURE(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Communication failed")),

		FAILURE_STRING(Doc.of(STRING) //
				.accessMode(READ_ONLY) //
				.text("Last failure reason or \"ok\"")),
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

	public int bytesAvailable();

	public int readBytes( byte[] buffer, int length );

	public int writeBytes( byte[] buffer, int bytesToWrite  );
	
	// Timeout Modes
	static final public int TIMEOUT_NONBLOCKING = 0x00000000;
	static final public int TIMEOUT_READ_SEMI_BLOCKING = 0x00000001;
	static final public int TIMEOUT_READ_BLOCKING = 0x00000010;
	static final public int TIMEOUT_WRITE_BLOCKING = 0x00000100;

	public boolean setComPortTimeouts(int newTimeoutMode, int newReadTimeout, int newWriteTimeout);

	/** 
	 * report failure state
	 */
	public void handleError( String context, Exception exc );

}
