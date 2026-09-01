package io.openems.edge.hycube.pvinverter;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.SinglePhaseMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Handles the PV inverter part of Sermatec Hybrid inverter.
 *
 * <p>
 */
public interface HycubePvInverter extends ManagedSymmetricPvInverter,
		OpenemsComponent, SinglePhaseMeter {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
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

}
