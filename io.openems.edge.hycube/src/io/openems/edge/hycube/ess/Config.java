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
	String alias() default "Hycube ESS System";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Connected Phase", description = "to which phase is the ESS connected?")
	SingleOrAllPhase phase() default SingleOrAllPhase.L1;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device.")
	int modbusUnitId() default 227;

	@AttributeDefinition(name = "Debug", description = "Enable debug mode?")
	boolean debugMode() default false;

	@AttributeDefinition(name = "Max Charge Power", description = "Maximum charge power in W")
	int maxChargePower() default 3000;

	@AttributeDefinition(name = "Max Discharge Power", description = "Maximum discharge power in W")
	int maxDischargePower() default 4600;

	@AttributeDefinition(name = "Start/stop behaviour?", description = "Should this Component be forced to start or stop?")
	StartStopConfig startStop() default StartStopConfig.START;

	@AttributeDefinition(name = "Read-Only mode", description = "Enables Read-Only mode")
	boolean readOnlyMode() default false;

	@AttributeDefinition(name = "Hycube Battery ID", description = "Battery-ID which the batteryinverter is connected to")
	String battery_id() default "battery0";

	@AttributeDefinition(name = "Denkovi Board ID", description = "Digital output board")
	String io_id() default "io0";

	@AttributeDefinition(name = "Battery switch pin", description = "Denkovi Board output pin to switch battery connection")
	int io_battery_pin() default 7;

	String webconsole_configurationFactory_nameHint() default "Hycube ESS [{id}]";

}