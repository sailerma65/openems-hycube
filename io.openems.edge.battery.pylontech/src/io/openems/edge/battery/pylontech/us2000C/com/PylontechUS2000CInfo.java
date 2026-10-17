package io.openems.edge.battery.pylontech.us2000C.com;

import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.battery.api.Battery;
import io.openems.edge.battery.pylontech.us2000C.PylontechUS2000CBattery;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.CMD_DESCRIPTORS;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.OpenemsComponent;

// 1. Definiert die Konfigurationsparameter für Felix
@Designate(ocd = ConfigServlet.class, factory = true)
// 2. Registriert die Klasse im OSGi Http-Whiteboard als Servlet
@Component(
    name = "US2000C.Info.Servlet",
    immediate = true,
    service = { Servlet.class, OpenemsComponent.class },
    configurationPolicy = ConfigurationPolicy.REQUIRE,
    property = {
            "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=org.osgi.service.http)",
    		   "felix.webconsole.label=batteryInfo",
    		    "felix.webconsole.title=Battery Info",
    		    "felix.webconsole.category=Status"    }
)
public class PylontechUS2000CInfo extends HttpServlet implements Servlet, OpenemsComponent {

    private static final long serialVersionUID = 1L;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = OPTIONAL)
    private Battery battery;

	private final Logger log = LoggerFactory.getLogger(PylontechUS2000CInfo.class);
	
	private PylontechUS2000CBattery pyBattery;
    
	private ConfigServlet config;
	
	private ComponentContext context;
	
	private String alias = "";
	
    // OpenEMS verlangt zwingend die Verwaltung von Channels (selbst wenn sie leer sind)
    private final List<Channel<?>> channels = new ArrayList<>();

    public PylontechUS2000CInfo() {
    	this.channels.add( OpenemsComponent.ChannelId.STATE.doc().createChannelInstance(this, OpenemsComponent.ChannelId.STATE ) );
    }
    @Activate
    void activate( ComponentContext _context, ConfigServlet _config) 
    {
    	this.config = _config;
    	this.context = _context;
    	
    	System.out.println("Servlet activate: " + this.toString() + ", " + this.hashCode() );
        
        pyBattery = ( PylontechUS2000CBattery )battery;
 
        if( pyBattery != null )
        {
        	// Wird aufgerufen, wenn du im Apache Felix Config Manager auf "Save" drückst
        	System.out.println("Servlet gestartet auf Pfad: " + config.osgi_http_whiteboard_servlet_pattern() + " with battery " + battery );
        }
    }

    @Deactivate
    void deactivate() {
        // Wird aufgerufen, wenn die Komponente gestoppt oder gelöscht wird
    }

    private static CMD_DESCRIPTORS[] cmdDescriptors = {CMD_DESCRIPTORS.CMD_GET_SERIAL_NUMBER,CMD_DESCRIPTORS.CMD_GET_MANUFACTURER_INFO,
    		CMD_DESCRIPTORS.CMD_GET_ALARM_INFO,	CMD_DESCRIPTORS.CMD_GET_ANALOG_VALUE, CMD_DESCRIPTORS.CMD_GET_MANAGEMENT_INFO};
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        String path = request.getPathInfo(); // Liefert den Pfad nach /mein-hauptmenue

        String contextPath = request.getContextPath();
        
        String uri = request.getRequestURI();
        
        log.info( "URI: " + uri + ", path: " + path + ", context: " + contextPath );

        if( contextPath != null && contextPath.length() > 0 )
        {
            URL resource = PylontechUS2000CInfo.class.getResource("ServletPage.template");
            
            String content;
            
            try (InputStream in = resource.openStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            
            String content2 = content.replace("{ANZAHL_GERAETE}", "ANZAHL_GERAETE = %d;".formatted( pyBattery.getParallelDevices() ) );

        	response.getWriter().write(content2);
        }
        else
        {
	    	// Deine Logik für HTTP-GET Anfragen
	        response.setContentType("application/json");
	        response.setCharacterEncoding("UTF-8");
	        
	        String addrStr = request.getParameter("addr");
	        
	        String cmdStr = request.getParameter("cmd");
	        
	        int cmd = -1;
	        
	        if( cmdStr == null )
	        {
	        	response.sendError(400);
	        	return;
	        }
	        else if( Character.isDigit( cmdStr.charAt(0)) )
	        {
	        	int cmdNr;
	        	
	            if( cmdStr.startsWith("0x"))
	            {
	            	cmdNr = Integer.parseInt(cmdStr.substring(2), 16 );
	            }
	            else
	            {
	            	cmdNr = Integer.parseInt(cmdStr);
	            }
	        	for( CMD_DESCRIPTORS descr : cmdDescriptors )
	        	{
	        		if( descr.getCmd() == cmdNr )
	        		{
	        			cmd = descr.getCmd();
	        		}
	        	}
	        }
	        else
	        {
	        	for( CMD_DESCRIPTORS descr : cmdDescriptors )
	        	{
	        		if( descr.getQueryName().equalsIgnoreCase(cmdStr))
	        		{
	        			cmd = descr.getCmd();
	        			break;
	        		}
	        	}
	        }
	        
	        if( cmd < 0 )
	        {
	        	response.sendError(400);
	        	return;
	        }
	        
	        int addr = addrStr == null ? 2 : Integer.parseInt(addrStr);
	        
	        if( addr == -1 || ( addr >= 2 && addr < 2 + pyBattery.getParallelDevices() ) )
	        {
	        	response.getWriter().write( pyBattery.getJsonInfo(cmd, addr));
	        }
	        else
	        {
	        	response.sendError(400);
	        	return;
	        }
        //
        }      
    }
    @Override
    public String id() {
        return this.config.id();
    }

    @Override
    public boolean isEnabled() {
        return this.config.enabled();
    }

    @Override
    public Channel<?> _channel(String channelId) {
        for (Channel<?> channel : this.channels) {
            if (channel.channelId().id().equals(channelId)) {
                return channel;
            }
        }
        return null;
    }

    @Override
    public Collection<Channel<?>> channels() {
        return channels;
    }

	@Override
	public String alias() {
		return alias;
	}

	@Override
	public ComponentContext getComponentContext() {
		return context;
	}
}
