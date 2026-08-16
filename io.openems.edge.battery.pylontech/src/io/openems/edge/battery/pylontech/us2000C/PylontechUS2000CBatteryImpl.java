package io.openems.edge.battery.pylontech.us2000C;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.DIRECT_1_TO_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.KEEP_NEGATIVE_AND_INVERT;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.chain;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.io.IOException;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OpenemsType;
import io.openems.common.worker.AbstractWorker;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.battery.protection.BatteryProtection;
import io.openems.edge.battery.pylontech.us2000C.com.Pylontech;
import io.openems.edge.battery.pylontech.us2000C.com.Pylontech.CMD_DESCRIPTORS;
import io.openems.edge.battery.pylontech.us2000C.com.Pylontech.Frame;
import io.openems.edge.battery.pylontech.us2000C.com.Pylontech.FrameTimeoutException;
import io.openems.edge.battery.pylontech.us2000C.com.Pylontech.ReadCursor;
import io.openems.edge.battery.pylontech.us2000C.com.Pylontech.UnexpectedStartOfFrame;
import io.openems.edge.battery.pylontech.us2000C.statemachine.Context;
import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine;
import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine.State;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.BitsWordElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.ChannelId.ChannelIdImpl;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Battery.PylontechUS2000C", //
		immediate = true, //
		configurationPolicy = REQUIRE)
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		TOPIC_CYCLE_AFTER_PROCESS_IMAGE })
public class PylontechUS2000CBatteryImpl extends AbstractOpenemsComponent implements 
		OpenemsComponent, Battery, EventHandler, StartStoppable, PylontechUS2000CBattery {

	// Beim Start: für alle Module 2 - 17 Abfrage 93: CMD_GET_SERIAL_NUMBER
	// Wenn Antwort (Timeout max. 1500ms) Abfrage 51: CMD_GET_MANUFACTURER_INFO
	
	// danach: Abfrage 66: CMD_GET_ANALOG_VALUE ca. 1x pro Sek.
	// Abfrage 68: CMD_GET_ALARM_INFO ca. alle 20s
	// Abfrage 146: CMD_GET_MANAGEMENT_INFO ca. alle 20s
	
	private static final int BATTERY_VOLTAGE = 48;

	public PylontechUS2000CBatteryImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				Battery.ChannelId.values(), //
				BatteryProtection.ChannelId.values(), //
				PylontechUS2000CBattery.ChannelId.values() //
		);
	}

	private final Logger log = LoggerFactory.getLogger(PylontechUS2000CBatteryImpl.class);

	@Reference
	protected ConfigurationAdmin cm;

	@Reference
	protected ComponentManager componentManager;

	/**
	 * Manages the {@link State}s of the StateMachine.
	 */
	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);

	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);

	private Config config = null;
	private BatteryProtection batteryProtection = null;

	private PylontechWorker m_worker = new PylontechWorker();
	
	private Pylontech m_pylontechAdapter;
	
	private String[] m_serialNumbers = new String[16];
	private Pylontech.ManufacturerInfo[] m_manufacturerInfos = new Pylontech.ManufacturerInfo[16];
	private Pylontech.AlarmInfo[] m_alarmInfos = new Pylontech.AlarmInfo[16];
	private Pylontech.ManagementInfo[] m_managementInfos = new Pylontech.ManagementInfo[16];
	private Pylontech.ModuleValues[] m_moduleValues = new Pylontech.ModuleValues[16];
	
	private static final int PY_START_ADDRESS = 2;
	
	private enum PYLONTECH_POLL_MODE{
		SCAN_SERIAL_NUMBER( CMD_DESCRIPTORS.CMD_GET_SERIAL_NUMBER ),
		SCAN_MANUFACTURER_INFO( CMD_DESCRIPTORS.CMD_GET_MANUFACTURER_INFO ),
		RUN_ANALOG_VALUES( CMD_DESCRIPTORS.CMD_GET_ANALOG_VALUE ),
		RUN_CMD_GET_ALARM_INFO( CMD_DESCRIPTORS.CMD_GET_ALARM_INFO ),
		RUN_CMD_GET_MANAGEMENT_INFO( CMD_DESCRIPTORS.CMD_GET_MANAGEMENT_INFO );
		
		CMD_DESCRIPTORS descriptor;
		
		PYLONTECH_POLL_MODE( CMD_DESCRIPTORS descriptor )
		{
			this.descriptor = descriptor;
		}
	}
	
	private enum PYLONTECH_COMM_MODE{
		REQUEST,
		RESPONSE
	}
	
	private static class FrameData
	{
		private int m_address;
		private CMD_DESCRIPTORS m_cmd;
		private Frame m_frame;
		
		private FrameData( int i_address, CMD_DESCRIPTORS i_descr, Frame i_frame )
		{
			m_address = i_address;
			m_cmd = i_descr;
			m_frame = i_frame;
		}
	}

	private static class FrameKey
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

	private LinkedHashMap<FrameKey, FrameData> m_receivedFrames = new LinkedHashMap<>();

	private int m_numberOfDevices;
	
	private class PylontechWorker extends AbstractWorker{
		
		private Pylontech m_pylontechAdapter;

		private PYLONTECH_POLL_MODE m_pylontechState = PYLONTECH_POLL_MODE.SCAN_SERIAL_NUMBER;
		
		private int m_pollAddress;
		
		private int m_pollMax;
		
		private PYLONTECH_COMM_MODE m_commMode;
		
		private static final int PY_VALUE_CYCLES = 20;
		
		private static final int PY_MAX_POLL_ERRORS = 4;

		private int m_valueCycles;
		
		private int m_pollErrorCount = 0;

		private volatile boolean m_communicationError;
		
		private void setSerialInterface( String i_interface )
		{
			m_pylontechAdapter = new Pylontech(i_interface);
		}

		private void setParallelDevices( int i_devices )
		{
			m_pollMax = PY_START_ADDRESS + i_devices - 1;
		}
		
		@Override
		protected void forever() throws Throwable {
			if( m_commMode == PYLONTECH_COMM_MODE.REQUEST )
			{
				m_pylontechAdapter.clearReceiveBuffer();
				m_pylontechAdapter.sendCmdWithAddressInfo( m_pollAddress, m_pylontechState.descriptor );
				
				m_commMode = PYLONTECH_COMM_MODE.RESPONSE;
			}
			else
			{
				try
				{
					Frame receivedFrame = m_pylontechAdapter.receiveOrWait();
					
					if( receivedFrame != null )
					{
						FrameKey key = new FrameKey( m_pollAddress, m_pylontechState.descriptor );
						FrameData data = new FrameData(m_pollAddress, m_pylontechState.descriptor, receivedFrame );
						
						synchronized (m_receivedFrames ) {
							m_receivedFrames.put(key, data);
						}
						
						switch( m_pylontechState )
						{
						case SCAN_SERIAL_NUMBER:
							m_pylontechState = PYLONTECH_POLL_MODE.SCAN_MANUFACTURER_INFO;
							break;
						case SCAN_MANUFACTURER_INFO:
					        if( m_pollAddress < m_pollMax )
					        {
					        	m_pollAddress++;
					        	m_pylontechState = PYLONTECH_POLL_MODE.SCAN_SERIAL_NUMBER;
					        }
					        else
					        {
					        	m_pylontechState = PYLONTECH_POLL_MODE.RUN_ANALOG_VALUES;
					        	m_pollAddress = PY_START_ADDRESS;
					        	m_valueCycles = PY_VALUE_CYCLES;
					        	m_pollErrorCount = 0;
					        }
					        break;
						case RUN_ANALOG_VALUES:
							// read values
							
							if( m_pollAddress < m_pollMax )
							{
								m_pollAddress++;
							}
							else
							{
								m_pollAddress = PY_START_ADDRESS;
								m_valueCycles--;
								
								if( m_valueCycles <= 0 )
								{
									m_pylontechState = PYLONTECH_POLL_MODE.RUN_CMD_GET_ALARM_INFO;
								}
							}
					        break;
						case RUN_CMD_GET_ALARM_INFO:
							// read values
							
							if( m_pollAddress < m_pollMax )
							{
								m_pollAddress++;
							}
							else
							{
								m_pollAddress = PY_START_ADDRESS;
								m_pylontechState = PYLONTECH_POLL_MODE.RUN_CMD_GET_MANAGEMENT_INFO;
							}
					        break;
						case RUN_CMD_GET_MANAGEMENT_INFO:
							// read values
							
							if( m_pollAddress < m_pollMax )
							{
								m_pollAddress++;
							}
							else
							{
								m_pollAddress = PY_START_ADDRESS;
								m_pylontechState = PYLONTECH_POLL_MODE.RUN_ANALOG_VALUES;
								m_valueCycles = PY_VALUE_CYCLES;
								m_pollErrorCount = 0;
							}
					        break;
							
						}
					}
				}
				catch( FrameTimeoutException | UnexpectedStartOfFrame | IOException ex )
				{
					log.error( ex.getClass().getSimpleName() + " in state " + m_pylontechState, ex );
					switch( m_pylontechState )
					{
					case SCAN_SERIAL_NUMBER:
					case SCAN_MANUFACTURER_INFO:
						initialize();
					default:
						break;
					}
					if( m_pollErrorCount < PY_MAX_POLL_ERRORS )
					{
						m_pollErrorCount++;
					}
					else
					{
						m_communicationError = true;
					}
				}
				m_commMode = PYLONTECH_COMM_MODE.REQUEST;
			}
		}

		private void initialize()
		{
			m_pollAddress = PY_START_ADDRESS;
			m_pylontechState = PYLONTECH_POLL_MODE.SCAN_SERIAL_NUMBER;
			m_commMode = PYLONTECH_COMM_MODE.REQUEST;
			m_communicationError = false;
		}

		public boolean hasCommunicationError()
		{
			return false;// TODO m_communicationError;
		}
		
		@Override
		public void activate(String name) {
			super.activate(name);
		}

		@Override
		protected int getCycleTime() {
			return 50;
		}
		
	}
	@Activate
	void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled() );

		m_worker.setParallelDevices( config.devicesInParallel() );
		m_worker.setSerialInterface( config.serialInterfaceId() );
		
		m_worker.activate(config.id());
		
		this.batteryProtection = BatteryProtection.create(this) //
				.applyBatteryProtectionDefinition(new PylontechUS2000CBatteryProtectionDefinition(),
						this.componentManager) //
				.build();
		
		Channel<Integer> numberOfDevicesChannel = this
				.channel(PylontechUS2000CBattery.ChannelId.SYSTEM_NUMBER_OF_PARALLEL_DEVICES);

		m_numberOfDevices = this.config.devicesInParallel();
		
		numberOfDevicesChannel.setNextValue( m_numberOfDevices );

	}

	
	@Deactivate
	protected void deactivate() {
		m_worker.deactivate();
		super.deactivate();
	}



	@Override
	public String debugLog() {
		return Battery.generateDebugLog(this, this.stateMachine);
	}

	@Override
	public void setStartStop(StartStop value) throws OpenemsNamedException {
		this.log.info("setStartStop called with value: " + value.toString());

		if (this.startStopTarget.getAndSet(value) != value) {
			// If the Start/Stop target is changed - (i.e the battery has been started from
			// outside) -> force the state machine into undefined (so that the state machine
			// will stop/start accordingly)
			this.stateMachine.forceNextState(State.UNDEFINED);
		}
	}

	@Override
	public StartStop getStartStopTarget() {
		return switch (this.config.startStop()) {
		case AUTO -> this.startStopTarget.get(); // read StartStop-Channel
		case START -> StartStop.START; // force START
		case STOP -> StartStop.STOP; // force STOP
		default -> {
			assert false : "Unexpected startStop value";
			yield StartStop.UNDEFINED; // can never happen
		}
		};
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
			-> 
		{
			try
			{
				this.applyChannelValues();
			}
			catch( Throwable ex )
			{
				ex.printStackTrace();
			}
			this.batteryProtection.apply(); 
		}
		case TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
			-> this.handleStateMachine();
		}
	}

	private void applyChannelValues() {
		boolean newSerialNumber = false;
		boolean newManufacturerInfo = false;
		boolean newAlarmInfo = false;
		boolean newModuleValue = false;
		boolean newManagementInfo = false;
		
		boolean communicationError = m_worker.hasCommunicationError();
		
		channel(PylontechUS2000CBattery.ChannelId.COMMUNICATION_ERROR).setNextValue(m_moduleValues);
		
		if( communicationError )
		{
			try {
				setStartStop(StartStop.STOP);
			} catch (OpenemsNamedException e) {
				// TODO Auto-generated catch block
				log.error( e.getMessage(), e);
			}
			return;
		}
		
		do {
			FrameData receivedData;
			FrameKey receivedKey;
			
			synchronized( m_receivedFrames )
			{
				if( m_receivedFrames.isEmpty() )
				{
					break;
				}
				
				Entry<FrameKey, FrameData> entry = m_receivedFrames.firstEntry();
				
				receivedData = entry.getValue();
				receivedKey = entry.getKey();

				m_receivedFrames.remove(receivedKey);
			}
			
			switch( receivedData.m_cmd )
			{
			case CMD_GET_SERIAL_NUMBER:
				String serialNumber = Pylontech.parseModuleSerialNumber( receivedData.m_frame.info() );
				
				m_serialNumbers[ receivedData.m_address - PY_START_ADDRESS ] = serialNumber;

				newSerialNumber = true;
				break;
			case CMD_GET_MANUFACTURER_INFO:
				Pylontech.ManufacturerInfo manufacturerInfo = Pylontech.parseManufacturerInfo(receivedData.m_frame.info());
				
				m_manufacturerInfos[ receivedData.m_address - PY_START_ADDRESS ] = manufacturerInfo;
				
				newManufacturerInfo = true;
				break;
			case CMD_GET_ALARM_INFO:
				Pylontech.AlarmInfo alarmInfo = Pylontech.parseAlarm(receivedData.m_frame.info());
				
				m_alarmInfos[ receivedData.m_address - PY_START_ADDRESS ] = alarmInfo;

				newAlarmInfo = true;
				break;
			case CMD_GET_ANALOG_VALUE:
				Pylontech.ModuleValues moduleValues = Pylontech.parseValuesSingle(receivedData.m_frame.info());

				m_moduleValues[ receivedData.m_address - PY_START_ADDRESS ] = moduleValues;

				newModuleValue = true;
				
				break;
			case CMD_GET_MANAGEMENT_INFO:
				Pylontech.ManagementInfo managementInfo = Pylontech.parseManagementInfo(receivedData.m_frame.info());

				m_managementInfos[ receivedData.m_address - PY_START_ADDRESS ] = managementInfo;

				newManagementInfo = true;
				break;
			default:
				break;
			}
		}
		while( true );
		
		if( ( newSerialNumber || newManufacturerInfo ) && m_serialNumbers[ 0 ] != null && m_manufacturerInfos[ 0 ] != null )
		{
			this.channel( PylontechUS2000CBattery.ChannelId.VERSION_STRING ).setNextValue( m_manufacturerInfos[0].softwareVersion() );
		}
		
		if( newModuleValue && infoAvailable( m_moduleValues ) )
		{
			double stateOfCharge = 0;
			double voltage = Double.MAX_VALUE;
			double current = 0;
			double capacity_Ah = 0.0;
			
			double minCellVoltage = Double.MAX_VALUE;
			double maxCellVoltage = 0.0;
			
			double minCellTemperature = Double.MAX_VALUE;
			double maxCellTemperature = 0.0;

			boolean charging = false;
			boolean discharging = false;
			
			for( int i = 0; i < m_numberOfDevices; i++ )
			{
				stateOfCharge += m_moduleValues[ i ].stateOfCharge();
				voltage = Math.min( m_moduleValues[ i ].voltage(), voltage );
				current = current + m_moduleValues[ i ].current();
				
				if( current > 0.1 )
				{
					charging = true;
				}
				else if( current < -0.1 )
				{
					discharging = true;
				}
				
				capacity_Ah = current + m_moduleValues[ i ].remainingCapacity();
				
				for( int j = 0; j < m_moduleValues[i].numberOfCells(); j++ )
				{
					minCellVoltage = Math.min( minCellVoltage, m_moduleValues[i].cellVoltages()[j]);
					maxCellVoltage = Math.max( maxCellVoltage, m_moduleValues[i].cellVoltages()[j]);
				}
				for( int j = 0; j < m_moduleValues[i].numberOfTemperatures() - 1 ; j++ )
				{
					minCellTemperature = Math.min( minCellTemperature, m_moduleValues[i].groupedCellsTemperatures()[j]);
					maxCellTemperature = Math.max( maxCellTemperature, m_moduleValues[i].groupedCellsTemperatures()[j]);
				}
				
				minCellTemperature = Math.min( minCellTemperature , m_moduleValues[ i ].averageBmsTemperature() );
				maxCellTemperature = Math.max( maxCellTemperature , m_moduleValues[ i ].averageBmsTemperature() );
			}
			
			if( charging && discharging )
			{
				charging = false;
			}
			
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_CHARGE_STATUS ).setNextValue( charging );
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_DISCHARGE_STATUS ).setNextValue( discharging );
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_IDLE_STATUS ).setNextValue( !discharging && !charging );
			
			stateOfCharge /= m_numberOfDevices;
			
			channel( Battery.ChannelId.SOC ).setNextValue(stateOfCharge * 100 );
			
			channel( Battery.ChannelId.VOLTAGE ).setNextValue(voltage);
			
			channel( Battery.ChannelId.CURRENT ).setNextValue(current);

			channel( Battery.ChannelId.CAPACITY ).setNextValue(capacity_Ah * BATTERY_VOLTAGE); 

			channel( Battery.ChannelId.MIN_CELL_TEMPERATURE ).setNextValue(minCellTemperature);

			channel( Battery.ChannelId.MAX_CELL_TEMPERATURE ).setNextValue(maxCellTemperature); 

			channel( Battery.ChannelId.MIN_CELL_VOLTAGE ).setNextValue(minCellVoltage * 1000.0 );

			channel( Battery.ChannelId.MAX_CELL_VOLTAGE ).setNextValue(maxCellVoltage * 1000.0 ); 
			
			
		}
		
		if( newManagementInfo && infoAvailable( m_managementInfos ) )
		{
			double chargeCurrent = 0;
			double dischargeCurrent = 0;
			
			double chargeVoltage = Double.MAX_VALUE;
			double dischargeVoltage = 0.0;

			boolean chargeEnable = true;
			boolean dichargeEnable = true;
			
			for( int i = 0; i < m_numberOfDevices; i++ )
			{
				chargeCurrent += m_managementInfos[ i ].chargeCurrentLimit();
				dischargeCurrent += m_managementInfos[ i ].dischargeCurrentLimit();
				
				chargeVoltage = Double.min( m_managementInfos[ i ].chargeVoltageLimit(), chargeVoltage );
				dischargeVoltage = Double.max( m_managementInfos[ i ].dischargeVoltageLimit(), dischargeVoltage );

				if( !m_managementInfos[ i ].chargeEnable() )
				{
					chargeEnable = false;
				}
				if( !m_managementInfos[ i ].dischargeEnable() )
				{
					dichargeEnable = false;
				}

			}

			if( !chargeEnable )
			{
				chargeCurrent = 0;
			}
			
			if( !dichargeEnable )
			{
				dischargeCurrent = 0;
			}

			channel( BatteryProtection.ChannelId.BP_CHARGE_BMS ).setNextValue(chargeCurrent);
			channel( BatteryProtection.ChannelId.BP_DISCHARGE_BMS ).setNextValue(dischargeCurrent);

			channel( BatteryProtection.ChannelId.BP_CHARGE_MAX_VOLTAGE ).setNextValue(chargeVoltage);
			channel( BatteryProtection.ChannelId.BP_DISCHARGE_MIN_VOLTAGE ).setNextValue(dischargeVoltage);
			
			channel( Battery.ChannelId.CHARGE_MAX_VOLTAGE ).setNextValue(chargeVoltage);
			channel( Battery.ChannelId.CHARGE_MAX_CURRENT ).setNextValue(chargeCurrent);

			channel( Battery.ChannelId.DISCHARGE_MIN_VOLTAGE ).setNextValue(dischargeVoltage);
			channel( Battery.ChannelId.DISCHARGE_MAX_CURRENT ).setNextValue(dischargeCurrent);
		}

		if( newAlarmInfo && infoAvailable( m_alarmInfos ) )
		{
			boolean highVoltage = false;
			boolean lowVoltage = false;
			boolean overVoltageProtection = false;
			boolean underVoltageProtection = false;

			boolean systemOverVoltage = false;
			boolean systemUnderVoltage = false;
			boolean systemOverVoltageProtection = false;
			boolean systemUnderVoltageProtection = false;

			boolean chargeOverCurrent = false;
			boolean chargeOverCurrentProtection = false;
			boolean dischargeOverCurrent = false;
			boolean dischargeOverCurrentProtection = false;
			
			boolean highTemp = false;
			boolean lowTemp = false;
			boolean overTempProtection = false;
			boolean underTempProtection = false;

			for( int i = 0; i < m_numberOfDevices; i++ )
			{
				for( int j = 0; j < m_alarmInfos[i].numberOfCells(); j++ )
				{
					highVoltage |= m_alarmInfos[ i ].cellAlarms()[ j ].cellHighVoltage();
					lowVoltage |= m_alarmInfos[ i ].cellAlarms()[ j ].cellLowVoltage();
					
					overVoltageProtection |= m_alarmInfos[ i ].cellAlarms()[ j ].cellOverVoltageProtection();
					underVoltageProtection |= m_alarmInfos[ i ].cellAlarms()[ j ].cellUnderVoltageProtection();
				}

				for( int j = 0; j < m_alarmInfos[i].numberOfSensors(); j++ )
				{
					highTemp |= m_alarmInfos[ i ].temperatureAlarms()[ j ].highTemperature();
					lowTemp |= m_alarmInfos[ i ].temperatureAlarms()[ j ].lowTemperature();

					overTempProtection |= m_alarmInfos[ i ].temperatureAlarms()[ j ].overTemperatureProtection();
					underTempProtection |= m_alarmInfos[ i ].temperatureAlarms()[ j ].underTemperatureProtection();
				}

				
				systemOverVoltage |= m_alarmInfos[i].systemVoltageAlarm().systemOverVoltage();
				systemUnderVoltage |= m_alarmInfos[i].systemVoltageAlarm().systemUnderVoltage();
				systemOverVoltageProtection |= m_alarmInfos[i].systemVoltageAlarm().overVoltageProtection();
				systemUnderVoltageProtection |= m_alarmInfos[i].systemVoltageAlarm().underVoltageProtection();
				
				chargeOverCurrent |= m_alarmInfos[ i ].currentAlarm().overCurrent();
				chargeOverCurrentProtection |= m_alarmInfos[ i ].currentAlarm().overCurrentProtection();

				dischargeOverCurrent |= m_alarmInfos[ i ].disChargeCurrentAlarm().overCurrent();
				dischargeOverCurrentProtection |= m_alarmInfos[ i ].disChargeCurrentAlarm().overCurrentProtection();
			}
			
			channel( PylontechUS2000CBattery.ChannelId.BATTERY_CELL_UNDER_VOLTAGE_PROTECTION ).setNextValue(underVoltageProtection);
			channel( PylontechUS2000CBattery.ChannelId.BATTERY_CELL_OVER_VOLTAGE_PROTECTION ).setNextValue(overVoltageProtection);
			channel( PylontechUS2000CBattery.ChannelId.BATTERY_CELL_LOW_VOLTAGE_WARNING ).setNextValue(lowVoltage);
			channel( PylontechUS2000CBattery.ChannelId.BATTERY_CELL_HIGH_VOLTAGE_WARNING ).setNextValue(highVoltage);

			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_OVER_VOLTAGE ).setNextValue(systemOverVoltage);
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_UNDER_VOLTAGE ).setNextValue(systemUnderVoltage);
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_OVER_VOLTAGE_PROTECTION ).setNextValue(systemOverVoltageProtection);
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_UNDER_VOLTAGE_PROTECTION ).setNextValue(systemUnderVoltageProtection);
			
			channel( PylontechUS2000CBattery.ChannelId.CHARGE_OVER_CURRENT_WARNING ).setNextValue(chargeOverCurrent);
			channel( PylontechUS2000CBattery.ChannelId.CHARGE_OVER_CURRENT_PROTECTION ).setNextValue(chargeOverCurrentProtection);
			channel( PylontechUS2000CBattery.ChannelId.DISCHARGE_OVER_CURRENT_WARNING ).setNextValue(dischargeOverCurrent);
			channel( PylontechUS2000CBattery.ChannelId.DISCHARGE_OVER_CURRENT_PROTECTION ).setNextValue(dischargeOverCurrentProtection);

			channel( PylontechUS2000CBattery.ChannelId.MODULE_HIGH_TEMPERATURE_WARNING ).setNextValue(highTemp);
			channel( PylontechUS2000CBattery.ChannelId.MODULE_OVER_TEMPERATURE_PROTECTION ).setNextValue(overTempProtection);
			channel( PylontechUS2000CBattery.ChannelId.MODULE_UNDER_TEMPERATURE_PROTECTION ).setNextValue(underTempProtection);
			channel( PylontechUS2000CBattery.ChannelId.MODULE_LOW_TEMPERATURE_WARNING ).setNextValue(lowTemp);
		}

		//TODO
		
		/*

SYSTEM_TEMPERATURE_WARNING
SYSTEM_IDLE_STATUS
SYSTEM_CHARGE_STATUS
SYSTEM_DISCHARGE_STATUS

CHARGE_UNDER_TEMPERATURE_PROTECTION
CHARGE_OVER_TEMPERATURE_PROTECTION
DISCHARGE_UNDER_TEMPERATURE_PROTECTION
DISCHARGE_OVER_TEMPERATURE_PROTECTION

MODULE_OVER_TEMPERATURE_PROTECTION
MODULE_HIGH_TEMPERATURE_WARNING

		 * 
		 */
	}
	
	private boolean infoAvailable( Object[] infos )
	{
		for( int i = 0; i < m_numberOfDevices; i++ )
		{
			if( infos[ i ] == null )
				return false;
		}
		return true;
	}
	
	/**
	 * Handles the state machine.
	 */
	private void handleStateMachine() {
		// Store the current state.
		this.channel(PylontechUS2000CBattery.ChannelId.STATE_MACHINE)
				.setNextValue(this.stateMachine.getCurrentState());

		// Initialize start-stop channel
		this._setStartStop(StartStop.UNDEFINED);

		var context = new Context(this);

		try {
			this.stateMachine.run(context);
			this.channel(PylontechUS2000CBattery.ChannelId.RUN_FAILED).setNextValue(false);
		} catch (OpenemsNamedException e) {
			this.channel(PylontechUS2000CBattery.ChannelId.RUN_FAILED).setNextValue(true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
		}
	}
}