package io.openems.edge.hycube.ess;

import static io.openems.common.channel.AccessMode.READ_WRITE;
import static io.openems.common.channel.AccessMode.WRITE_ONLY;
import static io.openems.common.channel.Level.FAULT;
import static io.openems.common.channel.PersistencePriority.HIGH;
import static io.openems.common.channel.Unit.AMPERE;
import static io.openems.common.channel.Unit.VOLT;
import static io.openems.common.channel.Unit.WATT;
import static io.openems.common.channel.Unit.WATT_HOURS;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.LONG;
import static io.openems.common.types.OpenemsType.SHORT;
import static io.openems.common.types.OpenemsType.STRING;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.hycube.enums.ActiveInactive;
import io.openems.edge.hycube.enums.EnableDisable;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;

/**
 * This interface defines all channels for the Hycube Energy Storage System
 * connected via Modbus. It supports single-phase configurations.
 *
 * <p>
 * Modbus registers: see https://github.com/xX-Overengineered-Xx/Hycube-PV-Research-Hub/blob/main/Sermatec%20SMT-5K-TL-LV/Modbus-Register%20(HY-5K-TL-LV).xlsx
 * 
 * More info: @see https://github.com/xX-Overengineered-Xx/Hycube-PV-Research-Hub
 *
 */
public interface HycubeEss extends ManagedSymmetricEss, OpenemsComponent, EventHandler {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		// ================= State Machine =================
		STATE_MACHINE(Doc.of(State.values())//
				.text("Current state of the component state-machine")), //
		RUN_FAILED(Doc.of(FAULT)//
				.text("Running the component logic failed")), //

		// ================= System Info =================
		SERIAL_NUMBER(Doc.of(STRING)//
				.text("Serial number of the Hycube/Sermatec inverter")), //

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

		INVERTER_L1_VOLTAGE( Doc.of(INTEGER)
				.unit(Unit.DEZIVOLT).text("Inverter voltage L1")),
		
		INVERTER_L2_VOLTAGE( Doc.of(INTEGER)
				.unit(Unit.DEZIVOLT).text("Inverter voltage L2")),

		INVERTER_L3_VOLTAGE( Doc.of(INTEGER)
				.unit(Unit.DEZIVOLT).text("Inverter voltage L3")),

		INVERTER_L1_CURRENT( Doc.of(INTEGER)
				.unit(Unit.DEZIAMPERE).text("Inverter current L1")),
		
		INVERTER_L2_CURRENT( Doc.of(INTEGER)
				.unit(Unit.DEZIAMPERE).text("Inverter current L2")),

		INVERTER_L3_CURRENT( Doc.of(INTEGER)
				.unit(Unit.DEZIAMPERE).text("Inverter current L3")),

		GRID_L1_VOLTAGE( Doc.of(INTEGER)
				.unit(Unit.DEZIVOLT).text("Grid voltage L1")),
		
		GRID_L1_CURRENT( Doc.of(INTEGER)
				.unit(Unit.DEZIAMPERE).text("Grid current L1")),
		
		GRID_L2_VOLTAGE( Doc.of(INTEGER)
				.unit(Unit.DEZIVOLT).text("Grid voltage L2")),
		
		GRID_L2_CURRENT( Doc.of(INTEGER)
				.unit(Unit.DEZIAMPERE).text("Grid current L2")),
		
		GRID_L3_VOLTAGE( Doc.of(INTEGER)
				.unit(Unit.DEZIVOLT).text("Grid voltage L3")),
		
		GRID_L3_CURRENT( Doc.of(INTEGER)
				.unit(Unit.DEZIAMPERE).text("Grid current L3")),
		
		GRID_FREQUENCY( Doc.of(INTEGER)
				.unit(Unit.MILLIHERTZ).text("Grid frequency")),
		
		GRID_POWER_FACTOR( Doc.of(INTEGER).unit(Unit.THOUSANDTH).text( "Grid power factor")),

		// ================= Grid Power =================
		GRID_POWER_L1(Doc.of(INTEGER)//
				.unit(WATT)//
				.persistencePriority(HIGH)//
				.text("Grid power L1. Positive=Import; Negative=Export")), //

		GRID_POWER_L2(Doc.of(INTEGER)//
				.unit(WATT)//
				.persistencePriority(HIGH)//
				.text("Grid power L2. Positive=Import; Negative=Export")), //

		GRID_POWER_L3(Doc.of(INTEGER)//
				.unit(WATT)//
				.persistencePriority(HIGH)//
				.text("Grid power L3. Positive=Import; Negative=Export")), //

		// ================= Grid Power =================
		GRID_REACTIVE_POWER_L1(Doc.of(INTEGER)//
				.unit(Unit.VOLT_AMPERE_REACTIVE)//
				.text("Grid reactive power")), //

		GRID_APPARENT_POWER_L1(Doc.of(INTEGER)//
				.unit(Unit.VOLT_AMPERE)//
				.text("Grid reactive power")), //

		BATTERY_CURRENT(Doc.of(INTEGER)//
				.unit(Unit.DEZIAMPERE)//
				.text("Battery current")),
		
		BATTERY_VOLTAGE(Doc.of(INTEGER)//
				.unit(Unit.DEZIVOLT)//
				.text("Battery current")),
		
		SET_TARGET_BATTERY_POWER(Doc.of(INTEGER)// Soll-Wert
				.unit(Unit.WATT)//
				.accessMode(READ_WRITE) //
				.text("Battery power target value")),
		
		SET_MAX_CHARGE_CURRENT(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)
				.unit(Unit.DEZIAMPERE)//
				.text("Max. battery charge current")),
				
		SET_MAX_DISCHARGE_CURRENT(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)
				.unit(Unit.DEZIAMPERE)//
				.text("Max. battery discharge current")),
				
		INVERTER_TEMPERATURE(Doc.of(INTEGER)//
				.unit(Unit.DEZIDEGREE_CELSIUS).text("Inverter temperature")),
		
		DSP_VERSION(Doc.of(LONG)//
				.text("DSP version")),

		LOAD_OUTPUT_VOLTAGE_L1(Doc.of(INTEGER)//
				.unit(Unit.DEZIVOLT).text("Load Ouput voltage L1")),

		LOAD_OUTPUT_VOLTAGE_L2(Doc.of(INTEGER)//
				.unit(Unit.DEZIVOLT).text("Load Ouput voltage L2")),

		LOAD_OUTPUT_VOLTAGE_L3(Doc.of(INTEGER)//
				.unit(Unit.DEZIVOLT).text("Load Ouput voltage L3")),

		LOAD_OUTPUT_CURRENT_L1(Doc.of(INTEGER)//
				.unit(Unit.DEZIAMPERE).text("Load Ouput current L1")),

		LOAD_OUTPUT_CURRENT_L2(Doc.of(INTEGER)//
				.unit(Unit.DEZIAMPERE).text("Load Ouput current L2")),

		LOAD_OUTPUT_CURRENT_L3(Doc.of(INTEGER)//
				.unit(Unit.DEZIAMPERE).text("Load Ouput current L3")),

		LOAD_OUTPUT_POWER_FACTOR(Doc.of(INTEGER)//
				.unit(Unit.THOUSANDTH).text("Load Ouput power factor" )),

		LOAD_POWER_L1(Doc.of(INTEGER)//
						.unit(WATT)//
						.persistencePriority(HIGH)//
						.text("Grid power L1. Positive=Import; Negative=Export")), //

		LOAD_POWER_L2(Doc.of(INTEGER)//
				.unit(WATT)//
				.persistencePriority(HIGH)//
				.text("Grid power L2. Positive=Import; Negative=Export")), //

		LOAD_POWER_L3(Doc.of(INTEGER)//
				.unit(WATT)//
				.persistencePriority(HIGH)//
				.text("Grid power L3. Positive=Import; Negative=Export")), //
		
		// ================= Grid Power =================
		LOAD_REACTIVE_POWER_L1(Doc.of(INTEGER)//
						.unit(Unit.VOLT_AMPERE_REACTIVE)//
						.text("Grid reactive power L1")), //

		LOAD_REACTIVE_POWER_L2(Doc.of(INTEGER)//
				.unit(Unit.VOLT_AMPERE_REACTIVE)//
				.text("Grid reactive power L2")), //

		LOAD_REACTIVE_POWER_L3(Doc.of(INTEGER)//
				.unit(Unit.VOLT_AMPERE_REACTIVE)//
				.text("Grid reactive power L3")), //

		LOAD_APPARENT_POWER_L1(Doc.of(INTEGER)//
						.unit(Unit.VOLT_AMPERE)//
						.text("Grid reactive power L1")), //

		LOAD_APPARENT_POWER_L2(Doc.of(INTEGER)//
				.unit(Unit.VOLT_AMPERE)//
				.text("Grid reactive power L2")), //

		LOAD_APPARENT_POWER_L3(Doc.of(INTEGER)//
				.unit(Unit.VOLT_AMPERE)//
				.text("Grid reactive power L3")), //

		STATUS_WORD_4046(Doc.of(INTEGER)//
				.text("Status word 0x4046")), //

		STATUS_WORD_4047(Doc.of(INTEGER)//
				.text("Status word 0x4047")), //

		STATUS_WORD_404B(Doc.of(INTEGER)//
				.text("Status word 0x404B")), //

		SYSTEM_MAX_CHARGE_CURRENT(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(AMPERE)//
				.text("System maximum charge current")), //

		SYSTEM_MAX_DISCHARGE_CURRENT(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(AMPERE)//
				.text("System maximum discharge current")), //

		// ================= Battery Voltage Control =================
		DC_MAX_VOLTAGE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(VOLT)//
				.text("Maximum battery charge voltage")),

		// ================= Battery Voltage Control =================
		DC_MIN_VOLTAGE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(VOLT)//
				.text("Minimum battery discharge voltage")),

		MAX_CHARGE_VOLTAGE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(VOLT)//
				.text("Maximum battery charge voltage")),

		// ================= Battery Voltage Control =================
		MIN_DISCHARGE_VOLTAGE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(VOLT)//
				.text("Minimum battery discharge voltage")),

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
		 * Off-Grid-Frequency.
		 *
		 * <p>
		 * In Off-Grid Mode the Battery-Inverter should generate this frequency.
		 *
		 * <ul>
		 * <li>Interface: {@link OffGridBatteryInverter}
		 * <li>Type: Integer
		 * <li>Unit: Hz
		 * <li>Range: 40-60
		 * </ul>
		 */
		OFF_GRID_FREQUENCY(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.unit(Unit.HERTZ)),
		
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
		// ================= Feed-in Control =================
		MAX_FEED_IN_POWER(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(WATT)//
				.text("Maximum grid feed-in power")), //
		PV_POWER_LIMITER_ACTIVE(Doc.of(ActiveInactive.values())//
				.text("PV power limiter currently active")), //

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

		// ================= ESS Control Flags =================
		/**
		 * Disable Charge Flag (inverse logic!).
		 *
		 * <p>
		 * 0 = Charge allowed; 1 = Charge DISABLED.
		 */
		ESS_DISABLE_CHARGE_FLAG(Doc.of(EnableDisable.values())//
				.accessMode(READ_WRITE)//
				.text("0=Charge allowed; 1=Charge DISABLED (inverse logic)")),

		// ================= Registers for inverter startup phase =================
		/**
		 * PCU control policy 0x3501
		 *
		 * <p>
		 * 0002: ACTIVE
		 */
		INIT_PCU_CONTROL(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("0x0002=ACTIVE")),

		/**
		 * Remote control register 0x4100
		 *
		 * <p>
		 * 00FF: OFF
		 * FF00: ON
		 */
		INIT_REMOTE_CONTROL(Doc.of(INTEGER)//
				.accessMode(AccessMode.READ_WRITE)//
				.text("0x00FF=OFF; 0xFF00=ON")),

		// ================= Registers for inverter startup phase =================
		/**
		 * PCU control policy 0x407B
		 *
		 * <p>
		 * 0000: BMS Errors=0
		 */
		INIT_RESET_BMS_ERRORS(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("0=no errors")),

		// ================= Registers for inverter startup phase =================
		/**
		 * EPS Error Clear Mode 0x4078
		 *
		 * <p>
		 * 00EE: OFF (manual operation)
		 * EE00: ON 
		 */
		INIT_EPS_ERROR_CLEAR_MODE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("0x00EE = manual operation")),

		/**
		 * Battery minimum SOC off grid (0x405E)
		 *
		 * <p>
		 * Vorgabewert 10
		 */
		INIT_BATTERY_MINIMUM_SOC_OFF_GRID(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("target value 10")),

		/**
		 * Battery minimum SOC on grid (0x405F)
		 *
		 * <p>
		 * Vorgabewert 10
		 */
		INIT_BATTERY_MINIMUM_SOC_ON_GRID(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("target value 10")),

		/**
		 * Battery discharge SOC min (0x405B)
		 *
		 * <p>
		 * Vorgabewert 15
		 */
		INIT_BATTERY_DISCHARGE_SOC(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("target value 15")),

		/**
		 * Max. AC output power (0x4064)
		 *
		 * <p>
		 * Vorgabewert 4600
		 */
		INIT_MAX_AC_OUTPUT_POWER(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("target value 4600")),

		/**
		 * Grid Code (0x4063)
		 *
		 * <p>
		 * Vorgabewert 3 = Germany
		 */
		INIT_GRID_CODE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("target value 3 (Germany)")),

		/**
		 * Final charging voltage (0x405C)
		 *
		 * <p>
		 * Vorgabewert 53,2 V
		 */
		FINAL_CHARGING_VOLTAGE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(Unit.DEZIVOLT)
				.text("target value: 53,2")),

		/**
		 * Final discharging voltage (0x405D)
		 *
		 * <p>
		 * Vorgabewert 45,5 V
		 */
		FINAL_DISCHARGING_VOLTAGE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(Unit.DEZIVOLT)
				.text("target value 45,5")),

		/**
		 * Emergency power output (40A1)
		 *
		 * <p>
		 * 0x00EE = OFF
		 * 0xEE00 = ON
		 */
		EMERGENCY_POWER_OUTPUT_MODE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("0x00EE=OFF; oxEE00=ON")),

		/**
		 * Power factor mode (4053)
		 *
		 * <p>
		 * 0 = fixed cos phi
		 */
		INIT_POWER_FACTOR_MODE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("0 = fixed cos phi")),

		/**
		 * COS Phi (4066)
		 *
		 * <p>
		 * Vorgabewert 950 = 0.95
		 */
		INIT_POWER_FACTOR(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("cos phi")),

		/**
		 * Reactive Power (4065)
		 *
		 * <p>
		 * Vorgabewert 0
		 */
		INIT_REACTIVE_POWER(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.text("reactive power (var)"));
		
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
	 * Internal method to set the 'nextValue' on {@link ChannelId#GRID_MODE}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setGridMode(GridMode value) {
		this.getGridModeChannel().setNextValue(value);
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

	/**
	 * Gets the Channel for {@link ChannelId#SYSTEM_MAX_DISCHARGE_CURRENT}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getSystemMaxDischargeCurrentChannel() {
		return this.channel(ChannelId.SYSTEM_MAX_DISCHARGE_CURRENT);
	}

	/**
	 * Gets the system maximum discharge current in [A].
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getSystemMaxDischargeCurrent() {
		return this.getSystemMaxDischargeCurrentChannel().value();
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

	public default IntegerReadChannel getGridOutputPowerL1Channel() {
		return this.channel(ChannelId.GRID_POWER_L1);
	}
	
	public default IntegerReadChannel getLoadOutputPowerL1Channel() {
		return this.channel(ChannelId.LOAD_POWER_L1 );
	}
	
	public default Value<Integer> getGridOutputPowerL1(){
		return this.getGridOutputPowerL1Channel().value();
	}

	public default Value<Integer> getLoadOutputPowerL1(){
		return this.getLoadOutputPowerL1Channel().value();
	}

	public default IntegerReadChannel getGridOutputPowerL2Channel() {
		return this.channel(ChannelId.GRID_POWER_L2);
	}
	
	public default IntegerReadChannel getLoadOutputPowerL2Channel() {
		return this.channel(ChannelId.LOAD_POWER_L2 );
	}
	
	public default Value<Integer> getGridOutputPowerL2(){
		return this.getGridOutputPowerL2Channel().value();
	}

	public default Value<Integer> getLoadOutputPowerL2(){
		return this.getLoadOutputPowerL2Channel().value();
	}

	public default IntegerReadChannel getGridOutputPowerL3Channel() {
		return this.channel(ChannelId.GRID_POWER_L3);
	}
	
	public default IntegerReadChannel getLoadOutputPowerL3Channel() {
		return this.channel(ChannelId.LOAD_POWER_L3 );
	}
	
	public default Value<Integer> getGridOutputPowerL3(){
		return this.getGridOutputPowerL3Channel().value();
	}

	public default Value<Integer> getLoadOutputPowerL3(){
		return this.getLoadOutputPowerL3Channel().value();
	}
	
	public default IntegerReadChannel getGridVoltageChannelL1(){
		return this.channel(ChannelId.GRID_L1_VOLTAGE );
	}

	public default IntegerReadChannel getGridVoltageChannelL2(){
		return this.channel(ChannelId.GRID_L2_VOLTAGE );
	}

	public default IntegerReadChannel getGridVoltageChannelL3(){
		return this.channel(ChannelId.GRID_L3_VOLTAGE );
	}

	public default IntegerReadChannel getSumSolarPowerChannel() {
		return this.channel(ChannelId.SOLAR_SUM_POWER );
	}
	
	/**
	 * Gets the Channel for {@link ChannelId#FREQUENCY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getOffGridFrequencyChannel() {
		return this.channel(ChannelId.OFF_GRID_FREQUENCY);
	}



	public void _setBatteryPowerTargetValue( int power ) throws OpenemsNamedException;
}
