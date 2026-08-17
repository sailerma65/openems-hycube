package io.openems.edge.hycube.ess.statemachine;

import io.openems.edge.batteryinverter.api.OffGridBatteryInverter.TargetGridMode;
import io.openems.edge.common.statemachine.AbstractContext;
import io.openems.edge.hycube.ess.Config;
import io.openems.edge.hycube.ess.HycubeEssImpl;

public class Context extends AbstractContext<HycubeEssImpl> {

	protected final Config config;
	protected final TargetGridMode targetGridMode;
	protected final int setActivePower;
	protected final int setReactivePower;

	public Context(HycubeEssImpl parent, Config config, TargetGridMode targetGridMode, int setActivePower,
			int setReactivePower) {
		super(parent);
		this.config = config;
		this.targetGridMode = targetGridMode;
		this.setActivePower = setActivePower;
		this.setReactivePower = setReactivePower;
	}

}