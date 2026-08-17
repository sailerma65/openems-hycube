package io.openems.edge.hycube.ess;

import static io.openems.common.channel.PersistencePriority.HIGH;
import static io.openems.common.channel.Unit.WATT;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.concurrent.atomic.AtomicReference;
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
import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter.TargetGridMode;
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
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.Phase.SinglePhase;
import io.openems.edge.ess.api.AsymmetricEss;
import io.openems.edge.ess.api.ManagedAsymmetricEss;
import io.openems.edge.ess.api.ManagedSinglePhaseEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SinglePhaseEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.hycube.enums.EnableDisable;
import io.openems.edge.hycube.ess.statemachine.Context;
import io.openems.edge.hycube.ess.statemachine.StateMachine;
import io.openems.edge.hycube.ess.statemachine.StateMachine.State;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * Implementation of the Hycube ESS component.
 *
 * <p>
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Hycube.Ess", //
		immediate = true, //
		configurationPolicy = REQUIRE, //
		service = { 
			            SymmetricEss.class,          // <-- ZWINGEND ERFORDERLICH für Core.Sum
			            ManagedSymmetricEss.class,   // (Falls steuerbar)
			            OpenemsComponent.class,       // Basisschnittstelle
			            EventHandler.class
		}
)
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		TOPIC_CYCLE_BEFORE_CONTROLLERS //
})
public class HycubeEssImpl extends AbstractOpenemsModbusComponent
		implements HycubeEss, ManagedSinglePhaseEss,  EventHandler, SinglePhaseEss, ManagedSymmetricEss, AsymmetricEss,
		ManagedAsymmetricEss, OpenemsComponent, StartStoppable, ModbusSlave, TimedataProvider {

	@Reference
	private Power power;

	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	private volatile Timedata timedata = null;

	@Reference
	protected ComponentManager componentManager;

	private final Logger log = LoggerFactory.getLogger(HycubeEssImpl.class);

	private Config config;
	private SinglePhase singlePhase = null;

	private boolean operationalValuesOk = false;

	public static final int BATTERY_VOLTAGE = 48; // for capacity calculation we cannot use current voltage

	public HycubeEssImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SinglePhaseEss.ChannelId.values(), //
				ManagedSinglePhaseEss.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				AsymmetricEss.ChannelId.values(), //
				ManagedAsymmetricEss.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				HycubeEss.ChannelId.values());
	}

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private Battery battery;
	
	private Integer batteryInverterMaxChargePower;
	private Integer batteryInverterMaxDischargePower;
	private Integer batteryMaxChargePower;
	private Integer batteryMaxDischargePower;
	private Integer maxChargePowerLimit;
	private Integer maxDischargePowerLimit;

	private final CalculateEnergyFromPower calculateDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY );

	private final CalculateEnergyFromPower calculateChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);



	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}

		if( OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "Battery", config.battery_id()) )
		{
			return;
		}
		
		if( battery == null )
		{
			return;
		}
		

		this.singlePhase = switch (config.phase()) {
		case L1 -> SinglePhase.L1;
		case L2 -> SinglePhase.L2;
		case L3 -> SinglePhase.L3;
		default -> {
			this.logError(this.log, "ESS->Hycube ESS supports only 1 phase ");
			yield null;
		}
		};

		SinglePhaseEss.initializeCopyPhaseChannel(this, this.singlePhase);

		
		setValue(this, SymmetricEss.ChannelId.GRID_MODE, GridMode.ON_GRID); // Has no Backup function

		this._setMaxApparentPower(4600);
		this._setGridMode(GridMode.ON_GRID);
		
		addCopyListener( battery.getCapacityChannel(), SymmetricEss.ChannelId.CAPACITY, ElementToChannelConverter.DIRECT_1_TO_1 );

		IntegerReadChannel chan = this.channel( HycubeEss.ChannelId.SYSTEM_MAX_CHARGE_CURRENT );
		
		chan.setNextValue( 70 );
		
		chan = this.channel( HycubeEss.ChannelId.SYSTEM_MAX_DISCHARGE_CURRENT );
		
		chan.setNextValue( 100 );

		chan = this.channel( HycubeEss.ChannelId.MAX_CHARGE_VOLTAGE );
		
		chan.setNextValue( 58 );
		
		chan = this.channel( HycubeEss.ChannelId.MIN_DISCHARGE_VOLTAGE );
		
		chan.setNextValue( 40 );

		chan = this.channel( HycubeEss.ChannelId.DC_MAX_VOLTAGE );
		
		chan.setNextValue( 58 );
		
		chan = this.channel( HycubeEss.ChannelId.DC_MIN_VOLTAGE );
		
		chan.setNextValue( 40 );

		chan = switch (config.phase()) {
		case L1 -> this.channel( HycubeEss.ChannelId.GRID_L1_VOLTAGE );
		case L2 -> this.channel( HycubeEss.ChannelId.GRID_L2_VOLTAGE );
		case L3 -> this.channel( HycubeEss.ChannelId.GRID_L3_VOLTAGE );
		default -> {
			this.logError(this.log, "ESS->Hycube ESS supports only 1 phase ");
			yield null;
		}
		};

		EnumReadChannel gridModeChannel = this.channel( SymmetricEss.ChannelId.GRID_MODE );
		
		chan.onChange( (oldValue, newValue) -> 
		{
			float gridVoltage = newValue.get() * 0.1f;
			
			GridMode mode = ( gridVoltage > 200.0f ) ? GridMode.ON_GRID : GridMode.OFF_GRID;
			
			gridModeChannel._setNextValue( mode.ordinal() );
		} );
		
		IntegerReadChannel solar1 = this.channel( HycubeEss.ChannelId.SOLAR1_POWER );

		IntegerReadChannel solar2 = this.channel( HycubeEss.ChannelId.SOLAR2_POWER );

		IntegerReadChannel solarSum = this.channel( HycubeEss.ChannelId.SOLAR_SUM_POWER );

		solar2.onChange( (oldValue, solar2Power) -> 
		{
			int sum = solar1.getNextValue().get() + solar2Power.get();
			
			solarSum.setNextValue( sum );
		} );
		
		IntegerReadChannel recommendedVoltageChannel = battery.getVoltageChannel();
		
		IntegerReadChannel allowedChargePowerChannel = this.channel( ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER );
		
		battery.getChargeMaxCurrentChannel().onSetNextValue( ( chargeCurrent ) -> 
		{
			int voltage = recommendedVoltageChannel.getNextValue().get();
			
			int power = voltage * chargeCurrent.get();
			
			allowedChargePowerChannel.setNextValue( power );
		} );
		
		IntegerReadChannel allowedDishargePowerChannel = this.channel( ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER );
		
		battery.getDischargeMaxCurrentChannel().onSetNextValue( ( chargeCurrent ) -> 
		{
			int voltage = recommendedVoltageChannel.getNextValue().get();
			
			int power = - voltage * chargeCurrent.get();
			
			allowedDishargePowerChannel.setNextValue( power );
		} );
		
		addCopyListener( battery.getSocChannel(), SymmetricEss.ChannelId.SOC, ElementToChannelConverter.DIRECT_1_TO_1 );
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
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
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() //
				+ "|L:" + this.getActivePower().asString() + "/" + this.getReactivePower().asString() + "|Phase:"
				+ this.config.phase() + "|Allowed:" + this.getAllowedChargePower().asStringWithoutUnit() + ";" //
				+ this.getAllowedDischargePower().asString() //

				+ "\n" + "|" + this.getGridModeChannel().value().asOptionString();
	}

	@Override
	public boolean isManaged() {
		return !this.config.readOnlyMode();
	}

	/**
	 * Calculates and sets the maximum charge and discharge power limits based on
	 * hardware capabilities and the shared configuration limit.
	 *
	 * @return true if limits are successfully calculated, false if any required
	 *         value is missing or invalid.
	 */
	private boolean calculateHardwareLimits() {

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
		var systemMaxDischargeCurrent = this.getSystemMaxDischargeCurrent().get();
		
		if (maxChargeVoltage == null || systemMaxChargeCurrent == null || systemMaxDischargeCurrent == null ) {
			return false;
		}

		this.batteryInverterMaxChargePower = Math.round(maxChargeVoltage * systemMaxChargeCurrent);
		this.batteryInverterMaxDischargePower = Math.round(maxChargeVoltage * systemMaxDischargeCurrent);

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
	private void setPowerValues(Battery _battery_, int setActivePower, int setReactivePower) throws OpenemsNamedException {
		if (this.config == null) {
			return;
		}
		
		this.logDebug(this.log, "setActivePower " + setActivePower + " / setReactivePower " + setReactivePower);

		// Update state machine channel
		this.channel(HycubeEss.ChannelId.STATE_MACHINE).setNextValue(this.stateMachine.getCurrentState());

		// Run state machine
		var context = new Context(this, this.config, this.targetGridMode.get(), setActivePower, setReactivePower);
		try {
			this.stateMachine.run(context);
			setValue(this, HycubeEss.ChannelId.RUN_FAILED, false);

		} catch (OpenemsNamedException e) {
			setValue(this, HycubeEss.ChannelId.RUN_FAILED, true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
			this.stateMachine.forceNextState(State.ERROR);
		}
	}

	/**
	 * Updates the operational values from battery inverter.
	 *
	 * <p>
	 * Validates that battery, inverter, and BMS are ready for operation and updates
	 * allowed charge/discharge power limits accordingly.
	 */
	private void updateOperationalValues() {
		if (!Boolean.TRUE.equals(calculateHardwareLimits())) {
			this.log.warn("BatteryInverter hardware limits not available");
			this.operationalValuesOk = false;
			return;
		}

		if (getMaxApparentPower().get() == null
				|| getMaxApparentPower().get() == 0) {
			this.log.warn("Max apparent power not available");
			this.operationalValuesOk = false;
			return;
		}

		if (maxChargePowerLimit == null || maxDischargePowerLimit == null || maxChargePowerLimit < 0 || maxDischargePowerLimit < 0) {
			this.log.warn(
					"BatteryInverter Allowed Charge/Discharge values not available -> System is not ready. Values will not be applied");
			this.operationalValuesOk = false;
			return;
		}

		Integer maxApparentPower = getMaxApparentPower().get();
		if (maxApparentPower == null || maxApparentPower < 0) {
			this.log.warn(
					"BatteryInverter max. Apparent power not available -> System is not ready. Values will not be applied");
			this.operationalValuesOk = false;
			return;
		}
		this.logDebug(this.log,
				"Getting max. Charge/Discharge power values: " + maxChargePowerLimit + "/" + maxDischargePowerLimit + "W");
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, -maxChargePowerLimit);
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER, maxDischargePowerLimit);
		this._setMaxApparentPower(maxApparentPower);

		this.operationalValuesOk = true;
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
			
			@SuppressWarnings("unchecked")
			T raw2 = ( T )i_converter.channelToElement(raw);

			value = new Value<T>( sourceChannel, raw2 );
			
			targetChannel.setNextValue(value);
		};
		sourceChannel.onSetNextValue(callback);
		callback.accept(sourceChannel.getNextValue());
	}


	
	/**
	 * Applies power setpoints for asymmetric ESS operation.
	 *
	 * <p>
	 * Note: Victron uses negative values for discharge, OpenEMS uses negative for
	 * charge. Values are inverted when writing to hardware.
	 *
	 * <p>
	 * AC-Out consumption is subtracted during charging to ensure accurate battery
	 * power control.
	 */
	@Override
	public void applyPower(int activePowerTargetL1, int reactivePowerTargetL1, int activePowerTargetL2,
			int reactivePowerTargetL2, int activePowerTargetL3, int reactivePowerTargetL3)
			throws OpenemsNamedException {

		if (!this.operationalValuesOk) {
			this.logWarn(this.log, "ESS is not ready for operation. Canceling ApplyPower(p1,p2,p3,q1,q2,q3");
			return;
		}

		this.logDebug(this.log, "Asymm. PowerWanted L1: " + activePowerTargetL1 + "|L2: " + activePowerTargetL2
				+ "|L3: " + activePowerTargetL3);

		this.logDebug(this.log, "Setting max. apparent power to batteryInverter-Channel");

		// Victron: Negative values for Discharge
		// OpenEMS: Negative values for Charge

		// if we are in symmetric mode we have to device the wanted power by 3
		// In single phase

		this.logDebug(this.log,
				"OpenEMS Apply Power L1: " + activePowerTargetL1 + "|L2: " + activePowerTargetL2 + "|L3: "
						+ activePowerTargetL3 );
		// at this point we add AC Out power values
		// i.e. -300W (charge battery)
		// 100W AC Out we have to draw 300W from grid

		if (activePowerTargetL1 == 0 && activePowerTargetL2 == 0 && activePowerTargetL3 == 0) {
			this.logDebug(this.log, "\n Disabling Charging / Discharging");
			this._setDisableChargeFlag(EnableDisable.ENABLE);
			this._setDisableDischargeFlag(EnableDisable.ENABLE);
		} else {
			if (this.getDisableChargeFlag() != EnableDisable.DISABLE
					|| this.getDisableDischargeFlag() != EnableDisable.DISABLE) {
				this._setDisableChargeFlag(EnableDisable.DISABLE);
				this._setDisableDischargeFlag(EnableDisable.DISABLE);
			}
		}

		if (activePowerTargetL1 < 0) {
			// TODO Eigenverbrauch?, Notstromausgang?
			// CHARGE: AC-Out draws power from battery additionally
		}

		if (this.config.readOnlyMode()) {
			this.logDebug(this.log, "Read Only Mode is active. Power is not applied");
			return;
		}

		// Falls 1p: die nicht genutzten Phasen auf 0 setzen
		if (this.singlePhase != null) {
			switch (this.singlePhase) {
			case L1 -> {
				activePowerTargetL2 = 0;
				activePowerTargetL3 = 0;
			}
			case L2 -> {
				activePowerTargetL1 = 0;
				activePowerTargetL3 = 0;
			}
			case L3 -> {
				activePowerTargetL1 = 0;
				activePowerTargetL2 = 0;
			}
			}
		}

		setPowerValues( null, activePowerTargetL1 + activePowerTargetL2 + activePowerTargetL3,
				reactivePowerTargetL1 + reactivePowerTargetL2 + reactivePowerTargetL3); //

		this.logDebug(this.log, "Apply Power L1: " + activePowerTargetL1 + "|L2: " + activePowerTargetL2 + "|L3: "
				+ activePowerTargetL3);

	}

	/**
	 * Applies power setpoints for symmetric ESS operation.
	 *
	 * <p>
	 * For three-phase systems, power is distributed equally across all phases. For
	 * single-phase systems, power is applied only to the configured phase.
	 *
	 * <p>
	 * Note: Victron uses negative values for discharge, OpenEMS uses negative for
	 * charge. Values are inverted when writing to hardware.
	 */
	@Override
	public void applyPower(int activePowerTarget, int reactivePower) throws OpenemsNamedException {

		if (!this.operationalValuesOk) {
			this.logWarn(this.log, "ESS is not ready for operation. Canceling ApplyPower(p1,q1)");
			return;
		}

		this.logDebug(this.log, "ApplyPower Target: " + activePowerTarget + "W");

		this._setMaxApparentPower(getMaxApparentPower().get().intValue());

		if (this.maxChargePowerLimit == null || this.maxDischargePowerLimit == null) {
			this.logError(this.log, "power Limits not set.");
			return;
		}

		this.logDebug(this.log, "Max Charge/Discharge Power from Inverter: " + this.maxChargePowerLimit + "/"
				+ this.maxDischargePowerLimit + "W");

		this.logDebug(this.log, "Symm. PowerWanted: " + activePowerTarget);

		
		// AC Output power (Reg 23, 24, 25) is always positive
		int acOutputActivePowerSum = getGridOutputPowerL1().orElse(0) + getLoadOutputPowerL1().orElse(0);

		if (activePowerTarget == 0) {
			this.logDebug(this.log, "\n Disabling Charging / Discharging");
			this._setDisableChargeFlag(EnableDisable.ENABLE);
			this._setDisableDischargeFlag(EnableDisable.ENABLE);
		} else {
			if (this.getDisableChargeFlag() != EnableDisable.DISABLE
					|| this.getDisableDischargeFlag() != EnableDisable.DISABLE) {
				this._setDisableChargeFlag(EnableDisable.DISABLE);
				this._setDisableDischargeFlag(EnableDisable.DISABLE);
			}
		}

		activePowerTarget = calculateAcInSetpoint(activePowerTarget, acOutputActivePowerSum, this.maxChargePowerLimit,
				this.maxDischargePowerLimit);

		this.logDebug(this.log, "Symm. PowerWanted after clamp and AC-Out adjustment: " + activePowerTarget);

		// Negative for charging
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, this.maxChargePowerLimit * -1);
		// Positive for discharging
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER, this.maxDischargePowerLimit);

		if (this.config.readOnlyMode()) {
			this.logDebug(this.log, "Read Only Mode is active. Power is not applied");
			return;
		}

		setPowerValues(null, activePowerTarget, reactivePower); //
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	/**
	 * Calculates the AC-in setpoint by first clamping the battery power to hardware
	 * limits, then adjusting for AC-out load.
	 *
	 * <p>
	 * Clamping is applied before the AC-out adjustment so that the hardware limits
	 * constrain the battery charge/discharge power, not the total AC-in power which
	 * must also cover AC-out loads.
	 *
	 * @param activePowerTarget      the requested battery power (negative=charge,
	 *                               positive=discharge)
	 * @param acOutputActivePowerSum the total AC-out load (always positive)
	 * @param maxChargePower         the maximum charge power (positive value)
	 * @param maxDischargePower      the maximum discharge power (positive value)
	 * @return the adjusted AC-in setpoint
	 */
	protected static int calculateAcInSetpoint(int activePowerTarget, int acOutputActivePowerSum, int maxChargePower,
			int maxDischargePower) {
		// Clamp power to hardware limits before AC-Out adjustment
		if (activePowerTarget < 0 && Math.abs(activePowerTarget) > maxChargePower) {
			activePowerTarget = maxChargePower * -1;
		}
		if (activePowerTarget > 0 && activePowerTarget > maxDischargePower) {
			activePowerTarget = maxDischargePower;
		}

		// CHARGE: AC-Out draws power from battery, subtract from target
		if (activePowerTarget < 0) {
			activePowerTarget -= acOutputActivePowerSum;
		}

		return activePowerTarget;
	}

	/**
	 * Calculates and sets the active/apparent power values.
	 *
	 * <p>
	 * This method calculates total power from AC input and output measurements. AC
	 * Power input includes power to AC-Out 1/2 and self-consumption.
	 *
	 * <p>
	 * Sign convention: Negative values for charge, positive for discharge.
	 */
/*	TODO public void _setMyActivePower() {

		int acActivePowerSumInput = 0;
		int acApparentPowerSumInput = 0;
		boolean threePhase = this.singlePhase == null;

		// Read AC Input measurements (Grid side)
		var acVoltageInputL1 = this.getVoltageInputL1().orElse(0);
		var acVoltageInputL2 = this.getVoltageInputL2().orElse(0);
		var acVoltageInputL3 = this.getVoltageInputL3().orElse(0);

		var acCurrentInputL1 = this.getCurrentInputL1().orElse(0);
		var acCurrentInputL2 = this.getCurrentInputL2().orElse(0);
		var acCurrentInputL3 = this.getCurrentInputL3().orElse(0);

		// ActivePower is the actual AC output including battery discharging
		var acActivePowerInputL1 = this.getActivePowerInputL1().orElse(0);
		var acActivePowerInputL2 = this.getActivePowerInputL2().orElse(0);
		var acActivePowerInputL3 = this.getActivePowerInputL3().orElse(0);

		// Input power calculation
		acActivePowerSumInput = acActivePowerInputL1 + acActivePowerInputL2 + acActivePowerInputL3;

		if (acVoltageInputL1 > 0) { // everything else can be 0
			acApparentPowerSumInput = apparentSumVaFromMilli(acVoltageInputL1, acCurrentInputL1, acVoltageInputL2,
					acCurrentInputL2, acVoltageInputL3, acCurrentInputL3, threePhase);
		}

		var acVoltageOutputL1 = this.getVoltageOutputL1().orElse(0);
		var acVoltageOutputL2 = this.getVoltageOutputL2().orElse(0);
		var acVoltageOutputL3 = this.getVoltageOutputL3().orElse(0);

		var acCurrentOutputL1 = this.getCurrentOutputL1().orElse(0);
		var acCurrentOutputL2 = this.getCurrentOutputL2().orElse(0);
		var acCurrentOutputL3 = this.getCurrentOutputL3().orElse(0);

		// AC Output power (Reg 23-25) is always positive
		var acPowerOutputL1 = this.getActivePowerOutputL1().orElse(0);
		var acPowerOutputL2 = this.getActivePowerOutputL2().orElse(0);
		var acPowerOutputL3 = this.getActivePowerOutputL3().orElse(0);

		// Output power calculation
		int acOutputActivePowerSum = acPowerOutputL1 + acPowerOutputL2 + acPowerOutputL3;

		// apparentPower calculation comes from mA/mV
		int acApparentPowerSumOutput = 0;
		if (acVoltageOutputL1 > 0) {
			acApparentPowerSumOutput = apparentSumVaFromMilli(acVoltageOutputL1, acCurrentOutputL1, acVoltageOutputL2,
					acCurrentOutputL2, acVoltageOutputL3, acCurrentOutputL3, threePhase);
		}

		int activePowerSumWithOutput = acActivePowerSumInput + acOutputActivePowerSum;

		this._setApparentPower(acApparentPowerSumInput - acApparentPowerSumOutput);
		this._setActivePower(activePowerSumWithOutput);

		this._setActivePowerL1(acActivePowerInputL1 + acPowerOutputL1); // Asymmetric ESS nature
		if (threePhase) { // 3p
			this._setActivePowerL2(acActivePowerInputL2 + acPowerOutputL2); // Asymmetric ESS nature
			this._setActivePowerL3(acActivePowerInputL3 + acPowerOutputL3); // Asymmetric ESS nature
		}

		this.logDebug(this.log, "ActivePower Sum-Calculation. \n" + "\n Input ActivePower " + acActivePowerInputL1
				+ "W/" + acActivePowerInputL2 + "W/" + acActivePowerInputL3 + "W Sum: " + acActivePowerSumInput
				+ "\n Input Voltage " + acVoltageInputL1 + "mV/" + acVoltageInputL2 + "mV/" + acVoltageInputL3
				+ "mV ApparentPower: " + acApparentPowerSumInput + "VA" + "\n Input Current " + acCurrentInputL1 + "mA/"
				+ acCurrentInputL2 + "mA/" + acCurrentInputL3 + "mA \n" + "\n\n Output ActivePower " + acPowerOutputL1
				+ "W/" + acPowerOutputL2 + "W/" + acPowerOutputL3 + "W Sum: " + acOutputActivePowerSum + "W "
				+ "\n Output Voltage " + acVoltageOutputL1 + "mV/" + acVoltageOutputL2 + "mV/" + acVoltageOutputL3
				+ "mV ApparentPower: " + acApparentPowerSumOutput + "VA" + "\n Output Current " + acCurrentOutputL1
				+ "mA/" + acCurrentOutputL2 + "mA/" + acCurrentOutputL3 + "mA" + "\nActivePower (with OutputPower) "
				+ activePowerSumWithOutput + "W" + "\n ActivePower to Channel -> " + this.getActivePower().asString()
				+ "/"

				+ this.getApparentPower().asString()

		);

	}
*/
	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> {
			this.updateOperationalValues();
	// TODO		this._setMyActivePower();

		}
		case TOPIC_CYCLE_BEFORE_CONTROLLERS -> {
	//		this._setMyActivePower();
	 		this.calculateEnergy();
		}
		}
	}

	@Override
	public SinglePhase getPhase() {
		return this.singlePhase;
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
//		ALLOWED_CHARGE_POWER
//		ALLOWED_DISCHARGE_POWER

		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0x4000, Priority.HIGH, //
						this.m(HycubeEss.ChannelId.SOLAR1_VOLTAGE, new UnsignedWordElement(0x4000)),
						this.m(HycubeEss.ChannelId.SOLAR1_CURRENT, new UnsignedWordElement(0x4001)),
						this.m(HycubeEss.ChannelId.SOLAR1_POWER, new UnsignedWordElement(0x4002)),
						this.m(HycubeEss.ChannelId.SOLAR2_VOLTAGE, new UnsignedWordElement(0x4003)),
						this.m(HycubeEss.ChannelId.SOLAR2_CURRENT, new UnsignedWordElement(0x4004)),
						this.m(HycubeEss.ChannelId.SOLAR2_POWER, new UnsignedWordElement(0x4005)),
						this.m(HycubeEss.ChannelId.INVERTER_L1_VOLTAGE, new UnsignedWordElement(0x4006)),
						this.m(HycubeEss.ChannelId.INVERTER_L1_CURRENT, new SignedWordElement(0x4007)),
						this.m(HycubeEss.ChannelId.GRID_L1_VOLTAGE, new UnsignedWordElement(0x4008)),
						new DummyRegisterElement(0x4009, 0x4009),
						this.m(HycubeEss.ChannelId.GRID_L1_CURRENT, new SignedWordElement(0x400A)),
						new DummyRegisterElement(0x400b, 0x4014),
						this.m(HycubeEss.ChannelId.GRID_FREQUENCY, new UnsignedWordElement(0x4015)),
						this.m(HycubeEss.ChannelId.GRID_POWER_FACTOR, new SignedWordElement(0x4016)),
						this.m(HycubeEss.ChannelId.GRID_POWER_L1, new SignedWordElement(0x4017)),
						this.m(HycubeEss.ChannelId.GRID_REACTIVE_POWER_L1, new SignedWordElement(0x4018)),
						this.m(HycubeEss.ChannelId.GRID_APPARENT_POWER_L1, new SignedWordElement(0x4019)),
						this.m(HycubeEss.ChannelId.BATTERY_CURRENT, new SignedWordElement(0x401A)),
						this.m(HycubeEss.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(0x401B)),
						new DummyRegisterElement(0x401c, 0x401e),
						this.m(SymmetricEss.ChannelId.ACTIVE_POWER, new SignedWordElement(0x401f)),
						this.m(HycubeEss.ChannelId.INVERTER_TEMPERATURE, new SignedWordElement(0x4020)),
						new DummyRegisterElement(0x4021, 0x4023),
						this.m(HycubeEss.ChannelId.DSP_VERSION, new UnsignedDoublewordElement(0x4024)),
						new DummyRegisterElement(0x4026, 0x402C),
						this.m(HycubeEss.ChannelId.LOAD_OUTPUT_VOLTAGE_L1, new SignedWordElement(0x402D)),
						new DummyRegisterElement(0x402E, 0x402F),
						this.m(HycubeEss.ChannelId.OFF_GRID_FREQUENCY, new UnsignedWordElement(0x4030), ElementToChannelConverter.SCALE_FACTOR_MINUS_3 ),
						this.m(HycubeEss.ChannelId.LOAD_OUTPUT_CURRENT_L1, new UnsignedWordElement(0x4031)),
						new DummyRegisterElement(0x4032, 0x4033),
						this.m(HycubeEss.ChannelId.LOAD_OUTPUT_POWER_FACTOR, new SignedWordElement(0x4034)),
						this.m(HycubeEss.ChannelId.LOAD_POWER_L1, new SignedWordElement(0x4035)),
						this.m(HycubeEss.ChannelId.LOAD_REACTIVE_POWER_L1, new SignedWordElement(0x4036)),
						this.m(HycubeEss.ChannelId.LOAD_APPARENT_POWER_L1, new SignedWordElement(0x4037))						
						),
		new FC3ReadRegistersTask(0x4046, Priority.LOW, //
				this.m(HycubeEss.ChannelId.STATUS_WORD_4046, new UnsignedWordElement(0x4046)),
				this.m(HycubeEss.ChannelId.STATUS_WORD_4047, new UnsignedWordElement(0x4047)),
				new DummyRegisterElement(0x4048, 0x404A),
				this.m(HycubeEss.ChannelId.STATUS_WORD_404B, new UnsignedWordElement(0x404B))
				),
		// Write sleep/wake register
		new FC6WriteRegisterTask(0x405a,
				m(HycubeEss.ChannelId.SET_TARGET_BATTERY_POWER, new SignedWordElement(0x405a))),
		new FC6WriteRegisterTask(0x4058,
				m(HycubeEss.ChannelId.SET_MAX_CHARGE_CURRENT, new UnsignedWordElement(0x4058))),
		new FC6WriteRegisterTask(0x4059,
				m(HycubeEss.ChannelId.SET_MAX_DISCHARGE_CURRENT, new UnsignedWordElement(0x4059)))
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
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode), //
				SymmetricEss.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(HycubeEss.class, accessMode, 200) //
						.build());
	}

	@Override
	public void _setBatteryPowerTargetValue(int power) throws OpenemsNamedException {
		
		IntegerWriteChannel wrChannel = this.channel(HycubeEss.ChannelId.SET_TARGET_BATTERY_POWER);
		
		wrChannel.setNextWriteValue(power);
	}


}
