package io.openems.edge.battery.pylontech.us2000C;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.FormattingStyle;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;

import io.openems.common.worker.AbstractWorker;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol;
import io.openems.edge.battery.pylontech.us2000C.com.SerialReadWrite;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.AlarmInfo;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.CMD_DESCRIPTORS;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.Frame;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.FrameTimeoutException;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.ManagementInfo;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.ManufacturerInfo;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.ModuleValues;

class PylontechProtocolWorker extends AbstractWorker{
	class FrameData
	{
		int address;
		CMD_DESCRIPTORS cmd;
		Frame frame;
		
		Object data;
		Type dataType;
		
		FrameData( int _address, CMD_DESCRIPTORS _descr, Frame _frame )
		{
			address = _address;
			cmd = _descr;
			frame = _frame;
			
			switch( cmd )
			{
			case CMD_GET_SERIAL_NUMBER:
				data = PylontechSerialProtocol.parseModuleSerialNumber( frame.info() );
				dataType = String.class;
				break;
			case CMD_GET_MANUFACTURER_INFO:
				data = PylontechSerialProtocol.parseManufacturerInfo(frame.info());
				dataType = ManufacturerInfo.class;
				
				break;
			case CMD_GET_ALARM_INFO:
				data = PylontechSerialProtocol.parseAlarm(frame.info());
				dataType = AlarmInfo.class;
				break;
			case CMD_GET_ANALOG_VALUE:
				data = PylontechSerialProtocol.parseValuesSingle(frame.info());
				dataType = ModuleValues.class;
				
				break;
			case CMD_GET_MANAGEMENT_INFO:
				data = PylontechSerialProtocol.parseManagementInfo(frame.info());
				dataType = ManagementInfo.class;
				break;
			default:
				dataType = null;
				data = null;
				break;
			}
		}
		
		@SuppressWarnings("unchecked")
		public <TYPE> TYPE getData()
		{
			return ( TYPE )data;
		}
		
		public Type getType()
		{
			return dataType;
		}
	}
	
	enum PYLONTECH_COMM_STATE{
		INACTIVE,
		INITIALIZING,
		RUNNING
	}
	
	enum PYLONTECH_POLL_CYCLE{
		INACTIVE(null),
		SCAN_SERIAL_NUMBER( CMD_DESCRIPTORS.CMD_GET_SERIAL_NUMBER ),
		SCAN_MANUFACTURER_INFO( CMD_DESCRIPTORS.CMD_GET_MANUFACTURER_INFO ),
		RUN_ANALOG_VALUES( CMD_DESCRIPTORS.CMD_GET_ANALOG_VALUE ),
		RUN_CMD_GET_ALARM_INFO( CMD_DESCRIPTORS.CMD_GET_ALARM_INFO ),
		RUN_CMD_GET_MANAGEMENT_INFO( CMD_DESCRIPTORS.CMD_GET_MANAGEMENT_INFO );
		
		CMD_DESCRIPTORS descriptor;
		
		PYLONTECH_POLL_CYCLE( CMD_DESCRIPTORS descriptor )
		{
			this.descriptor = descriptor;
		}
	}
	
	enum PYLONTECH_COMM_DIR{
		REQUEST,
		RESPONSE
	}
	
	static class FrameKey
	{
		private int address;
		private CMD_DESCRIPTORS cmd;

		private FrameKey( int _address, CMD_DESCRIPTORS _descr )
		{
			address = _address;
			cmd = _descr;
		}

		@Override
		public int hashCode() {
			return address + cmd.ordinal() << 8; 
		}

		@Override
		public boolean equals(Object obj) {
			if( obj instanceof FrameKey key )
			{
				return key.address == address && key.cmd == cmd;
			}
			return false;
		}
		
		
	}

	private PylontechSerialProtocol pylontechAdapter;

	// sync
	private PYLONTECH_POLL_CYCLE pylontechPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
	
	private LinkedHashMap<FrameKey, FrameData> receivedFrames = new LinkedHashMap<>();

	private HashMap<FrameKey, FrameData> saveHashMap = new HashMap<>();

	private HashMap<Integer, CMD_DESCRIPTORS> cmdDescriptorMap = null;
	
	// single thr.
	private int pollAddress;
	
	private int pollMax;
	
	private int deviceCount = 0;
	
	private static final int PY_MAX_DEVICE_CHECK_CYCLES = 2;
	
	private int deviceCheckCycle = 0;
	
	private int[] deviceCheckResults = new int[ PY_MAX_DEVICE_CHECK_CYCLES ];
	
	// single thr.
	private PYLONTECH_COMM_DIR commDirection;
	
	private static final int PY_MAX_POLL_ERRORS = 4;

	// single thr.
	private int pollErrorCount = 0;

	private PYLONTECH_COMM_STATE commState = PYLONTECH_COMM_STATE.INACTIVE;
	
	
	private boolean activated = false;

	private volatile boolean communicationError;
	
	private SerialReadWrite serialConnection;
	
	private int waitFailCountDown = 0;
	
	private final Logger log = LoggerFactory.getLogger(PylontechProtocolWorker.class);
	
	void setSerialInterface( SerialReadWrite _connection )
	{
		serialConnection = _connection;
		pylontechAdapter = new PylontechSerialProtocol(serialConnection);
	}
	
	@Override
	public void activate(String _name, boolean _initiallyTriggerNextRun) {
		
		super.activate(_name, _initiallyTriggerNextRun);
		
		synchronized( this )
		{
		  activated = true;
		}
	}
	
	

	@Override
	public void deactivate() {
		synchronized( this )
		{
		  activated = false;
		  commState = PYLONTECH_COMM_STATE.INACTIVE;
		}
		super.deactivate();
	}

	public synchronized boolean isActivated()
	{
		return activated;
	}
	
	public synchronized boolean isRunning()
	{
		return isActivated() && commState == PYLONTECH_COMM_STATE.RUNNING;
	}
	
	public synchronized void startCommunication()
	{
		pylontechAdapter.startWork();
		
		communicationError = false;
		
		deviceCheckCycle = 0;
		
		commState = PYLONTECH_COMM_STATE.INITIALIZING;
		
		commDirection = PYLONTECH_COMM_DIR.REQUEST;
		
		pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
		
		pollMax = PylontechUS2000CBatteryImpl.PY_START_ADDRESS + PylontechUS2000CBatteryImpl.PY_MAX_DEVICES;
		
		startNextCycle();
	}
	
	public synchronized void startNextCycle()
	{
		if( pylontechPollCycle == PYLONTECH_POLL_CYCLE.INACTIVE  && commState != PYLONTECH_COMM_STATE.INACTIVE )
		{
			if( commState == PYLONTECH_COMM_STATE.INITIALIZING )
			{
				pylontechPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
			}
			else
			{
				pylontechPollCycle = PYLONTECH_POLL_CYCLE.RUN_ANALOG_VALUES;
			}
		}
	}
	
	private boolean checkDeviceCount()
	{
		int compare = deviceCheckResults[ 0 ];
		for( int i = 1; i < deviceCheckCycle; i++ )
		{
			if( deviceCheckResults[ i ] != compare )
			{
				return false;
			}
		}

		deviceCount = compare;
		
		pollMax = PylontechUS2000CBatteryImpl.PY_START_ADDRESS + deviceCount;
		
		return true;
	}
	
	@Override
	protected void forever() throws Throwable {
		if( waitFailCountDown > 0 && --waitFailCountDown > 0 )
		{
			return;
		}
		
		if( !serialConnection.isStarted() )
			return;
		
		PYLONTECH_POLL_CYCLE currentPollCycle;
		
		synchronized( this )
		{
			if( pylontechPollCycle == PYLONTECH_POLL_CYCLE.INACTIVE )
			{
				return;
			}

		    currentPollCycle = pylontechPollCycle;
		}
		
		PYLONTECH_POLL_CYCLE nextPollCycle = null;
		
		if( commDirection == PYLONTECH_COMM_DIR.REQUEST )
		{
			pylontechAdapter.clearReceiveBuffer();
			pylontechAdapter.sendCmdWithAddressInfo( pollAddress, currentPollCycle.descriptor );
			commDirection = PYLONTECH_COMM_DIR.RESPONSE;
		}
		else
		{
			try
			{
				Frame receivedFrame = pylontechAdapter.receiveOrWait();
				
				if( receivedFrame != null )
				{
					FrameKey key = new FrameKey( pollAddress, currentPollCycle.descriptor );
					FrameData data = new FrameData(pollAddress, currentPollCycle.descriptor, receivedFrame );
					
					synchronized (receivedFrames ) {
						receivedFrames.put(key, data);
					}
					
					synchronized( saveHashMap ) {
						saveHashMap.put(key, data);
					}
					
					switch( currentPollCycle )
					{
					case SCAN_SERIAL_NUMBER:
						nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_MANUFACTURER_INFO;
						break;
					case SCAN_MANUFACTURER_INFO: 
				        if( commState == PYLONTECH_COMM_STATE.INITIALIZING )
				        {
				        	pollAddress++;
				        	
				        	if( pollAddress < pollMax )
				        	{
				        		nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
				        		break;
				        	}
				        	else if( deviceCheckCycle < PY_MAX_DEVICE_CHECK_CYCLES )
				        	{
								deviceCheckResults[ deviceCheckCycle ] = pollMax - PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
								deviceCheckCycle++;
				        		nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
				        		pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
				        		break;

				        	}
				        	else if( !checkDeviceCount() )
				        	{
			        			throw new IOException("Error in device count detection");
				        	}
				        }
				        nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_ANALOG_VALUES;
				        pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
				        //m_valueCycles = PY_VALUE_CYCLES;
				        pollErrorCount = 0;
				        break;
					case RUN_ANALOG_VALUES:
						// read values
						
						pollAddress++;
						
						if( pollAddress >= pollMax )
						{
							pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
							nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_CMD_GET_ALARM_INFO;
						}
				        break;
					case RUN_CMD_GET_ALARM_INFO:
						// read values
						
						pollAddress++;

						if( pollAddress >= pollMax )
						{
							pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
							nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_CMD_GET_MANAGEMENT_INFO;
						}
				        break;
					case RUN_CMD_GET_MANAGEMENT_INFO:
						// read values
						
						pollAddress++;

						if( pollAddress >= pollMax )
						{
							pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
							nextPollCycle = PYLONTECH_POLL_CYCLE.INACTIVE;
							
							//m_valueCycles = PY_VALUE_CYCLES;
							pollErrorCount = 0;
							
							commState = PYLONTECH_COMM_STATE.RUNNING;
						}
				        break;
					case INACTIVE:
					default:
						break;
						
					}
					commDirection = PYLONTECH_COMM_DIR.REQUEST;
				}
			}
			catch( FrameTimeoutException fex )
			{
				if( commState == PYLONTECH_COMM_STATE.INITIALIZING )
				{
					if( deviceCheckCycle < PY_MAX_DEVICE_CHECK_CYCLES )
					{
						deviceCheckResults[ deviceCheckCycle ] = pollAddress - PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
						deviceCheckCycle++;
		        		nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
		        		pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
		        		
		        		if( deviceCheckCycle == PY_MAX_DEVICE_CHECK_CYCLES &&  checkDeviceCount() )
						{
					        nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_ANALOG_VALUES;
					        pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
					        pollErrorCount = 0;
		        		}
					}
					else
					{
						log.error( "checkDeviceCount() failed after FrameTimeoutException in state " + currentPollCycle, fex );
						
						nextPollCycle = checkErrorCount( nextPollCycle, currentPollCycle, fex );						
					}
					commDirection = PYLONTECH_COMM_DIR.REQUEST;
				}
			}
			catch( RuntimeException ex )
			{
				log.info( ex.getClass().getSimpleName() + " in state " + currentPollCycle, ex );

				commDirection = PYLONTECH_COMM_DIR.REQUEST;

				// pause for 4 thread cycles
				waitFailCountDown = 4;
			}
			catch( IOException ex )
			{
				log.error( ex.getClass().getSimpleName() + " in state " + currentPollCycle, ex );
				
				nextPollCycle = checkErrorCount( nextPollCycle, currentPollCycle, ex );
			}
		}
		
		if( nextPollCycle != null )
		{
			synchronized (this) {
				pylontechPollCycle = nextPollCycle;
			}
		}
	}

	private PYLONTECH_POLL_CYCLE checkErrorCount( PYLONTECH_POLL_CYCLE i_nextPollCycle, PYLONTECH_POLL_CYCLE i_currentPollCycle,Exception ex )
	{
		PYLONTECH_POLL_CYCLE result = i_nextPollCycle;
		
		if( pollErrorCount < PY_MAX_POLL_ERRORS )
		{
			pollErrorCount++;
		}
		else
		{
			pollErrorCount = 0;
			
			communicationError = true;
			
			serialConnection.handleError("poll error", ex );
			
			commDirection = PYLONTECH_COMM_DIR.REQUEST;

			return PYLONTECH_POLL_CYCLE.INACTIVE;
		}
		switch( i_currentPollCycle )
		{
		case SCAN_SERIAL_NUMBER:
		case SCAN_MANUFACTURER_INFO:
			pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
			return PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
		default:
			break;
		}
		commDirection = PYLONTECH_COMM_DIR.REQUEST;
		
		return result;
	}
	
	public boolean hasCommunicationError()
	{
		return communicationError;
	}
	
	public void quitCommunicationERror()
	{
		communicationError = false;
	}
	
	@Override
	public void activate(String name) {
		
		super.activate(name);
	}

	@Override
	protected int getCycleTime() {
		return 50;
	}
	
	public int getDeviceCount()
	{
		return deviceCount;
	}
	
	public FrameData getNextFrame()
	{
		synchronized( receivedFrames )
		{
			if( receivedFrames.isEmpty() )
			{
				return null;
			}
		
			Entry<FrameKey, FrameData> entry = receivedFrames.firstEntry();
		
			FrameKey receivedKey = entry.getKey();

			receivedFrames.remove(receivedKey);

			return entry.getValue();
		}
	}
	
	private CMD_DESCRIPTORS getCmdDescriptor( int cmd )
	{
		if( cmdDescriptorMap == null )
		{
			cmdDescriptorMap = new HashMap<Integer, PylontechSerialProtocol.CMD_DESCRIPTORS>();
		
			for(CMD_DESCRIPTORS cmdDescr : CMD_DESCRIPTORS.values() )
			{
				cmdDescriptorMap.put( cmdDescr.getCmd(), cmdDescr );
			}
		}
		
		return cmdDescriptorMap.get( cmd );
	}
	
	
	private Object getSavedDataObject( CMD_DESCRIPTORS descr, int address )
	{
		synchronized( saveHashMap )
		{
			FrameKey key = new FrameKey( address, descr );
		
	
			FrameData frameData = saveHashMap.get(key);
		
			if( frameData != null )
			{
				return frameData.data;
			}
		}
		return null;
	}
	
	public String getJsonData( int cmd, int address )
	{
			CMD_DESCRIPTORS descr = getCmdDescriptor(cmd);
			
			if( descr != null )
			{
				Object dataObj;
				
				if( address == -1 )
				{
					HashMap<Integer, Object> resultMap = new HashMap<Integer, Object>();

					dataObj = resultMap;

					for( int i = 0; i < deviceCount ; i++ )
					{
						Object result = getSavedDataObject( descr, PylontechUS2000CBatteryImpl.PY_START_ADDRESS + i );
						
						if( result != null )
						{
							resultMap.put( ( i + PylontechUS2000CBatteryImpl.PY_START_ADDRESS ), result );
						}
					}
				}
				else
				{
					dataObj = getSavedDataObject(descr, address);
				}
				
				if( dataObj != null )
				{
					Gson gson = new Gson();
					
					StringWriter swr = new StringWriter();
					
					JsonWriter jwr = new JsonWriter( swr );
					
					jwr.setFormattingStyle(FormattingStyle.PRETTY);
					
					jwr.setIndent("  ");
					
					gson.toJson( dataObj, dataObj.getClass(), jwr );
					
					return swr.toString();
				}
		}
		
		return "Error!";
	}
}