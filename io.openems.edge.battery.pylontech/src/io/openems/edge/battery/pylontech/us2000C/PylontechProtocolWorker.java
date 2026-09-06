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
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.FrameTimeoutException;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.UnexpectedStartOfFrame;

class PylontechProtocolWorker extends AbstractWorker{
	
	class FrameData
	{
		int m_address;
		CMD_DESCRIPTORS m_cmd;
		Frame m_frame;
		
		FrameData( int i_address, CMD_DESCRIPTORS i_descr, Frame i_frame )
		{
			m_address = i_address;
			m_cmd = i_descr;
			m_frame = i_frame;
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
		private int m_address;
		private CMD_DESCRIPTORS m_cmd;

		private FrameKey( int i_address, CMD_DESCRIPTORS i_descr )
		{
			m_address = i_address;
			m_cmd = i_descr;
		}

		@Override
		public int hashCode() {
			return m_address + m_cmd.ordinal() << 8; 
		}

		@Override
		public boolean equals(Object obj) {
			if( obj instanceof FrameKey key )
			{
				return key.m_address == m_address && key.m_cmd == m_cmd;
			}
			return false;
		}
		
		
	}

	private PylontechSerialProtocol wrkPylontechAdapter;

	// sync
	private PYLONTECH_POLL_CYCLE wrkPylontechPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
	
	private LinkedHashMap<FrameKey, FrameData> wrkReceivedFrames = new LinkedHashMap<>();

	// single thr.
	private int wrkPollAddress;
	
	// read
	private int wrkPollMax;
	
	// single thr.
	private PYLONTECH_COMM_DIR wrkCommDirection;
	
	private static final int PY_MAX_POLL_ERRORS = 4;

	// single thr.
	private int wrkPollErrorCount = 0;

	private PYLONTECH_COMM_STATE wrkCommState = PYLONTECH_COMM_STATE.INACTIVE;
	
	
	private boolean wrkActivated = false;

	private volatile boolean wrkCommunicationError;
	
	private SerialReadWrite wrkSerialConnection;
	
	private final Logger log = LoggerFactory.getLogger(PylontechProtocolWorker.class);
	
	void setParallelDevices( int i_devices )
	{
		wrkPollMax = PylontechUS2000CBatteryImpl.PY_START_ADDRESS + i_devices - 1;
	}
	
	void setSerialInterface( SerialReadWrite connection )
	{
		wrkSerialConnection = connection;
		wrkPylontechAdapter = new PylontechSerialProtocol(wrkSerialConnection);
	}
	
	@Override
	public void activate(String name, boolean initiallyTriggerNextRun) {
		
		super.activate(name, initiallyTriggerNextRun);
		
		synchronized( this )
		{
		  wrkActivated = true;
		}
	}
	
	

	@Override
	public void deactivate() {
		synchronized( this )
		{
		  wrkActivated = false;
		  wrkCommState = PYLONTECH_COMM_STATE.INACTIVE;
		}
		super.deactivate();
	}

	public synchronized boolean isActivated()
	{
		return wrkActivated;
	}
	
	public synchronized boolean isRunning()
	{
		return isActivated() && wrkCommState == PYLONTECH_COMM_STATE.RUNNING;
	}
	
	public synchronized void startCommunication()
	{
		wrkPylontechAdapter.startWork();
		
		wrkCommunicationError = false;
		
		wrkCommState = PYLONTECH_COMM_STATE.INITIALIZING;
		
		wrkCommDirection = PYLONTECH_COMM_DIR.REQUEST;
		
		wrkPollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
		
		startNextCycle();
	}
	
	public synchronized void startNextCycle()
	{
		if( wrkPylontechPollCycle == PYLONTECH_POLL_CYCLE.INACTIVE  && wrkCommState != PYLONTECH_COMM_STATE.INACTIVE )
		{
			if( wrkCommState == PYLONTECH_COMM_STATE.INITIALIZING )
			{
				wrkPylontechPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
			}
			else
			{
				wrkPylontechPollCycle = PYLONTECH_POLL_CYCLE.RUN_ANALOG_VALUES;
			}
		}
	}
	
	@Override
	protected void forever() throws Throwable {
		if( !wrkSerialConnection.isStarted() )
			return;
		
		PYLONTECH_POLL_CYCLE currentPollCycle;
		
		synchronized( this )
		{
			if( wrkPylontechPollCycle == PYLONTECH_POLL_CYCLE.INACTIVE )
			{
				return;
			}

		    currentPollCycle = wrkPylontechPollCycle;
		}
		
		PYLONTECH_POLL_CYCLE nextPollCycle = null;
		
		if( wrkCommDirection == PYLONTECH_COMM_DIR.REQUEST )
		{
			wrkPylontechAdapter.clearReceiveBuffer();
			wrkPylontechAdapter.sendCmdWithAddressInfo( wrkPollAddress, currentPollCycle.descriptor );
			wrkCommDirection = PYLONTECH_COMM_DIR.RESPONSE;
		}
		else
		{
			try
			{
				Frame receivedFrame = wrkPylontechAdapter.receiveOrWait();
				
				if( receivedFrame != null )
				{
					FrameKey key = new FrameKey( wrkPollAddress, currentPollCycle.descriptor );
					FrameData data = new FrameData(wrkPollAddress, currentPollCycle.descriptor, receivedFrame );
					
					synchronized (wrkReceivedFrames ) {
						wrkReceivedFrames.put(key, data);
					}
					
					switch( currentPollCycle )
					{
					case SCAN_SERIAL_NUMBER:
						 nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_MANUFACTURER_INFO;
						break;
					case SCAN_MANUFACTURER_INFO:
				        if( wrkPollAddress < wrkPollMax )
				        {
				        	wrkPollAddress++;
				        	nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
				        }
				        else
				        {
				        	nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_ANALOG_VALUES;
				        	wrkPollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
				        	//m_valueCycles = PY_VALUE_CYCLES;
				        	wrkPollErrorCount = 0;
				        }
				        break;
					case RUN_ANALOG_VALUES:
						// read values
						
						if( wrkPollAddress < wrkPollMax )
						{
							wrkPollAddress++;
						}
						else
						{
							wrkPollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
							//m_valueCycles--;
							
							//if( m_valueCycles <= 0 )
							{
								nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_CMD_GET_ALARM_INFO;
							}
						}
				        break;
					case RUN_CMD_GET_ALARM_INFO:
						// read values
						
						if( wrkPollAddress < wrkPollMax )
						{
							wrkPollAddress++;
						}
						else
						{
							wrkPollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
							nextPollCycle = PYLONTECH_POLL_CYCLE.RUN_CMD_GET_MANAGEMENT_INFO;
						}
				        break;
					case RUN_CMD_GET_MANAGEMENT_INFO:
						// read values
						
						if( wrkPollAddress < wrkPollMax )
						{
							wrkPollAddress++;
						}
						else
						{
							wrkPollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
							nextPollCycle = PYLONTECH_POLL_CYCLE.INACTIVE;
							
							//m_valueCycles = PY_VALUE_CYCLES;
							wrkPollErrorCount = 0;
							
							wrkCommState = PYLONTECH_COMM_STATE.RUNNING;
						}
				        break;
					case INACTIVE:
					default:
						break;
						
					}
					wrkCommDirection = PYLONTECH_COMM_DIR.REQUEST;
				}
			}
			catch( FrameTimeoutException | UnexpectedStartOfFrame | IOException ex )
			{
				log.error( ex.getClass().getSimpleName() + " in state " + currentPollCycle, ex );
				if( wrkPollErrorCount < PY_MAX_POLL_ERRORS )
				{
					wrkPollErrorCount++;
				}
				else
				{
					wrkPollErrorCount = 0;
					
					wrkCommunicationError = true;
					
					wrkSerialConnection.handleError("poll error", ex );
					
					nextPollCycle = PYLONTECH_POLL_CYCLE.INACTIVE;
					
					currentPollCycle = PYLONTECH_POLL_CYCLE.INACTIVE;
				}
				switch( currentPollCycle  )
				{
				case SCAN_SERIAL_NUMBER:
				case SCAN_MANUFACTURER_INFO:
					wrkPollAddress = PylontechUS2000CBatteryImpl.PY_START_ADDRESS;
					nextPollCycle = PYLONTECH_POLL_CYCLE.SCAN_SERIAL_NUMBER;
					return;
				default:
					break;
				}
				wrkCommDirection = PYLONTECH_COMM_DIR.REQUEST;
			}
		}
		
		if( nextPollCycle != null )
		{
			synchronized (this) {
				wrkPylontechPollCycle = nextPollCycle;
			}
		}
	}

	public boolean hasCommunicationError()
	{
		return wrkCommunicationError;
	}
	
	public void quitCommunicationERror()
	{
		wrkCommunicationError = false;
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
		if( wrkReceivedFrames.isEmpty() )
		{
			return null;
		}
		
		Entry<FrameKey, FrameData> entry = wrkReceivedFrames.firstEntry();
		
		FrameKey receivedKey = entry.getKey();

		wrkReceivedFrames.remove(receivedKey);

		return entry.getValue();
	}
}