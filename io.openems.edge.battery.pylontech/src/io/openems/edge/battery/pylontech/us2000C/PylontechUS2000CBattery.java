package io.openems.edge.battery.pylontech.us2000C;

import static io.openems.common.channel.AccessMode.READ_ONLY;
import static io.openems.common.channel.AccessMode.READ_WRITE;
import static io.openems.common.channel.Unit.DEGREE_CELSIUS;
import static io.openems.common.channel.Unit.KILOOHM;
import static io.openems.common.channel.Unit.NONE;
import static io.openems.common.channel.Unit.VOLT;
import static io.openems.common.channel.Unit.WATT_HOURS;
import static io.openems.common.types.OpenemsType.BOOLEAN;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.STRING;

import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine.State;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;

public interface PylontechUS2000CBattery extends Battery, OpenemsComponent, StartStoppable {

	public static enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		// / 3.2 Equipment Information
		VERSION_STRING(Doc.of(STRING) //
				.accessMode(READ_ONLY) //
				.text("Pylontech BMS version number")),
		SYSTEM_NUMBER_OF_PARALLEL_DEVICES(Doc.of(INTEGER) //
				.unit(NONE) //
				.accessMode(READ_ONLY) //
				.text("Number of parallel devices in system")),

		/*
		 * Pylontech's RS485 protocol refers to several states (e.g low temperature)
		 * where the battery still functions (at reduced C rate) as 'Alarms'. In this
		 * implementation we are calling them 'Warnings' as an alarm state is usually
		 * something that stops the battery as understood by the OpenEMS community.
		 * 
		 * Pylontech's protocol also defines 'Protection' states. This is where the
		 * operational range is exceeded and the main contactor is opened (disconnecting
		 * the battery). For now we are calling these 'Protection'.
		 */
		// 3.4 System Information

		BASIC_STATUS(Doc.of(Status.values()) //
				.accessMode(READ_ONLY) //
				.text("System status. 00=Sleep, 01=Charge, 02=Discharge")),
		SYSTEM_ERROR_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("System error protection (0: Normal, 1: Protect)")),
		SYSTEM_CURRENT_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Current Protection active")),
		SYSTEM_OVER_VOLTAGE(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Voltage Protection Active.")),
		SYSTEM_TEMPERATURE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Temperature Protection Active")),
		SYSTEM_UNDER_VOLTAGE(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Voltage Warning")),
		SYSTEM_CURRENT_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Current Warning")),
		SYSTEM_TEMPERATURE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Temperature Warning")),
		SYSTEM_IDLE_STATUS(Doc.of(BOOLEAN) //
				.accessMode(READ_ONLY) //
				.text("System idle status")),
		SYSTEM_CHARGE_STATUS(Doc.of(BOOLEAN) //
				.accessMode(READ_ONLY) //
				.text("System charge status")),
		SYSTEM_DISCHARGE_STATUS(Doc.of(BOOLEAN) //
				.accessMode(READ_ONLY) //
				.text("System discharge status")),
		SYSTEM_FAN_WARN(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("System fan warning")),
		BATTERY_CELL_UNDER_VOLTAGE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Battery cell under voltage protection")),
		BATTERY_CELL_OVER_VOLTAGE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Battery cell over voltage protection")),
		SYSTEM_UNDER_VOLTAGE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Pile under voltage protection")),
		SYSTEM_OVER_VOLTAGE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Pile over voltage protection")),
		CHARGE_UNDER_TEMPERATURE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Charge under temperature protection")),
		CHARGE_OVER_TEMPERATURE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Charge over temperature protection")),
		DISCHARGE_UNDER_TEMPERATURE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Discharge under temperature protection")),
		DISCHARGE_OVER_TEMPERATURE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Discharge over temperature protection")),
		CHARGE_OVER_CURRENT_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Charge over current protection")),
		DISCHARGE_OVER_CURRENT_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Discharge over current protection")),
		MODULE_OVER_TEMPERATURE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Module over temperature protection")),
		MODULE_UNDER_TEMPERATURE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Module over temperature protection")),
		MODULE_UNDER_VOLTAGE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Module under voltage protection")),
		MODULE_OVER_VOLTAGE_PROTECTION(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Module over voltage protection")),
		COMMUNICATION_ERROR(Doc.of(Level.FAULT) //
				.accessMode(READ_ONLY) //
				.text("Communication Error")),
		BATTERY_CELL_LOW_VOLTAGE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Battery cell low voltage warning")),
		BATTERY_CELL_HIGH_VOLTAGE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Battery cell high voltage warning")),
		PILE_LOW_VOLTAGE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Pile low voltage warning")),
		PILE_HIGH_VOLTAGE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Pile high voltage warning")),
		CHARGE_LOW_TEMPERATURE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Charge low temperature warning")),
		CHARGE_HIGH_TEMPERATURE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Charge high temperature warning")),
		DISCHARGE_LOW_TEMPERATURE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Discharge low temperature warning")),
		DISCHARGE_HIGH_TEMPERATURE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Discharge high temperature warning")),
		CHARGE_OVER_CURRENT_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Charge over current warning")),
		DISCHARGE_OVER_CURRENT_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Disharge over current warning")),
		BMS_HIGH_TEMPERATURE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Main controller (BMS) high temperature warning")),
		MODULE_HIGH_TEMPERATURE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Module high temperature warning")),
		MODULE_LOW_TEMPERATURE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Module low temperature warning")),
		MODULE_LOW_VOLTAGE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Module low voltage warning")),
		MODULE_HIGH_VOLTAGE_WARNING(Doc.of(Level.WARNING) //
				.accessMode(READ_ONLY) //
				.text("Module high voltage warning")),
		CHARGE_ENABLE(Doc.of(BOOLEAN) //
				.accessMode(READ_ONLY) //
				.text("Charge enable")),
		DISCHARGE_ENABLE(Doc.of(BOOLEAN) //
				.accessMode(READ_ONLY) //
				.text("Discharge enable")),
		SYSTEM_TEMPERATURE(Doc.of(INTEGER) //
				.unit(DEGREE_CELSIUS) //
				.accessMode(READ_ONLY) //
				.text("Temperature")),
		CYCLE_TIMES(Doc.of(INTEGER) //
				.accessMode(READ_ONLY) //
				.text("Cycle times")),
		BUZZER_ACTIVE(Doc.of(BOOLEAN) //
				.accessMode(READ_ONLY) //
				.text("Buzzer active")),
		PY_MAX_CHARGE_VOLTAGE(Doc.of(INTEGER) //
				.accessMode(READ_ONLY)
				.unit(Unit.DEZIVOLT)),
		PY_MIN_DISCHARGE_VOLTAGE(Doc.of(INTEGER) //
				.accessMode(READ_ONLY)
				.unit(Unit.DEZIVOLT)),
		PY_MAX_CHARGE_CURRENT(Doc.of(INTEGER) //
				.accessMode(READ_ONLY)
				.unit(Unit.DEZIAMPERE)),
		PY_MAX_DISCHARGE_CURRENT(Doc.of(INTEGER) //
				.accessMode(READ_ONLY)
				.unit(Unit.DEZIAMPERE)),
		PY_BATTERY_VOLTAGE(Doc.of(INTEGER) //
				.accessMode(READ_ONLY)
				.unit(Unit.DEZIVOLT)),
		STATE_MACHINE(Doc.of(State.values()) //
				.text("Current state of state machine")),
		RUN_FAILED(Doc.of(Level.FAULT) //
				.text("Running the Logic failed")) //
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

	/**
	 * Gets the target Start/Stop mode from config or StartStop-Channel.
	 * 
	 * @return {@link StartStop}
	 */
	public StartStop getStartStopTarget();

	/**
	 * Gets the current system status.
	 * 
	 * @return a Status enum containing the current system status
	 */
	public default Status getSystemBasicStatus() {
		return this.getSystemBasicStatusChannel().value().asEnum();
	}

	/**
	 * Get the basic status channel.
	 * 
	 * @return The BASIC_STATUS channel
	 */
	public default Channel<Status> getSystemBasicStatusChannel() {
		return this.channel(ChannelId.BASIC_STATUS);
	}

	/**
	 * Get the basic status channel.
	 * 
	 * @return The BASIC_STATUS channel
	 */
	public default Channel<Boolean> getChargeEnabledChannel() {
		return this.channel(ChannelId.CHARGE_ENABLE);
	}
	/**
	 * Get the basic status channel.
	 * 
	 * @return The BASIC_STATUS channel
	 */
	public default Channel<Boolean> getDishargeEnabledChannel() {
		return this.channel(ChannelId.DISCHARGE_ENABLE);
	}
	
	public default boolean isChargeEnabled()
	{
		return getChargeEnabledChannel().value().orElse(Boolean.FALSE);
	}

	public default boolean isDischargeEnabled()
	{
		return getDishargeEnabledChannel().value().orElse(Boolean.FALSE);
	}

	
	public default Channel<Integer> getMaxChargeCurrentChannel() {
		return this.channel(ChannelId.PY_MAX_CHARGE_CURRENT );
	}

	public default Channel<Integer> getMaxDischargeCurrentChannel() {
		return this.channel(ChannelId.PY_MAX_DISCHARGE_CURRENT );
	}

	public default Channel<Integer> getMaxChargeVoltageChannel() {
		return this.channel(ChannelId.PY_MAX_CHARGE_VOLTAGE );
	}

	public default Channel<Integer> getMinDischargVoltagetChannel() {
		return this.channel(ChannelId.PY_MIN_DISCHARGE_VOLTAGE );
	}
	
	public default Channel<Integer> getPylontechBatteryVoltageChannel() {
		return this.channel(ChannelId.PY_BATTERY_VOLTAGE );
	}

	public default Integer getMaxChargeCurrent()
	{
		return getMaxChargeCurrentChannel().value().orElse(0);
	}

	public default Integer getMaxDischargeCurrent()
	{
		return getMaxDischargeCurrentChannel().value().orElse(0);
	}

	public default Integer getMaxChargeVoltage()
	{
		return getMaxChargeVoltageChannel().value().orElse(0);
	}

	public default Integer getMinDischargeVoltage()
	{
		return getMinDischargVoltagetChannel().value().orElse(0);
	}

	public default Integer getPylontechBatteryVoltage()
	{
		return getPylontechBatteryVoltageChannel().value().orElse(0);
	}
}