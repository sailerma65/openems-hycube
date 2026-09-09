package io.openems.edge.battery.pylontech.us2000C;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.worker.AbstractWorker;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol;
import io.openems.edge.battery.pylontech.us2000C.com.SerialReadWrite;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.CMD_DESCRIPTORS;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.Frame;
import java.lang.annotation.*;
class PylontechProtocolWorker extends AbstractWorker{
	
	class FrameData
	{
		int address;
		CMD_DESCRIPTORS cmd;
		Frame frame;
		
		FrameData( int _address, CMD_DESCRIPTORS _descr, Frame _frame )
		{
			address = _address;
			cmd = _descr;
			frame = _frame;
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

	// single thr.
	private int pollAddress;
	
	// read
	private int pollMax;
	
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
	
	void setParallelDevices( int _devices )
	{
		pollMax = PylontechUS2000CBatteryImpl.PY_START_ADDRESS + _devices - 1;
	}
	
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
		
		commState = PYLONTECH_COMM_STATE.INITIALIZING;
		
		commDirection = PYLONTECH_COMM_DIR.REQUEST;
		
		pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
		
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
					
					switch( currentPollCycle )
					{
					case SCAN_SERIAL_NUMBER:
						 nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_MANUFACTURER_INFO;
						break;
					case SCAN_MANUFACTURER_INFO:
				        if( pollAddress < pollMax )
				        {
				        	pollAddress++;
				        	nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
				        }
				        else
				        {
				        	nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_ANALOG_VALUES;
				        	pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
				        	//m_valueCycles = PY_VALUE_CYCLES;
				        	pollErrorCount = 0;
				        }
				        break;
					case RUN_ANALOG_VALUES:
						// read values
						
						if( pollAddress < pollMax )
						{
							pollAddress++;
						}
						else
						{
							pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
							//m_valueCycles--;
							
							//if( m_valueCycles <= 0 )
							{
								nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_CMD_GET_ALARM_INFO;
							}
						}
				        break;
					case RUN_CMD_GET_ALARM_INFO:
						// read values
						
						if( pollAddress < pollMax )
						{
							pollAddress++;
						}
						else
						{
							pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
							nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_CMD_GET_MANAGEMENT_INFO;
						}
				        break;
					case RUN_CMD_GET_MANAGEMENT_INFO:
						// read values
						
						if( pollAddress < pollMax )
						{
							pollAddress++;
						}
						else
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
				if( pollErrorCount < PY_MAX_POLL_ERRORS )
				{
					pollErrorCount++;
				}
				else
				{
					pollErrorCount = 0;
					
					communicationError = true;
					
					serialConnection.handleError("poll error", ex );
					
					nextPollCycle = PYLONTECH_POLL_CYCLE.INACTIVE;
					
					currentPollCycle = PYLONTECH_POLL_CYCLE.INACTIVE;
				}
				switch( currentPollCycle  )
				{
				case SCAN_SERIAL_NUMBER:
				case SCAN_MANUFACTURER_INFO:
					pollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
					nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
					return;
				default:
					break;
				}
				commDirection = PYLONTECH_COMM_DIR.REQUEST;
			}
		}
		
		if( nextPollCycle != null )
		{
			synchronized (this) {
				pylontechPollCycle = nextPollCycle;
			}
		}
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
	
	public synchronized FrameData getNextFrame()
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