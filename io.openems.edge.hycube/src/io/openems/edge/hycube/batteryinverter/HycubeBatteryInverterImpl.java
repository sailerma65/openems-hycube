package io.openems.edge.hycube.batteryinverter;

import static io.openems.common.channel.AccessMode.READ_WRITE;
import static io.openems.common.channel.Unit.AMPERE;
import static io.openems.common.channel.Unit.VOLT;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.battery.protection.BatteryProtection;
import io.openems.edge.battery.pylontech.us2000C.PylontechUS2000CBattery;
import io.openems.edge.batteryinverter.api.BatteryInverterConstraint;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.internal.AbstractReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.Phase.SingleOrAllPhase;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.hycube.batteryinverter.statemachine.Context;
import io.openems.edge.hycube.batteryinverter.statemachine.StateMachine;
import io.openems.edge.hycube.batteryinverter.statemachine.StateMachine.State;
import io.openems.edge.hycube.ess.HycubeEss;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * Implementation of the Hycube Battery Inverter component.
 *
 * <p>
 * This component communicates with Hycube systems  using
 * Modbus-TCP (Unit-ID 100 for system data). It reads system-level power flows,
 * battery status, and ESS control parameters.
 *
 * <p>
 * The inverter is controlled indirectly through the ESS component
 * ({@link HycubeEss}), which handles power setpoints.
 *
 * @see <a href=
 *      Modbus TCP list</a>
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Hycube.Battery-Inverter", //
		immediate = true, //
		configurationPolicy = REQUIRE //
) //
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class HycubeBatteryInverterImpl extends AbstractOpenemsModbusComponent implements HycubeBatteryInverter,
		ManagedSymmetricBatteryInverter, SymmetricBatteryInverter, EventHandler, OpenemsComponent, StartStoppable, ModbusSlave, TimedataProvider {

	private final Logger log = LoggerFactory.getLogger(HycubeBatteryInverterImpl.class);

	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);

	@Reference
	protected ComponentManager componentManager;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	private volatile Timedata timedata = null;

	@Reference
	protected ConfigurationAdmin cm;

//	@Reference
//	private Power power;

	protected Config config;

	public static final int BATTERY_VOLTAGE = 48; // for capacity calculation we cannot use current voltage

	public HycubeBatteryInverterImpl() throws OpenemsNamedException {
		super(//
				OpenemsComponent.ChannelId.values(), //
				OffGridBatteryInverter.ChannelId.values(), //
				SymmetricBatteryInverter.ChannelId.values(), //
				ManagedSymmetricBatteryInverter.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				HycubeBatteryInverter.ChannelId.values() //
		);
	}

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private Battery battery;
	
	private PylontechUS2000CBattery pyBattery;

	private Integer batteryInverterMaxChargePower;
	private Integer batteryInverterMaxDischargePower;
	private Integer batteryMaxChargePower;
	private Integer batteryMaxDischargePower;
	private Integer maxChargePowerLimit;
	private Integer maxDischargePowerLimit;

	private final CalculateEnergyFromPower calculateDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricBatteryInverter.ChannelId.ACTIVE_DISCHARGE_ENERGY );

	private final CalculateEnergyFromPower calculateChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricBatteryInverter.ChannelId.ACTIVE_CHARGE_ENERGY);


	@Activate
	protected void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;

		try
		{
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		}
		catch( Exception ex )
		{
			ex.printStackTrace();
		}

		if( OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "Battery", config.battery_id()) )
		{
			return;
		}
		
		if( battery instanceof PylontechUS2000CBattery pyBat )
		{
			pyBattery = pyBat;
		}
		else
		{
			return;
		}
		
		//pyBattery.getCapacity()
		
		this._setMaxApparentPower(4600);
		this._setGridMode(GridMode.ON_GRID);
		
		addCopyListener( channel( HycubeBatteryInverter.ChannelId.INVERTER_TEMPERATURE ), 
				SymmetricBatteryInverter.ChannelId.TEMPERATURE_CABINET, ElementToChannelConverter.SCALE_FACTOR_1 );
		
		addCopyListener( battery.getCapacityChannel(), HycubeBatteryInverter.ChannelId.CAPACITY, ElementToChannelConverter.DIRECT_1_TO_1 );

		IntegerReadChannel chan = this.channel( HycubeBatteryInverter.ChannelId.SYSTEM_MAX_CHARGE_CURRENT );
		
		chan.setNextValue( 70 );
		
		chan = this.channel( HycubeBatteryInverter.ChannelId.SYSTEM_MAX_DISCHARGE_CURRENT );
		
		chan.setNextValue( 100 );

		chan = this.channel( HycubeBatteryInverter.ChannelId.MAX_CHARGE_VOLTAGE );
		
		chan.setNextValue( 58 );
		
		chan = this.channel( HycubeBatteryInverter.ChannelId.MIN_DISCHARGE_VOLTAGE );
		
		chan.setNextValue( 40 );

		chan = this.channel( SymmetricBatteryInverter.ChannelId.DC_MAX_VOLTAGE );
		
		chan.setNextValue( 58 );
		
		chan = this.channel( SymmetricBatteryInverter.ChannelId.DC_MIN_VOLTAGE );
		
		chan.setNextValue( 40 );

		chan = this.channel( SymmetricBatteryInverter.ChannelId.MAX_APPARENT_POWER );
		
		chan.setNextValue( 4600 );
		
		chan = this.channel( HycubeBatteryInverter.ChannelId.GRID_L1_VOLTAGE );
		
		EnumReadChannel gridModeChannel = this.channel( SymmetricBatteryInverter.ChannelId.GRID_MODE );
		
		chan.onChange( (oldValue, newValue) -> 
		{
			float gridVoltage = newValue.get() * 0.1f;
			
			GridMode mode = ( gridVoltage > 200.0f ) ? GridMode.ON_GRID : GridMode.OFF_GRID;
			
			gridModeChannel._setNextValue( mode.ordinal() );
		} );
		
		IntegerReadChannel solar1 = this.channel( HycubeBatteryInverter.ChannelId.SOLAR1_POWER );

		IntegerReadChannel solar2 = this.channel( HycubeBatteryInverter.ChannelId.SOLAR2_POWER );

		IntegerReadChannel solarSum = this.channel( HycubeBatteryInverter.ChannelId.SOLAR_SUM_POWER );

		solar2.onChange( (oldValue, solar2Power) -> 
		{
			int sum = solar1.getNextValue().get() + solar2Power.get();
			
			solarSum.setNextValue( sum );
		} );
		
		
		addCopyListener( battery.getSocChannel(), HycubeBatteryInverter.ChannelId.SOC, ElementToChannelConverter.DIRECT_1_TO_1 );
		
	}

	/**
	 * Adds a Copy-Listener. It listens on setNextValue() and copies the value to
	 * the target channel.
	 *
	 * @param <T>             the Channel type
	 * @param sourceChannel   the source Channel
	 * @param targetChannelId the target ChannelId
	 */
	private <T> void addCopyListener(Channel<T> sourceChannel,
			io.openems.edge.common.channel.ChannelId targetChannelId, ElementToChannelConverter i_converter ) {
		Consumer<Value<T>> callback = value -> {
			Channel<T> targetChannel = this.channel(targetChannelId);
			T raw = value.get();
			
			raw = ( T )i_converter.channelToElement(raw);

			value = new Value<T>( sourceChannel, raw );
			
			targetChannel.setNextValue(value);
		};
		sourceChannel.onSetNextValue(callback);
		callback.accept(sourceChannel.getNextValue());
	}


	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	/**
	 * Calculates and sets the maximum charge and discharge power limits based on
	 * hardware capabilities and the shared configuration limit.
	 *
	 * @return true if limits are successfully calculated, false if any required
	 *         value is missing or invalid.
	 */
	@Override
	public boolean calculateHardwareLimits() {

		if (!this.getBatteryInverterLimits() || !this.getBatteryLimits()) {
			return false;
		}

		// Initial check for null values or zero configuration which indicates missing
		// setup or configuration
		if (this.batteryInverterMaxChargePower == null || this.batteryMaxChargePower == null
				|| this.batteryInverterMaxDischargePower == null || this.batteryMaxDischargePower == null
				|| this.config.maxChargePower() == 0) {
			return false;
		}

		// Calculate maximum charge power limit
		this.maxChargePowerLimit = Math.min(Math.min(this.batteryInverterMaxChargePower, this.batteryMaxChargePower),
				this.config.maxChargePower());

		// Calculate maximum discharge power limit
		this.maxDischargePowerLimit = Math.min(
				Math.min(this.batteryInverterMaxDischargePower, this.batteryMaxDischargePower),
				this.config.maxDischargePower());

		return true;
	}

	/**
	 * Gets BMS limits. Max charge current decreases according to SoC.
	 *
	 * @return true if limits were successfully retrieved, false if battery is not
	 *         available or values are missing
	 */
	public boolean getBatteryLimits() {
		if (this.battery == null) {
			return false;
		}

		var chargeMaxCurrent = this.battery.getChargeMaxCurrent().get();
		var dischargeMaxCurrent = this.battery.getDischargeMaxCurrent().get();
		var voltage = this.battery.getVoltage().get();

		if (chargeMaxCurrent == null || voltage == null) {
			return false;
		}
		this.batteryMaxChargePower = chargeMaxCurrent * voltage;

		if (dischargeMaxCurrent == null) {
			return false;
		}
		this.batteryMaxDischargePower = dischargeMaxCurrent * voltage;

		return true;
	}

	/**
	 * Gets BatteryInverter limits from Modbus registers. Keep in mind that these
	 * may differ from battery limits.
	 *
	 * @return true if limits were successfully retrieved, false if values are
	 *         missing
	 */
	public boolean getBatteryInverterLimits() {
		var maxChargeVoltage = this.getMaxChargeVoltage().get();
		var systemMaxChargeCurrent = this.getSystemMaxChargeCurrent().get();

		if (maxChargeVoltage == null || systemMaxChargeCurrent == null) {
			return false;
		}

		this.batteryInverterMaxChargePower = Math.round(maxChargeVoltage * systemMaxChargeCurrent);
		this.batteryInverterMaxDischargePower = this.batteryInverterMaxChargePower;

		var maxApparentPower = this.getMaxApparentPower().get();
		if (maxApparentPower == null || maxApparentPower == 0) {
			this.logError(this.log, "Device Type of battery inverter not configured!");
			return false;
		}
		this._setMaxApparentPower(maxApparentPower);

		return true;
	}

	/**
	 * Runs the battery inverter state machine and applies power setpoints.
	 *
	 * <p>
	 * Note: Power setpoints are controlled by the ESS component via
	 * {@link HycubeEss#applyPower}.
	 *
	 * @param battery          the battery component
	 * @param setActivePower   the active power setpoint in W (negative = charge)
	 * @param setReactivePower the reactive power setpoint in var
	 * @throws OpenemsNamedException on error
	 */
	@Override
	public void run(Battery _battery_, int setActivePower, int setReactivePower) throws OpenemsNamedException {
		if (this.config == null) {
			return;
		}
		
		this.logDebug(this.log, "setActivePower " + setActivePower + " / setReactivePower " + setReactivePower);

		// Update state machine channel
		this.channel(HycubeBatteryInverter.ChannelId.STATE_MACHINE).setNextValue(this.stateMachine.getCurrentState());

		// Run state machine
		var context = new Context(this, this.config, this.targetGridMode.get(), setActivePower, setReactivePower);
		try {
			this.stateMachine.run(context);
			setValue(this, HycubeBatteryInverter.ChannelId.RUN_FAILED, false);

		} catch (OpenemsNamedException e) {
			setValue(this, HycubeBatteryInverter.ChannelId.RUN_FAILED, true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
			this.stateMachine.forceNextState(State.ERROR);
		}
	}

	@Override
	public String debugLog() {
		return this.stateMachine.getCurrentState().asCamelCase() //
				+ " | Limits (Charge/Discharge) - Battery: " + this.batteryMaxChargePower + "/"
				+ this.batteryMaxDischargePower + ", Inverter: " + this.batteryInverterMaxChargePower + "/"
				+ this.batteryInverterMaxDischargePower + ", Config: " + this.config.maxChargePower() + "/"
				+ this.config.maxDischargePower();
	}

	/**
	 * Uses Info Log for further debug features.
	 */
	@Override
	protected void logDebug(Logger log, String message) {
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> {
	// TODO		this._setMyActivePower();

		}
		case TOPIC_CYCLE_BEFORE_CONTROLLERS -> {
	//		this._setMyActivePower();
	 		this.calculateEnergy();
		}
		}
		
	}

	/**
	 * Calculate the Energy values for AC-side.
	 *
	 * <p>
	 * Negative values for Charge; positive for Discharge.
	 */
	private void calculateEnergy() {

		var activeAcPower = this.getActivePower().get();
		if (activeAcPower == null) {
			// Not available
			this.calculateChargeEnergy.update(null);
			this.calculateDischargeEnergy.update(null); //
		} else if (activeAcPower > 0) {
			// Discharge
			this.calculateChargeEnergy.update(0);
			this.calculateDischargeEnergy.update(activeAcPower);
		} else if (activeAcPower < 0) {
			// Charge
			this.calculateChargeEnergy.update(activeAcPower * -1);
			this.calculateDischargeEnergy.update(0);
		} else {
			// Undefined
			this.calculateChargeEnergy.update(0);
			this.calculateDischargeEnergy.update(0);
		}

		// TODO
		// this._setDcDischargePower(getActivePower().get());

	}


	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}


	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);

	@Override
	public void setStartStop(StartStop value) {
		if (this.startStopTarget.getAndSet(value) != value) {
			// Set only if value changed
			this.stateMachine.forceNextState(State.UNDEFINED);
		}
	}

	/**
	 * Gets the current start/stop target based on configuration.
	 *
	 * @return the effective {@link StartStop} target
	 */
	public StartStop getStartStopTarget() {
		return switch (this.config.startStop()) {
		case AUTO -> this.startStopTarget.get();
		case START -> StartStop.START;
		case STOP -> StartStop.STOP;
		};
	}

	protected final AtomicReference<TargetGridMode> targetGridMode = new AtomicReference<>(TargetGridMode.GO_ON_GRID);

	@Override
	public void setTargetGridMode(TargetGridMode targetGridMode) {
		if (this.targetGridMode.getAndSet(targetGridMode) != targetGridMode) {
			// Set only if value changed
			this.stateMachine.forceNextState(State.UNDEFINED);
		}
	}

	@Override
	public BatteryInverterConstraint[] getStaticConstraints() throws OpenemsException {
		if (this.stateMachine.getCurrentState() == State.RUNNING) {
			return BatteryInverterConstraint.NO_CONSTRAINTS;

		}
		// Block any power as long as we are not RUNNING
		return new BatteryInverterConstraint[] { //
				new BatteryInverterConstraint("Hycube inverter not ready", SingleOrAllPhase.ALL, Pwr.REACTIVE, //
						Relationship.EQUALS, 0), //
				new BatteryInverterConstraint("Hycube inverter not ready", SingleOrAllPhase.ALL, Pwr.ACTIVE, //
						Relationship.EQUALS, 0) //
		};
	}

	@Override
	public int getPowerPrecision() {
		return 100;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		/*
		 * GRID_MODE
ACTIVE_POWER
REACTIVE_POWER
APPARENT_POWER
MAX_APPARENT_POWER
ACTIVE_CHARGE_ENERGY
ACTIVE_DISCHARGE_ENERGY
DC_MIN_VOLTAGE
DC_MAX_VOLTAGE
TEMPERATURE_CABINET
		 */
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0x4000, Priority.HIGH, //
						this.m(HycubeBatteryInverter.ChannelId.SOLAR1_VOLTAGE, new UnsignedWordElement(0x4000)),
						this.m(HycubeBatteryInverter.ChannelId.SOLAR1_CURRENT, new UnsignedWordElement(0x4001)),
						this.m(HycubeBatteryInverter.ChannelId.SOLAR1_POWER, new UnsignedWordElement(0x4002)),
						this.m(HycubeBatteryInverter.ChannelId.SOLAR2_VOLTAGE, new UnsignedWordElement(0x4003)),
						this.m(HycubeBatteryInverter.ChannelId.SOLAR2_CURRENT, new UnsignedWordElement(0x4004)),
						this.m(HycubeBatteryInverter.ChannelId.SOLAR2_POWER, new UnsignedWordElement(0x4005)),
						this.m(HycubeBatteryInverter.ChannelId.INVERTER_L1_VOLTAGE, new UnsignedWordElement(0x4006)),
						this.m(HycubeBatteryInverter.ChannelId.INVERTER_L1_CURRENT, new SignedWordElement(0x4007)),
						this.m(HycubeBatteryInverter.ChannelId.GRID_L1_VOLTAGE, new UnsignedWordElement(0x4008)),
						new DummyRegisterElement(0x4009, 0x4009),
						this.m(HycubeBatteryInverter.ChannelId.GRID_L1_CURRENT, new SignedWordElement(0x400A)),
						new DummyRegisterElement(0x400b, 0x4014),
						this.m(HycubeBatteryInverter.ChannelId.GRID_FREQUENCY, new UnsignedWordElement(0x4015)),
						this.m(HycubeBatteryInverter.ChannelId.GRID_POWER_FACTOR, new SignedWordElement(0x4016)),
						this.m(HycubeBatteryInverter.ChannelId.GRID_POWER_L1, new SignedWordElement(0x4017)),
						this.m(HycubeBatteryInverter.ChannelId.GRID_REACTIVE_POWER_L1, new SignedWordElement(0x4018)),
						this.m(HycubeBatteryInverter.ChannelId.GRID_APPARENT_POWER_L1, new SignedWordElement(0x4019)),
						this.m(HycubeBatteryInverter.ChannelId.BATTERY_CURRENT, new SignedWordElement(0x401A)),
						this.m(HycubeBatteryInverter.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(0x401B)),
						new DummyRegisterElement(0x401c, 0x401e),
						this.m(SymmetricBatteryInverter.ChannelId.ACTIVE_POWER, new SignedWordElement(0x401f)),
						this.m(HycubeBatteryInverter.ChannelId.INVERTER_TEMPERATURE, new SignedWordElement(0x4020)),
						new DummyRegisterElement(0x4021, 0x4023),
						this.m(HycubeBatteryInverter.ChannelId.DSP_VERSION, new UnsignedDoublewordElement(0x4024)),
						new DummyRegisterElement(0x4026, 0x402C),
						this.m(HycubeBatteryInverter.ChannelId.LOAD_OUTPUT_VOLTAGE_L1, new SignedWordElement(0x402D)),
						new DummyRegisterElement(0x402E, 0x402F),
						this.m(OffGridBatteryInverter.ChannelId.OFF_GRID_FREQUENCY, new UnsignedWordElement(0x4030), ElementToChannelConverter.SCALE_FACTOR_MINUS_3 ),
						this.m(HycubeBatteryInverter.ChannelId.LOAD_OUTPUT_CURRENT_L1, new UnsignedWordElement(0x4031)),
						new DummyRegisterElement(0x4032, 0x4033),
						this.m(HycubeBatteryInverter.ChannelId.LOAD_OUTPUT_POWER_FACTOR, new SignedWordElement(0x4034)),
						this.m(HycubeBatteryInverter.ChannelId.LOAD_POWER_L1, new SignedWordElement(0x4035)),
						this.m(HycubeBatteryInverter.ChannelId.LOAD_REACTIVE_POWER_L1, new SignedWordElement(0x4036)),
						this.m(HycubeBatteryInverter.ChannelId.LOAD_APPARENT_POWER_L1, new SignedWordElement(0x4037))						
						),
		new FC3ReadRegistersTask(0x4046, Priority.LOW, //
				this.m(HycubeBatteryInverter.ChannelId.STATUS_WORD_4046, new UnsignedWordElement(0x4046)),
				this.m(HycubeBatteryInverter.ChannelId.STATUS_WORD_4047, new UnsignedWordElement(0x4047)),
				new DummyRegisterElement(0x4048, 0x404A),
				this.m(HycubeBatteryInverter.ChannelId.STATUS_WORD_404B, new UnsignedWordElement(0x404B))
				),
		// Write sleep/wake register
		new FC6WriteRegisterTask(0x405a,
				m(HycubeBatteryInverter.ChannelId.SET_TARGET_BATTERY_POWER, new SignedWordElement(0x405a))),
		new FC6WriteRegisterTask(0x4058,
				m(HycubeBatteryInverter.ChannelId.SET_MAX_CHARGE_CURRENT, new UnsignedWordElement(0x4058))),
		new FC6WriteRegisterTask(0x4059,
				m(HycubeBatteryInverter.ChannelId.SET_MAX_DISCHARGE_CURRENT, new UnsignedWordElement(0x4059)))
		);
	}

	/**
	 * Executes a Soft-Start.
	 *
	 * <p>
	 * Note: Hycube inverters handle soft-start internally when connected via
	 * Modbus. This method is a placeholder for potential future implementation.
	 *
	 * @param switchOn true to enable soft-start, false to disable
	 * @throws OpenemsNamedException on error
	 */
	public void softStart(boolean switchOn) throws OpenemsNamedException {
		// Victron handles soft-start internally - no action required
	}

	@Override
	public Integer getMaxChargePower() {
		return this.maxChargePowerLimit;
	}

	@Override
	public Integer getMaxDischargePower() {
		return this.maxDischargePowerLimit;
	}
	
	private boolean _isChargeEnabled()
	{
		if( pyBattery != null )
		{
			return pyBattery.isChargeEnabled();
		}
		else
		{
			return ( battery.getChargeMaxCurrent().get() > 0 );
		}
	}

	private boolean _isDischargeEnabled()
	{
		if( pyBattery != null )
		{
			return pyBattery.isDischargeEnabled();
		}
		else
		{
			return ( battery.getDischargeMaxCurrent().get() > 0 );
		}
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				SymmetricBatteryInverter.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricBatteryInverter.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(HycubeBatteryInverter.class, accessMode, 200) //
						.build());
	}

	@Override
	public void _setBatteryPowerTargetValue(int power) throws OpenemsNamedException {
		
		IntegerWriteChannel wrChannel = this.channel(HycubeBatteryInverter.ChannelId.SET_TARGET_BATTERY_POWER);
		
		wrChannel.setNextWriteValue(power);
	}
}
