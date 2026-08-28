package io.openems.edge.hycube.denkovi;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Denkovi 8 Relais Board", //
		description = "switches relais of Denkovi relais board by http commands. Access is handled in a separate HTTP-Server-Process")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "io0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Host", description = "Server location")
	String serverIp() default "localhost";

	@AttributeDefinition(name = "Port", description = "Server port")
	int serverPort() default 8101;

	@AttributeDefinition(name = "Number of output channels", description = "This many channels 'OutputX' are created.")
	int numberOfOutputs() default 8;

	String webconsole_configurationFactory_nameHint() default "Denkovi Relais Output Board [{id}]";

}
