package io.openems.edge.hycube.pvinverter;

import static io.openems.common.types.OpenemsType.INTEGER;

import io.openems.common.channel.Unit;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.hycube.ess.HycubeEss.ChannelId;
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

		// ================= AC PV on Output (Critical Loads) =================
		SOLAR1_VOLTAGE(Doc.of(INTEGER)
				.unit(Unit.DEZIVOLT).text("Solar1 voltage")),
	
		SOLAR1_CURRENT(Doc.of(INTEGER)
				.unit(Unit.DEZIAMPERE).text("Solar1 current")),

		SOLAR1_POWER(Doc.of(INTEGER)
				.unit(Unit.WATT).text("Solar1 power")),

		SOLAR2_VOLTAGE(Doc.of(INTEGER)
				.unit(Unit.DEZIVOLT).text("Solar2 voltage")),
	
		SOLAR2_CURRENT(Doc.of(INTEGER)
				.unit(Unit.DEZIAMPERE).text("Solar2 current")),

		SOLAR2_POWER(Doc.of(INTEGER)
				.unit(Unit.WATT).text("Solar2 power")),

		SOLAR_SUM_POWER(Doc.of(INTEGER)
				.unit(Unit.WATT).text("Total solar power")),

		GRID_PHASE_VOLTAGE( Doc.of(INTEGER)
				.unit(Unit.DEZIVOLT).text("Grid voltage")),
		
		GRID_FREQUENCY( Doc.of(INTEGER)
				.unit(Unit.MILLIHERTZ).text("Grid frequency")),

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
	public default IntegerReadChannel getSumSolarPowerChannel() {
		return this.channel(ChannelId.SOLAR_SUM_POWER );
	}
	/**
	 * Gets the Channel for {@link ChannelId#FREQUENCY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getGridFrequencyChannel() {
		return this.channel(ChannelId.GRID_FREQUENCY);
	}

	/**
	 * Gets the Channel for {@link ChannelId#GRID_PHASE_VOLTAGE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getGridVoltageChannel() {
		return this.channel(ChannelId.GRID_PHASE_VOLTAGE);
	}


}
