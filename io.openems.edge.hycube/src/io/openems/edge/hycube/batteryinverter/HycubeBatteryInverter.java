package io.openems.edge.hycube.batteryinverter;

import static io.openems.common.channel.AccessMode.READ_WRITE;
import static io.openems.common.channel.Level.FAULT;
import static io.openems.common.channel.PersistencePriority.HIGH;
import static io.openems.common.channel.Unit.AMPERE;
import static io.openems.common.channel.Unit.AMPERE_HOURS;
import static io.openems.common.channel.Unit.ON_OFF;
import static io.openems.common.channel.Unit.PERCENT;
import static io.openems.common.channel.Unit.SECONDS;
import static io.openems.common.channel.Unit.VOLT;
import static io.openems.common.channel.Unit.WATT;
import static io.openems.common.types.OpenemsType.BOOLEAN;
import static io.openems.common.types.OpenemsType.LONG;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.STRING;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.battery.pylontech.us2000C.PylontechUS2000CBattery;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.ess.api.SymmetricEss.ChannelId;
import io.openems.edge.hycube.batteryinverter.statemachine.StateMachine.State;
import io.openems.edge.hycube.enums.ActiveInactive;
import io.openems.edge.hycube.enums.ActiveInputSource;
import io.openems.edge.hycube.enums.BatteryState;

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
public interface HycubeBatteryInverter extends OffGridBatteryInverter, ManagedSymmetricBatteryInverter,
		SymmetricBatteryInverter, OpenemsComponent, StartStoppable, EventHandler, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		// ================= State Machine =================
		STATE_MACHINE(Doc.of(State.values())//
				.text("Current state of the component state-machine")), //
		RUN_FAILED(Doc.of(FAULT)//
				.text("Running the component logic failed")), //

		/**
		 * State of Charge.
		 *
		 * <ul>
		 * <li>Interface: Battery
		 * <li>Type: Integer
		 * <li>Unit: %
		 * <li>Range: 0..100
		 * </ul>
		 */
		SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.persistencePriority(PersistencePriority.HIGH)), //

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
				.unit(Unit.DEZIAMPERE)//
				.text("Max. battery charge current")),
				
		SET_MAX_DISCHARGE_CURRENT(Doc.of(INTEGER)//
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

		/**
		 * Capacity.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Wh
		 * </ul>
		 *
		 * @since 2019.5.0
		 */
		CAPACITY(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.WATT_HOURS)//
				.persistencePriority(PersistencePriority.HIGH)),

		SYSTEM_MAX_CHARGE_CURRENT(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(AMPERE)//
				.text("System maximum charge current")), //

		SYSTEM_MAX_DISCHARGE_CURRENT(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(AMPERE)//
				.text("System maximum discharge current")), //

		// ================= Battery Voltage Control =================
		MAX_CHARGE_VOLTAGE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(VOLT)//
				.text("Maximum battery charge voltage")),

		// ================= Battery Voltage Control =================
		MIN_DISCHARGE_VOLTAGE(Doc.of(INTEGER)//
				.accessMode(READ_WRITE)//
				.unit(VOLT)//
				.text("Minimum battery discharge voltage"));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	// ================= AC PV on Output (Critical Loads) Accessors

	// ================= Grid Power Accessors =================

	/**
	 * Gets the grid power L1 in [W].
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getGridPowerL1() {
		return this.getGridPowerL1Channel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#GRID_POWER_L1}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getGridPowerL1Channel() {
		return this.channel(ChannelId.GRID_POWER_L1);
	}

	// ================= Generator Power Accessors =================
	// ================= DC Battery Accessors =================

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

	public default IntegerReadChannel getSumSolarPowerChannel() {
		return this.channel(ChannelId.SOLAR_SUM_POWER );
	}
	
	/**
	 * Gets the maximum charge power in [W].
	 *
	 * @return the max charge power or null if not available
	 */
	public Integer getMaxChargePower();

	/**
	 * Gets the maximum discharge power in [W].
	 *
	 * @return the max discharge power or null if not available
	 */
	public Integer getMaxDischargePower();

	/**
	 * Gets the Channel for {@link ChannelId#CAPACITY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getCapacityChannel() {
		return this.channel(ChannelId.CAPACITY);
	}

	/**
	 * Gets the Capacity in [Wh]. See {@link ChannelId#CAPACITY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getCapacity() {
		return this.getCapacityChannel().value();
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

	public default IntegerReadChannel getGridCurrentChannelL1(){
		return this.channel(ChannelId.GRID_L1_CURRENT );
	}

	public default IntegerReadChannel getGridCurrentChannelL2(){
		return this.channel(ChannelId.GRID_L2_CURRENT );
	}

	public default IntegerReadChannel getGridCurrentChannelL3(){
		return this.channel(ChannelId.GRID_L3_CURRENT );
	}

	public default IntegerReadChannel getGridPowerChannelL1(){
		return this.channel(ChannelId.GRID_POWER_L1 );
	}

	public default IntegerReadChannel getGridPowerChannelL2(){
		return this.channel(ChannelId.GRID_POWER_L2 );
	}

	public default IntegerReadChannel getGridPowerChannelL3(){
		return this.channel(ChannelId.GRID_POWER_L3 );
	}
	
	public default IntegerReadChannel getSocChannel() {
		return this.channel(ChannelId.SOC );
	}
	/**
	 * Calculates hardware limits from battery and inverter.
	 *
	 * @return true if limits were successfully calculated
	 */
	public boolean calculateHardwareLimits();
	
	public void _setBatteryPowerTargetValue( int power ) throws OpenemsNamedException;
}
