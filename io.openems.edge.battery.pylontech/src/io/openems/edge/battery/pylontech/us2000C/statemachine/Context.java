package io.openems.edge.battery.pylontech.us2000C.statemachine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.battery.pylontech.us2000C.PylontechUS2000CBattery;
import io.openems.edge.battery.pylontech.us2000C.Status;
import io.openems.edge.common.statemachine.AbstractContext;

public class Context extends AbstractContext<PylontechUS2000CBattery> {

	private final Logger log = LoggerFactory.getLogger(Context.class);

	public Context(PylontechUS2000CBattery parent) {
		super(parent);
	}

	/**
	 * Checks if battery is awake - i.e in charge / discharge modes.
	 * 
	 * @return boolean which says if battery is awake.
	 */
	protected boolean isBatteryAwake() {
		this.getParent().getSystemBasicStatus();
		
		Status status = this.getParent().getSystemBasicStatus();
		if (status == Status.CHARGE || status == Status.DISCHARGE || status == Status.IDLE) {
			return true;
		}
		return false;
	}


}