package io.openems.edge.hycube.pvinverter;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.SinglePhaseMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Victron Battery Inverter interface for Venus OS / Cerbo GX systems.
 *
 * <p>
 * This interface defines all channels for the Victron Battery Inverter
 * connected via Modbus to Venus OS / Cerbo GX. It provides system-level
 * monitoring and control of the Victron energy system including AC/DC power
 * flows, battery status, and ESS control parameters.
 *
 * @see <a href="https://github.com/victronenergy/dbus_modbustcp">Venus
 *      Modbus-TCP</a>
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
