package io.openems.edge.hycube.ess.statemachine;

import io.openems.edge.common.statemachine.AbstractContext;
import io.openems.edge.hycube.ess.Config;
import io.openems.edge.hycube.ess.HycubeEssImpl;

public class Context extends AbstractContext<HycubeEssImpl> {

	protected final Config config;

	public Context(HycubeEssImpl parent, Config config) {
		super(parent);
		this.config = config;
	}

}