package io.openems.edge.battery.pylontech.us2000C;

import io.openems.edge.battery.protection.BatteryProtectionDefinition;
import io.openems.edge.battery.protection.force.ForceCharge;
import io.openems.edge.battery.protection.force.ForceDischarge;
import io.openems.edge.common.linecharacteristic.PolyLine;

public class PylontechUS2000CBatteryProtectionDefinition implements BatteryProtectionDefinition {

	private int initBmsMaxEverCharge;
	private int initBmsMaxEverDischarge;
	private double maxIncreasePerSecond;
	private volatile boolean chargeAllowed, dischargeAllowed;
	
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

	public void setInitialBmsMaxEverChargeCurrent( int i_value ) {
		initBmsMaxEverCharge = i_value;
	}

	@Override
	public int getInitialBmsMaxEverDischargeCurrent() {
		return initBmsMaxEverDischarge; // [A]
	}

	public void setInitialBmsMaxEverDischargeCurrent( int i_value ) {
		initBmsMaxEverDischarge = i_value; // [A]
	}

	@Override
	public PolyLine getChargeVoltageToPercent() {
			return PolyLine.create() //
					.addPoint(2000, 0.1) //
					.addPoint(3000, 0.2) //
					.addPoint(Math.nextUp(3000), 1) //
					.addPoint(3300, 1) //
					.addPoint(3450, 0.1) //
					.addPoint(3540, 0.01) //
					.addPoint(3580, 0) //
					.build();
	}

	@Override
	public PolyLine getDischargeVoltageToPercent() {
		return PolyLine.create() //
				.addPoint(2000, 0) //
				.addPoint(3000, 0) //
				.addPoint(3100, 0.1) //
				.addPoint(3200, 0.2) //
				.addPoint(3300, 1) //
				.build();
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

	public void setMaxIncreaseAmperePerSecond( int i_increase )
	{
		maxIncreasePerSecond = i_increase;
	}
	
	@Override
	public PolyLine getChargeSocToPercent() {
		return PolyLine.empty();
	}

	@Override
	public PolyLine getDischargeSocToPercent() {
		return PolyLine.empty();
	}

	void _setChargeAllowed( boolean _allowed )
	{
		chargeAllowed = _allowed;
	}
	@Override
	public boolean isChargeAllowed() {
		return chargeAllowed;
	}

	void _setDischargeAllowed( boolean _allowed )
	{
		dischargeAllowed = _allowed;
	}
	
	@Override
	public boolean isDischargeAllowed() {
		return dischargeAllowed;
	}
}
