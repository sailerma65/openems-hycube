package io.openems.edge.hycube.ess;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1_AND_INVERT;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;
import static io.openems.edge.ess.power.api.Pwr.REACTIVE;
import static io.openems.edge.ess.power.api.Relationship.EQUALS;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.concurrent.atomic.AtomicReference;

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
import io.openems.edge.battery.pylontech.us2000C.PylontechUS2000CBattery;
import io.openems.edge.batteryinverter.api.BatteryInverterConstraint;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter.TargetGridMode;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.modbusslave.ModbusType;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.Phase;
import io.openems.edge.common.type.Phase.SinglePhase;
import io.openems.edge.ess.api.AsymmetricEss;
import io.openems.edge.ess.api.ManagedAsymmetricEss;
import io.openems.edge.ess.api.ManagedSinglePhaseEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SinglePhaseEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Constraint;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.hycube.ess.Config;
import io.openems.edge.hycube.ess.HycubeEss.ChannelId;
import io.openems.edge.hycube.batteryinverter.HycubeBatteryInverter;
import io.openems.edge.hycube.batteryinverter.HycubeBatteryInverterImpl;
import io.openems.edge.hycube.enums.DeviceType;
import io.openems.edge.hycube.enums.EnableDisable;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
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
		configurationPolicy = REQUIRE //
)
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		TOPIC_CYCLE_BEFORE_CONTROLLERS //
})
public class HycubeEssImpl extends AbstractOpenemsComponent
		implements HycubeEss, ManagedSinglePhaseEss, SinglePhaseEss, ManagedSymmetricEss, AsymmetricEss,
		ManagedAsymmetricEss, OpenemsComponent {

	/*
	 ** ManagedSymmetricEss **
ALLOWED_CHARGE_POWER
ALLOWED_DISCHARGE_POWER
APPLY_POWER_FAILED
DEBUG_SET_ACTIVE_POWER
	 *DEBUG_SET_REACTIVE_POWER
SET_ACTIVE_POWER_EQUALS
	 *SET_ACTIVE_POWER_GREATER_OR_EQUALS
	 *SET_ACTIVE_POWER_LESS_OR_EQUALS
	 *SET_REACTIVE_POWER_EQUALS
	 *SET_REACTIVE_POWER_GREATER_OR_EQUALS
	 *SET_REACTIVE_POWER_LESS_OR_EQUALS

	 ** AsymmetricEss **
ACTIVE_POWER_L1
	 *ACTIVE_POWER_L2
	 *ACTIVE_POWER_L3
	 *REACTIVE_POWER_L1
	 *REACTIVE_POWER_L2
	 *REACTIVE_POWER_L3

	 ** ManagedAsymmetricEss **
DEBUG_SET_ACTIVE_POWER_L1
	 *DEBUG_SET_ACTIVE_POWER_L2
	 *DEBUG_SET_ACTIVE_POWER_L3
	 *DEBUG_SET_REACTIVE_POWER_L1
	 *DEBUG_SET_REACTIVE_POWER_L2
	 *DEBUG_SET_REACTIVE_POWER_L3
SET_ACTIVE_POWER_L1_EQUALS
	 *SET_ACTIVE_POWER_L1_GREATER_OR_EQUALS
	 *SET_ACTIVE_POWER_L1_LESS_OR_EQUALS
	 *SET_ACTIVE_POWER_L2_EQUALS
	 *SET_ACTIVE_POWER_L2_GREATER_OR_EQUALS
	 *SET_ACTIVE_POWER_L2_LESS_OR_EQUALS
	 *SET_ACTIVE_POWER_L3_EQUALS
	 *SET_ACTIVE_POWER_L3_GREATER_OR_EQUALS
	 *SET_ACTIVE_POWER_L3_LESS_OR_EQUALS
	 *SET_REACTIVE_POWER_L1_EQUALS
	 *SET_REACTIVE_POWER_L1_GREATER_OR_EQUALS
	 *SET_REACTIVE_POWER_L1_LESS_OR_EQUALS
	 *SET_REACTIVE_POWER_L2_EQUALS
	 *SET_REACTIVE_POWER_L2_GREATER_OR_EQUALS
	 *SET_REACTIVE_POWER_L2_LESS_OR_EQUALS
	 *SET_REACTIVE_POWER_L3_EQUALS
	 *SET_REACTIVE_POWER_L3_GREATER_OR_EQUALS
	 *SET_REACTIVE_POWER_L3_LESS_OR_EQUALS
	 */
	
	@Reference
	private Power power;


	@Reference
	private ConfigurationAdmin cm;

	@Reference
	protected ComponentManager componentManager;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private ManagedSymmetricBatteryInverter batteryInverter;
	
	private HycubeBatteryInverterImpl hyBatteryInverter;
	
	private final Logger log = LoggerFactory.getLogger(HycubeEssImpl.class);

	private Config config;
	private SinglePhase singlePhase = null;

	private boolean operationalValuesOk = false;

	private Integer maxChargePower = null;
	private Integer maxDischargePower = null;

	public HycubeEssImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SinglePhaseEss.ChannelId.values(), //
				ManagedSinglePhaseEss.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				AsymmetricEss.ChannelId.values(), //
				ManagedAsymmetricEss.ChannelId.values(), //
				HycubeEss.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled() );

		if( OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "BatteryInverter", config.batteryInverter_id()) )
		{
			this.logError(this.log, "ESS->updateReferenceFilter returned true!");
			return;
		}

		// Set initial values from config
		this._setMaxApparentPower(config.maxApparentPower());

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

		if ( this.batteryInverter instanceof HycubeBatteryInverterImpl hyInv ) {
			hyBatteryInverter = hyInv;
		}
		else
		{
			this.logError(this.log, "ESS->BatteryInverter not yet activated ");
			return;
		}

		this._setMaxApparentPower(this.config.maxApparentPower());

		// TODO kommt aus config
				if (getMaxApparentPower().get() != null) {
					Integer maxApparentPower = getMaxApparentPower().get();
					this._setMaxApparentPower(maxApparentPower);
				} else {
					this.logError(this.log, "ESS->BatteryInverter max. apparent power not set ");
				}

/* channels to be provided:
				ALLOWED_CHARGE_POWER
				ALLOWED_DISCHARGE_POWER
				APPLY_POWER_FAILED
				DEBUG_SET_ACTIVE_POWER
				DEBUG_SET_REACTIVE_POWER
				SET_ACTIVE_POWER_EQUALS
				SET_ACTIVE_POWER_GREATER_OR_EQUALS
				SET_ACTIVE_POWER_LESS_OR_EQUALS
				SET_REACTIVE_POWER_EQUALS
				SET_REACTIVE_POWER_GREATER_OR_EQUALS
				SET_REACTIVE_POWER_LESS_OR_EQUALS

ACTIVE_POWER_L1
ACTIVE_POWER_L2
ACTIVE_POWER_L3
REACTIVE_POWER_L1
REACTIVE_POWER_L2
REACTIVE_POWER_L3


DEBUG_SET_ACTIVE_POWER_L1
DEBUG_SET_ACTIVE_POWER_L2
DEBUG_SET_ACTIVE_POWER_L3
DEBUG_SET_REACTIVE_POWER_L1
DEBUG_SET_REACTIVE_POWER_L2
DEBUG_SET_REACTIVE_POWER_L3
SET_ACTIVE_POWER_L1_EQUALS
SET_ACTIVE_POWER_L1_GREATER_OR_EQUALS
SET_ACTIVE_POWER_L1_LESS_OR_EQUALS
SET_ACTIVE_POWER_L2_EQUALS
SET_ACTIVE_POWER_L2_GREATER_OR_EQUALS
SET_ACTIVE_POWER_L2_LESS_OR_EQUALS
SET_ACTIVE_POWER_L3_EQUALS
SET_ACTIVE_POWER_L3_GREATER_OR_EQUALS
SET_ACTIVE_POWER_L3_LESS_OR_EQUALS
SET_REACTIVE_POWER_L1_EQUALS
SET_REACTIVE_POWER_L1_GREATER_OR_EQUALS
SET_REACTIVE_POWER_L1_LESS_OR_EQUALS
SET_REACTIVE_POWER_L2_EQUALS
SET_REACTIVE_POWER_L2_GREATER_OR_EQUALS
SET_REACTIVE_POWER_L2_LESS_OR_EQUALS
SET_REACTIVE_POWER_L3_EQUALS
SET_REACTIVE_POWER_L3_GREATER_OR_EQUALS
SET_REACTIVE_POWER_L3_LESS_OR_EQUALS

STATE

*/				
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public int getPowerPrecision() {
		return 100;
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
	 * Updates the operational values from battery inverter.
	 *
	 * <p>
	 * Validates that battery, inverter, and BMS are ready for operation and updates
	 * allowed charge/discharge power limits accordingly.
	 */
	private void updateOperationalValues() {
		if (this.hyBatteryInverter == null) {
			this.log.warn("ESS not ready. Battery not available");
			this.operationalValuesOk = false;
			return;
		}

		if (!Boolean.TRUE.equals(hyBatteryInverter.calculateHardwareLimits())) {
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

		Integer maxChargePower = hyBatteryInverter.getMaxChargePower(); // [W], positiv
		Integer maxDischargePower = hyBatteryInverter.getMaxDischargePower(); // [W], positiv
		if (maxChargePower == null || maxDischargePower == null || maxChargePower < 0 || maxDischargePower < 0) {
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
				"Getting max. Charge/Discharge power values: " + maxChargePower + "/" + maxDischargePower + "W");
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, -maxChargePower);
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER, maxDischargePower);
		this._setMaxApparentPower(maxApparentPower);

		this.operationalValuesOk = true;
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

		hyBatteryInverter.run( null, activePowerTargetL1 + activePowerTargetL2 + activePowerTargetL3,
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

		this.maxChargePower = hyBatteryInverter.getMaxChargePower();
		this.maxDischargePower = hyBatteryInverter.getMaxDischargePower();

		this.logDebug(this.log, "Max Charge/Discharge Power from Inverter: " + this.maxChargePower + "/"
				+ this.maxDischargePower + "W");

		if (this.maxChargePower == null || this.maxDischargePower == null) {
			this.logError(this.log, "power Limits not set.");
			return;
		}

		this.logDebug(this.log, "Symm. PowerWanted: " + activePowerTarget);

		
		// AC Output power (Reg 23, 24, 25) is always positive
		int acOutputActivePowerSum = hyBatteryInverter.getGridOutputPowerL1().orElse(0) + hyBatteryInverter.getLoadOutputPowerL1().orElse(0);

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

		activePowerTarget = calculateAcInSetpoint(activePowerTarget, acOutputActivePowerSum, this.maxChargePower,
				this.maxDischargePower);

		this.logDebug(this.log, "Symm. PowerWanted after clamp and AC-Out adjustment: " + activePowerTarget);

		// Negative for charging
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, this.maxChargePower * -1);
		// Positive for discharging
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER, this.maxDischargePower);

		// if we are in symmetric mode we have to device the wanted power by 3
		// In single phase
		int powerPerPhase = activePowerTarget;

		if (this.getPhase() == null) { // no single Phase
			if (Math.abs(activePowerTarget) > 10) {
				powerPerPhase = (int) Math.round(activePowerTarget / 3.0);
			}
		}

		if (this.config.readOnlyMode()) {
			this.logDebug(this.log, "Read Only Mode is active. Power is not applied");
			return;
		}

		batteryInverter.run(null, activePowerTarget, reactivePower); //
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
	 * Calculates apparent power sum from millivolt and milliampere values.
	 *
	 * @param u1_mV      voltage L1 in millivolts
	 * @param i1_mA      current L1 in milliamperes
	 * @param u2_mV      voltage L2 in millivolts
	 * @param i2_mA      current L2 in milliamperes
	 * @param u3_mV      voltage L3 in millivolts
	 * @param i3_mA      current L3 in milliamperes
	 * @param threePhase true if three-phase system, false for single-phase
	 * @return apparent power sum in VA
	 */
	private static int apparentSumVaFromMilli(int u1_mV, int i1_mA, int u2_mV, int i2_mA, int u3_mV, int i3_mA,
			boolean threePhase) {
		long microVA = 0L;
		microVA += 1L * Math.abs(u1_mV) * Math.abs(i1_mA);
		if (threePhase) {
			microVA += 1L * Math.abs(u2_mV) * Math.abs(i2_mA);
			microVA += 1L * Math.abs(u3_mV) * Math.abs(i3_mA);
		}
		//
		long va = microVA / 1_000_000L;
		if (va < 0) {
			va = 0;
		}
		return (int) Math.min(Integer.MAX_VALUE, va);
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
		}
		}
	}

	@Override
	public SinglePhase getPhase() {
		return this.singlePhase;
	}


	@Override
	public Constraint[] getStaticConstraints() throws OpenemsNamedException {
		if (this.config.readOnlyMode() || !this.operationalValuesOk) {
			return new Constraint[] { this.createPowerConstraint("Read-Only-Mode", ALL, ACTIVE, EQUALS, 0),
					this.createPowerConstraint("Read-Only-Mode", ALL, REACTIVE, EQUALS, 0) };
		}
		return new Constraint[] { createPowerConstraint("NoQ", ALL, REACTIVE, EQUALS, 0) };

	}
}
