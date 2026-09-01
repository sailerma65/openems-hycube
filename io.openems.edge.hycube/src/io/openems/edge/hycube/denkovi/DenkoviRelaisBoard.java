package io.openems.edge.hycube.denkovi;

import io.openems.common.channel.Level;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.io.api.DigitalOutput;

/**
 * DenkoviRelaisBoard proxy: DenkoviRelaisBoardImpl is a proxy that sends relay switch commands via
 * http to an application controlling the Denkovi USB interface.
 * The http protocol is compatible to Shelly devices. 
 */
public interface DenkoviRelaisBoard extends DigitalOutput, OpenemsComponent {
	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		SLAVE_COMMUNICATION_FAILED(Doc.of(Level.FAULT)); //

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
		
		/**
		 * Slave Communication Failed Fault.
		 *
		 * <ul>
		 * <li>Interface: Shelly25
		 * <li>Type: State
		 * </ul>
		 */
	}
}
