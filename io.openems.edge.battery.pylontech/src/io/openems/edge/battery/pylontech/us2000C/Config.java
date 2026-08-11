package io.openems.edge.battery.pylontech.us2000C;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.common.startstop.StartStopConfig;

@ObjectClassDefinition(//
		name = "Battery Pylontech US2000C", //
		description = "Battery implementation for Pylontech US2000C. Not tested for other Pylontech batteries.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "battery0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Start/stop behaviour?", description = "Should this Component be forced to start or stop?")
	StartStopConfig startStop() default StartStopConfig.AUTO;

	@AttributeDefinition(name = "Serial interface", description = "Serial interface identifier")
	String serialInterfaceId() default "";

	@AttributeDefinition(name = "Parallel Devices", description = "Number of devices in parallel")
	int devicesInParallel() default 4;

	String webconsole_configurationFactory_nameHint() default "Battery Pylontech US2000C [{id}]";
}
