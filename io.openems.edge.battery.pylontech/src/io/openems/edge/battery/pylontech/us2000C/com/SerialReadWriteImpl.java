package io.openems.edge.battery.pylontech.us2000C.com;

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

/**
 * Provides a service for connecting to, querying and writing to a Modbus/RTU
 * device.
 */
@Designate(ocd = ConfigSerialRW.class, factory = true)
@Component(//
		name = "Serial.Read.Write", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class SerialReadWriteImpl extends AbstractOpenemsComponent implements SerialReadWrite, OpenemsComponent, StartStoppable{
	private final Logger log = LoggerFactory.getLogger(SerialReadWriteImpl.class);

	@Reference
	private ComponentManager componentManager;

	/** The configured Port-Name (e.g. '/dev/ttyUSB0' or 'COM3'). */
	private String portName = "";

	private String savedPortName = null;
	
	/** The configured Baudrate (e.g. 9600). */
	private int baudrate;

	/** The configured Databits (e.g. 8). */
	private int databits;

	/** The configured Stopbits. */
	private Stopbit stopbits;

	/** The configured parity. */
	private Parity parity;

	private SerialPort serialPort;
	
	private ConfigSerialRW config;
	
	private volatile int sumRead = 0, sumWrite = 0;
	
	public SerialReadWriteImpl() {
		super(
				OpenemsComponent.ChannelId.values(), //
				SerialReadWrite.ChannelId.values(), //
				StartStoppable.ChannelId.values() //
				);
	}
	@Activate
	protected void activate(ComponentContext context, ConfigSerialRW config) {
		this.config = config;

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
		closePort();
		super.deactivate();
	}

	private void closePort()
	{
		if( serialPort != null && serialPort.isOpen() )
		{
			serialPort.closePort();
		}
		serialPort = null;
	}
	
	@Override
	public void setStartStop(StartStop value) throws OpenemsNamedException {
		logDebug(this.log, "Serial open close: " + value );
		switch( value )
		{
		case StartStop.START, StartStop.UNDEFINED ->
		{
			if( ensurePortIsOpen() )
			{
				_setStartStop(StartStop.START);
				channel( SerialReadWrite.ChannelId.COMMUNICATION_FAILURE ).setNextValue( Boolean.FALSE );
			}
			else
			{
				_setStartStop(StartStop.UNDEFINED);
			}
		}
		case StartStop.STOP ->
		{
			closePort();
			_setStartStop(StartStop.STOP);
		}
		}
	}
	
	@Override
	public boolean ensurePortIsOpen()
	{
		if( serialPort != null && serialPort.isOpen() )
		{
			logDebug( this.log, "ensureOpen ok");
			return true;
		}
		else if( portName != null )
		{
			try
			{
				
				if( serialPort != null && portName.equals(savedPortName))
				{
					int err = serialPort.getLastErrorCode();
					serialPort.closePort();
				}
				else
				{
					serialPort = SerialPort.getCommPort(portName);
					savedPortName = portName;
				}
				serialPort.setBaudRate(baudrate);
				serialPort.setNumDataBits(databits);
				serialPort.setNumStopBits( stopbits.getValue() );
				serialPort.setParity( parity.getValue() );
				
				serialPort.setComPortTimeouts( SerialPort.TIMEOUT_NONBLOCKING, 0, 0 );
				
				serialPort.openPort();
				
				boolean isOpen = serialPort.isOpen();
				
				String errCode = "";
				
				if( !isOpen )
				{
					errCode = ", ErrorCode: " + serialPort.getLastErrorCode();
					
					serialPort.closePort();
					
					serialPort.openPort();
					
					isOpen = serialPort.isOpen();
				}
				
				logDebug( this.log, "ensureOpen: try to open: " + isOpen + errCode );

				channel( SerialReadWrite.ChannelId.BAD_PARAMETERS ).setNextValue( Boolean.FALSE );
				channel( SerialReadWrite.ChannelId.COMMUNICATION_FAILURE ).setNextValue( Boolean.FALSE );

				return serialPort.isOpen();
			}
			catch( Exception ex )
			{
				log.error("Exception opening port", ex);
				channel( SerialReadWrite.ChannelId.FAILURE_STRING ).setNextValue( ex.getMessage() );
				channel( SerialReadWrite.ChannelId.BAD_PARAMETERS ).setNextValue( Boolean.TRUE );
			}
		}
		return false;
	}

	@Override
	public int bytesAvailable()
	{
		return serialPort != null ? serialPort.bytesAvailable() : 0;
	}
	
	@Override
	public int readBytes( byte[] buffer, int length )
	{
		int result = serialPort != null ? serialPort.readBytes(buffer, length) : 0;
		
		sumRead += result;
		
		return result;
	}
	
	@Override
	public int writeBytes( byte[] buffer, int bytesToWrite  )
	{
		int result = serialPort != null ? serialPort.writeBytes(buffer, bytesToWrite ) : 0;
		
		sumWrite += result;
		
		return result;
	}

	@Override
	public boolean setComPortTimeouts(int newTimeoutMode, int newReadTimeout, int newWriteTimeout) {
		if( serialPort != null )
			return serialPort.setComPortTimeouts(newTimeoutMode, newReadTimeout, newWriteTimeout);
		return false;
	}
	
	public String debugLog() {
		int readBytes = sumRead;
		sumRead = 0;
		
		int writeBytes = sumWrite;
		sumWrite = 0;
		
		return new StringBuilder() //
				.append(getStartStop()) //
				.append("|Name:").append(portName) //
				.append("|R:").append(readBytes) //
				.append("|W:").append(writeBytes) //
				.toString();
	}

	/**
	 * Uses Info Log for further debug features.
	 */
	@Override
	protected void logDebug(Logger log, String message) {
		super.logDebug(log, message);
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}

	public void handleError( String context, Exception exc )
	{
		log.error( context, exc );
		channel( SerialReadWrite.ChannelId.FAILURE_STRING ).setNextValue( exc.getMessage() );
		channel( SerialReadWrite.ChannelId.COMMUNICATION_FAILURE ).setNextValue( Boolean.TRUE );
		closePort();
	}
}
