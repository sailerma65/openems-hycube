package io.openems.edge.hycube.pvinverter;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.common.type.Phase.SingleOrAllPhase;

@ObjectClassDefinition(//
		name = "Hycube PV-Inverter", //
		description = "Implements the Hycube PV inverter (read only).")
public @interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "pvInverter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "Hycube PV Inverter";

	@AttributeDefinition(name = "Phase", description = "true, if three Inverters are configured for master-slave symmetric mode")
	SingleOrAllPhase phase() default SingleOrAllPhase.L1;

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Debug", description = "Enable debug mode?")
	boolean debugMode() default false;
	@AttributeDefinition(name = "Hycube ESS ID", description = "ESS-ID which the PV inverter is connected to")
	String ess_id() default "ess0";

	String webconsole_configurationFactory_nameHint() default "HYCUBE Hybrid Inverter (ESS){id}]";
}
