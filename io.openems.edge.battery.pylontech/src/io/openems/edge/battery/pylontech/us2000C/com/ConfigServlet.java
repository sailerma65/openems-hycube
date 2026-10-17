package io.openems.edge.battery.pylontech.us2000C.com;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "US2000C servlet", 
    description = "US2000C info servlet"
)
@interface ConfigServlet {
    String id() default "us2000c.servlet";

   @AttributeDefinition(name = "Servlet Pfad", description = "Der HTTP-Pfad für dieses Servlet")
    String osgi_http_whiteboard_servlet_pattern() default "/system/us2000c/info";

	@AttributeDefinition(name = "US2000C battery id", description = "Battery-ID")
	String battery_id() default "battery0";

   boolean enabled() default true;
}
