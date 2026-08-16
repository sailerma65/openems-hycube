package io.openems.edge.hycube.pvinverter;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.common.types.MeterType;
import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.common.type.Phase.SingleOrAllPhase;
import io.openems.edge.hycube.enums.DeviceType;

@ObjectClassDefinition(//
		name = "Hycube PV-Inverter RO", //
		description = "Implements the Hycube PV inverter (read only).")
public @interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "pvInverter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "Hycube Hybrid Inverter (ESS)";

	@AttributeDefinition(name = "Phase", description = "true, if three Inverters are configured for master-slave symmetric mode")
	SingleOrAllPhase phase() default SingleOrAllPhase.L1;

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Debug", description = "Enable debug mode?")
	boolean debugMode() default false;
	@AttributeDefinition(name = "Hycube Battery Inverter ID", description = "Battery-ID which the batteryinverter is connected to")
	String batteryInverter_id() default "batteryInverter0";

	String webconsole_configurationFactory_nameHint() default "HYCUBE Hybrid Inverter (ESS){id}]";
}
