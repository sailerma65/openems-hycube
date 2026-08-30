package io.openems.edge.battery.pylontech.us2000C;

import io.openems.edge.battery.protection.BatteryProtectionDefinition;
import io.openems.edge.battery.protection.force.ForceCharge;
import io.openems.edge.battery.protection.force.ForceDischarge;
import io.openems.edge.common.linecharacteristic.PolyLine;

public class PylontechUS2000CBatteryProtectionDefinition implements BatteryProtectionDefinition {

	private int initBmsMaxEverCharge;
	private int initBmsMaxEverDischarge;
	private double maxIncreasePerSecond;
	
	public PylontechUS2000CBatteryProtectionDefinition( int _initBmsMaxEverCharge, int _initBmsMaxEverDischarge, double _maxIncreasePerSecond )
	{
		initBmsMaxEverCharge = _initBmsMaxEverCharge;
		initBmsMaxEverDischarge = _initBmsMaxEverDischarge;
		maxIncreasePerSecond = _maxIncreasePerSecond;
	}
	/*
	 * Most values not defined. Those that are defined come from Pylontech engineer.
	 */
	@Override
	public int getInitialBmsMaxEverChargeCurrent() {
		return initBmsMaxEverCharge; // [A]
	}

	@Override
	public int getInitialBmsMaxEverDischargeCurrent() {
		return initBmsMaxEverDischarge; // [A]
	}

	@Override
	public PolyLine getChargeVoltageToPercent() {
		return PolyLine.empty();
	}

	@Override
	public PolyLine getDischargeVoltageToPercent() {
		return PolyLine.empty();
	}

	@Override
	public PolyLine getChargeTemperatureToPercent() {
		return PolyLine.empty();
	}

	@Override
	public PolyLine getDischargeTemperatureToPercent() {
		return PolyLine.empty();
	}

	@Override
	public ForceDischarge.Params getForceDischargeParams() {
		return new ForceDischarge.Params(3650, 3450, 3449);
	}

	@Override
	public ForceCharge.Params getForceChargeParams() {
		return new ForceCharge.Params(2700, 3000, 3001);
	}

	@Override
	public Double getMaxIncreaseAmperePerSecond() {
		// [A] per second
		// This is not provided by Pylontech. May be unnecessary to
		// provide this value as BMS takes care.
		return maxIncreasePerSecond;
	}

	@Override
	public PolyLine getChargeSocToPercent() {
		return PolyLine.empty();
	}

	@Override
	public PolyLine getDischargeSocToPercent() {
		return PolyLine.empty();
	}

	@Override
	public boolean isChargeAllowed() {
		return true;
	}

	@Override
	public boolean isDischargeAllowed() {
		return true;
	}
}
