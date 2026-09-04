package io.openems.edge.battery.pylontech.us2000C.com;

import java.io.ByteArrayOutputStream;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bridge.modbus.api.Parity;
import io.openems.edge.bridge.modbus.api.Stopbit;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.test.TestUtils;

/**
 * Provides a service for connecting to, querying and writing to a Modbus/RTU
 * device.
 */
@Designate(ocd = ConfigSerialRW.class, factory = true)
@Component(//
		name = "Bridge.Serial.Read.Write", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class SerialReadWriteImpl extends AbstractOpenemsComponent implements SerialReadWrite, OpenemsComponent, StartStoppable{
	private final Logger log = LoggerFactory.getLogger(SerialReadWriteImpl.class);

	@Reference
	private ComponentManager componentManager;

	/** The configured Port-Name (e.g. '/dev/ttyUSB0' or 'COM3'). */
	private String portName = "";

	/** The configured Baudrate (e.g. 9600). */
	private int baudrate;

	/** The configured Databits (e.g. 8). */
	private int databits;

	/** The configured Stopbits. */
	private Stopbit stopbits;

	/** The configured parity. */
	private Parity parity;

	private SerialPort serialPort;
	
	private ByteArrayOutputStream byteCollector = new ByteArrayOutputStream();
	private boolean lineAvailable = false;

	public SerialReadWriteImpl() {
		super(
				OpenemsComponent.ChannelId.values(), //
				SerialReadWrite.ChannelId.values(), //
				StartStoppable.ChannelId.values() //
				);
	}
	@Activate
	protected void activate(ComponentContext context, ConfigSerialRW config) {
		super.activate(context, config.id(), config.alias(), config.enabled ());

		this.applyConfig(config);
	}

	@Modified
	private void modified(ComponentContext context, ConfigSerialRW config) {
		super.modified(context, config.id(), config.alias(), config.enabled() );

		this.applyConfig(config);
		
		closePort();
	}


	private void applyConfig(ConfigSerialRW config) {
		if( config.enabled() )
		{
			this.portName = config.portName();
		}
		else
		{
			this.portName = null;
		}
		this.baudrate = config.baudRate();
		this.databits = config.databits();
		this.stopbits = config.stopbits();
		this.parity = config.parity();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private void closePort()
	{
		if( serialPort.isOpen() )
		{
			serialPort.closePort();
		}
		serialPort = null;
	}
	
	@Override
	public void setStartStop(StartStop value) throws OpenemsNamedException {
		// We are not using _setStartStop() by purpose to avoid race conditions with not
		// setting the Channel immediately
		TestUtils.withValue(this, StartStoppable.ChannelId.START_STOP, switch (value) {
		case START, UNDEFINED -> StartStop.START;
		case STOP -> StartStop.STOP;
		});

		// Close existing Modbus Connection on STOP
		if (value == StartStop.STOP) {
			this.closePort();
		}
		
	}
	
	private boolean ensurePortIsOpen()
	{
		if( serialPort != null && serialPort.isOpen() )
		{
			return true;
		}
		else if( portName != null )
		{
			byteCollector.reset();
			lineAvailable = false;
			
			serialPort = SerialPort.getCommPort(portName);
			serialPort.setBaudRate(baudrate);
			serialPort.setNumDataBits(databits);
			serialPort.setNumStopBits( stopbits.getValue() );
			serialPort.setParity( parity.getValue() );
			
			serialPort.openPort();
			
			serialPort.setComPortTimeouts( SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0 );

			return serialPort.isOpen();
		}
		return false;
	}

	@Override
	public boolean isReadLineAvailable() {
		if( ensurePortIsOpen() )
		{
			while( !lineAvailable )
			{
				byte[] buffer = new byte[1];
			
				int bytesRead = serialPort.readBytes( buffer, 1 );
				
				if( bytesRead == 1 )
				{
					byteCollector.writeBytes(buffer);

					if( buffer[ 0 ] == '\r' || buffer[ 0 ] == '\r' )
					{
						lineAvailable = true;
					}
				}
				else
				{
					break;
				}
			}
			
		}
		return lineAvailable;
	}

	@Override
	public byte[] readLine( int i_maxTimeout ) {
		if( isReadLineAvailable() )
		{
			byte[] result = byteCollector.toByteArray();
			
			byteCollector.reset();
			
			lineAvailable = false;
			
			return result;
		}
		return null;
	}

	@Override
	public boolean writeLine( byte[] line) {
		if( ensurePortIsOpen() )
		{
			int written = serialPort.writeBytes( line, line.length );
			
			return written == line.length;
		}
		return false;
	}
}
