package io.openems.edge.hycube.ess;

import static io.openems.common.channel.AccessMode.READ_WRITE;
import static io.openems.common.channel.AccessMode.WRITE_ONLY;
import static io.openems.common.channel.Level.FAULT;
import static io.openems.common.channel.PersistencePriority.HIGH;
import static io.openems.common.channel.Unit.AMPERE;
import static io.openems.common.channel.Unit.CUMULATED_WATT_HOURS;
import static io.openems.common.channel.Unit.DEGREE_CELSIUS;
import static io.openems.common.channel.Unit.MILLIAMPERE;
import static io.openems.common.channel.Unit.MILLIHERTZ;
import static io.openems.common.channel.Unit.MILLIVOLT;
import static io.openems.common.channel.Unit.NONE;
import static io.openems.common.channel.Unit.VOLT;
import static io.openems.common.channel.Unit.VOLT_AMPERE;
import static io.openems.common.channel.Unit.VOLT_AMPERE_REACTIVE;
import static io.openems.common.channel.Unit.WATT;
import static io.openems.common.channel.Unit.WATT_HOURS;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.LONG;
import static io.openems.common.types.OpenemsType.SHORT;
import static io.openems.common.types.OpenemsType.STRING;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter.TargetGridMode;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.ShortWriteChannel;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.type.Phase.SinglePhase;
import io.openems.edge.hycube.batteryinverter.HycubeBatteryInverter;
import io.openems.edge.hycube.batteryinverter.HycubeBatteryInverter.ChannelId;
import io.openems.edge.hycube.batteryinverter.statemachine.StateMachine.State;
import io.openems.edge.hycube.enums.ActiveInactive;
import io.openems.edge.hycube.enums.EnableDisable;

/**
 * This interface defines all channels for the Victron Energy Storage System
 * connected via Modbus to GX. It supports both single-phase and three-phase
 * configurations.
 *
 * <p>
 * Modbus registers are based on Victron´s Modbus-TCP documentation.
 *
 * @see <a href=
 *      "https://github.com/victronenergy/dbus_modbustcp/blob/master/CCGX-Modbus-TCP-register-list.xlsx">GX
 *      Modbus-TCP list</a>
 */
public interface HycubeEss extends OpenemsComponent, EventHandler {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		SET_ACTIVE_POWER_L1(Doc.of(SHORT)//
				.unit(WATT)//
				.accessMode(WRITE_ONLY)), //
		SET_ACTIVE_POWER_L2(Doc.of(SHORT)//
				.unit(WATT)//
				.accessMode(WRITE_ONLY)), //
		SET_ACTIVE_POWER_L3(Doc.of(SHORT)//
				.unit(WATT)//
				.accessMode(WRITE_ONLY)), //
		/**
		 * DC Discharge Power.
		 *
		 * <p>
		 * Actual DC-side battery discharge power. Negative values for charge; positive
		 * for discharge. This is the power actually going into/out of the battery, not
		 * including inverter losses.
		 */
		DC_DISCHARGE_POWER(Doc.of(INTEGER)//
				.unit(WATT)//
				.persistencePriority(HIGH)//
				.text("DC battery power. Negative=Charge; Positive=Discharge")), //


		// ================= Useable Capacity & SoC =================
		/**
		 * Useable battery capacity in Wh.
		 *
		 * <p>
		 * This is the capacity available for use, excluding emergency reserves from
		 * controllers like EmergencyCapacityReserve and LimitTotalDischarge.
		 */
		USEABLE_CAPACITY(Doc.of(INTEGER)//
				.unit(WATT_HOURS)//
				.persistencePriority(HIGH)), //

		/**
		 * Useable State of Charge in %.
		 *
		 * <p>
		 * This is the SoC available for use, with controller reserves
		 * (EmergencyCapacityReserve, LimitTotalDischarge) already subtracted.
		 */
		USEABLE_SOC(Doc.of(INTEGER)//
				.unit(Unit.PERCENT)//
				.persistencePriority(HIGH)), //

		// ================= ESS Current Limits =================
		ESS_MAX_DISCHARGE_POWER(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(WATT)//
				.text("Maximum discharge power limit")), //
		SYSTEM_MAX_CHARGE_CURRENT(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(AMPERE)//
				.text("System maximum charge current")), //

		// ================= Feed-in Control =================
		MAX_FEED_IN_POWER(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(WATT)//
				.text("Maximum grid feed-in power")), //
		PV_POWER_LIMITER_ACTIVE(Doc.of(ActiveInactive.values())//
				.text("PV power limiter currently active")), //

		// ================= Battery Voltage Control =================
		MAX_CHARGE_VOLTAGE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(VOLT)//
				.text("Maximum battery charge voltage")),

		// ================= ESS Control Flags =================
		/**
		 * Disable Feedback/Discharge Flag (inverse logic!).
		 *
		 * <p>
		 * 0 = Feed-in allowed; 1 = Feed-in DISABLED.
		 */
		ESS_DISABLE_DISCHARGE_FLAG(Doc.of(EnableDisable.values())//
				.accessMode(READ_WRITE)//
				.text("0=Feed-in allowed; 1=Feed-in DISABLED (inverse logic)")), //


		/**
		 * Cumulated DC Charge Energy.
		 */
		DC_CHARGE_ENERGY(Doc.of(LONG)//
				.unit(CUMULATED_WATT_HOURS)//
				.persistencePriority(HIGH)), //

		/**
		 * Cumulated DC Discharge Energy.
		 */
		DC_DISCHARGE_ENERGY(Doc.of(LONG)//
				.unit(CUMULATED_WATT_HOURS)//
				.persistencePriority(HIGH)), //

		// ================= ESS Control Flags =================
		/**
		 * Disable Charge Flag (inverse logic!).
		 *
		 * <p>
		 * 0 = Charge allowed; 1 = Charge DISABLED.
		 */
		ESS_DISABLE_CHARGE_FLAG(Doc.of(EnableDisable.values())//
				.accessMode(READ_WRITE)//
				.text("0=Charge allowed; 1=Charge DISABLED (inverse logic)"));


		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Sets the DC discharge energy.
	 *
	 * @param value the energy value in [Wh]
	 */
	public default void _setDcDischargeEnergy(Long value) {
		this.getDcDischargeEnergyChannel().setNextValue(value);
	}

	/**
	 * Gets the DC discharge energy.
	 *
	 * @return the energy value
	 */
	public default Value<Long> getDcDischargeEnergy() {
		return this.getDcDischargeEnergyChannel().value();
	}

	/**
	 * Gets the DC discharge energy channel.
	 *
	 * @return the channel
	 */
	public default LongReadChannel getDcDischargeEnergyChannel() {
		return this.channel(ChannelId.DC_DISCHARGE_ENERGY);
	}

	/**
	 * Sets the DC charge energy.
	 *
	 * @param value the energy value in [Wh]
	 */
	public default void _setDcChargeEnergy(Long value) {
		this.getDcChargeEnergyChannel().setNextValue(value);
	}

	/**
	 * Gets the DC charge energy.
	 *
	 * @return the energy value
	 */
	public default Value<Long> getDcChargeEnergy() {
		return this.getDcChargeEnergyChannel().value();
	}

	/**
	 * Gets the DC charge energy channel.
	 *
	 * @return the channel
	 */
	public default LongReadChannel getDcChargeEnergyChannel() {
		return this.channel(ChannelId.DC_CHARGE_ENERGY);
	}

	/**
	 * Sets the disable charge flag.
	 *
	 * <p>
	 * 0 = Charging enabled; 1 = Charging DISABLED (inverse logic).
	 *
	 * @param value the {@link EnableDisable} value
	 * @throws OpenemsNamedException on error
	 */
	public default void _setDisableChargeFlag(EnableDisable value) throws OpenemsNamedException {
		this.setDisableChargeFlagChannel().setNextWriteValue(value);
	}

	public default EnableDisable getDisableChargeFlag() {
		return this.getDisableChargeFlagChannel().value().asEnum();
	}

	public default Channel<EnableDisable> getDisableChargeFlagChannel() {
		return this.channel(ChannelId.ESS_DISABLE_CHARGE_FLAG);
	}

	public default WriteChannel<EnableDisable> setDisableChargeFlagChannel() {
		return this.channel(ChannelId.ESS_DISABLE_CHARGE_FLAG);
	}

	/**
	 * Sets the disable discharge flag.
	 *
	 * <p>
	 * 0 = Discharging enabled; 1 = Discharging DISABLED (inverse logic).
	 *
	 * @param value the {@link EnableDisable} value
	 * @throws OpenemsNamedException on error
	 */
	public default void _setDisableDischargeFlag(EnableDisable value) throws OpenemsNamedException {
		this.setDisableDischargeFlagChannel().setNextWriteValue(value);
	}

	public default EnableDisable getDisableDischargeFlag() {
		return this.getDisableDischargeFlagChannel().value().asEnum();
	}

	public default Channel<EnableDisable> getDisableDischargeFlagChannel() {
		return this.channel(ChannelId.ESS_DISABLE_DISCHARGE_FLAG);
	}

	public default WriteChannel<EnableDisable> setDisableDischargeFlagChannel() {
		return this.channel(ChannelId.ESS_DISABLE_DISCHARGE_FLAG);
	}

	/**
	 * Sets the disable discharge flag.
	 *
	 * <p>
	 * 0 = Discharging enabled; 1 = Discharging DISABLED (inverse logic).
	 *
	 * @param value the {@link EnableDisable} value
	 * @throws OpenemsNamedException on error
	 */
	/**
	 * Gets the VE Bus State.
	 *
	 * @return the VE Bus State
	 */
	/**
	 * Gets the VE Bus BMS Error.
	 *
	 * @return the VE Bus BMS Error
	 */

	// ================= Grid Power Accessors =================
	/**
	 * Gets the maximum discharge power limit in [W].
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getEssMaxDischargePower() {
		return this.getEssMaxDischargePowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#ESS_MAX_DISCHARGE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getEssMaxDischargePowerChannel() {
		return this.channel(ChannelId.ESS_MAX_DISCHARGE_POWER);
	}

	/**
	 * Gets the system maximum charge current in [A].
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getSystemMaxChargeCurrent() {
		return this.getSystemMaxChargeCurrentChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#SYSTEM_MAX_CHARGE_CURRENT}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getSystemMaxChargeCurrentChannel() {
		return this.channel(ChannelId.SYSTEM_MAX_CHARGE_CURRENT);
	}

	// ================= Feed-in Control Accessors =================

	/**
	 * Gets the maximum grid feed-in power in [W].
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxFeedInPower() {
		return this.getMaxFeedInPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_FEED_IN_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxFeedInPowerChannel() {
		return this.channel(ChannelId.MAX_FEED_IN_POWER);
	}

	// ================= Battery Voltage Control Accessors =================

	/**
	 * Gets the maximum battery charge voltage in [V].
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxChargeVoltage() {
		return this.getMaxChargeVoltageChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_CHARGE_VOLTAGE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxChargeVoltageChannel() {
		return this.channel(ChannelId.MAX_CHARGE_VOLTAGE);
	}
	
	/**
	 * Sets the DC discharge power.
	 *
	 * @param value the power value in [W]
	 */
	public default void _setDcDischargePower(Integer value) {
		this.getDcDischargePowerChannel().setNextValue(value);
	}

	/**
	 * Gets the DC discharge power.
	 *
	 * @return the power value
	 */
	public default Value<Integer> getDcDischargePower() {
		return this.getDcDischargePowerChannel().value();
	}

	/**
	 * Gets the DC discharge power channel.
	 *
	 * @return the channel
	 */
	public default IntegerReadChannel getDcDischargePowerChannel() {
		return this.channel(ChannelId.DC_DISCHARGE_POWER);
	}

	
}
