package io.openems.edge.hycube.ess;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.common.type.Phase.SingleOrAllPhase;

@ObjectClassDefinition(//
		name = "Hycube ESS", //
		description = "Hycube ESS system")
public @interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ess0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Connected Phase", description = "to which phase is the ESS connected?")
	SingleOrAllPhase phase() default SingleOrAllPhase.L1;

	@AttributeDefinition(name = "Debug", description = "Enable debug mode?")
	boolean debugMode() default false;

	@AttributeDefinition(name = "Read-Only mode", description = "Enables Read-Only mode")
	boolean readOnlyMode() default false;

	@AttributeDefinition(name = "Hycube BatteryInverter ID", description = "BatteryInverter-ID which the ess is connected to")
	String batteryInverter_id() default "batteryInverter0";

	String webconsole_configurationFactory_nameHint() default "Hycube ESS [{id}]";

}