package io.openems.edge.battery.pylontech.us2000C;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.battery.protection.BatteryProtection;
import io.openems.edge.battery.pylontech.us2000C.PylontechProtocolWorker.FrameData;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol.CMD_DESCRIPTORS;
import io.openems.edge.battery.pylontech.us2000C.com.SerialReadWrite;
import io.openems.edge.battery.pylontech.us2000C.statemachine.Context;
import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine;
import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine.State;
import io.openems.edge.common.channel.BooleanDoc;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.internal.AbstractDoc;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Battery.PylontechUS2000C", //
		immediate = true, //
		configurationPolicy = REQUIRE,
		service = { 
				Battery.class,          // <-- ZWINGEND ERFORDERLICH für Core.Sum
				OpenemsComponent.class,       // Basisschnittstelle
				EventHandler.class
		})
@GenerateTargetsFromReferences("serialConnection")
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		TOPIC_CYCLE_AFTER_PROCESS_IMAGE })
public class PylontechUS2000CBatteryImpl extends AbstractOpenemsComponent implements 
		OpenemsComponent, Battery, EventHandler, StartStoppable, PylontechUS2000CBattery {

	private static final String PY_HV_WARNING = "PY_HV_WARNING";

	private static final String PY_FULLY_CHARGED = "PY_FULLY_CHARGED";

	private static final String PY_DIS_CHARGING = "PY_DISCHARGING";

	private static final String PY_CHARGING = "PY_CHARGING";

	private static final String PY_CHARGE_MOSFET = "PY_CHARGE_MOSFET";

	private static final String PY_DISCHG_MOSFET = "PY_DISCHARGE_MOSFET";

	private static final String PY_USE_BAT_POWER = "PY_USE_BAT_POWER";

	// Beim Start: für alle Module 2 - 17 Abfrage 93: CMD_GET_SERIAL_NUMBER
	// Wenn Antwort (Timeout max. 1500ms) Abfrage 51: CMD_GET_MANUFACTURER_INFO
	
	// danach: Abfrage 66: CMD_GET_ANALOG_VALUE ca. 1x pro Sek.
	// Abfrage 68: CMD_GET_ALARM_INFO ca. alle 20s
	// Abfrage 146: CMD_GET_MANAGEMENT_INFO ca. alle 20s
	
	private static final String PY_CURRENT = "PY_CURRENT";

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

	@Reference( cardinality = ReferenceCardinality.MANDATORY, policyOption = ReferencePolicyOption.GREEDY, 
			target = "(&(id=${config.serialInterfaceId})(enabled=true))" )
	protected SerialReadWrite serialConnection;
	
	@Reference
	protected ConfigurationAdmin cm;

	@Reference
	protected ComponentManager componentManager;
	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);

	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);

	private Config config = null;
	private BatteryProtection batteryProtection = null;

	private PylontechProtocolWorker m_worker = new PylontechProtocolWorker();
	
	private String[] m_serialNumbers = new String[16];
	private PylontechSerialProtocol.ManufacturerInfo[] m_manufacturerInfos = new PylontechSerialProtocol.ManufacturerInfo[16];
	private PylontechSerialProtocol.AlarmInfo[] m_alarmInfos = new PylontechSerialProtocol.AlarmInfo[16];
	private PylontechSerialProtocol.ManagementInfo[] m_managementInfos = new PylontechSerialProtocol.ManagementInfo[16];
	private PylontechSerialProtocol.ModuleValues[] m_moduleValues = new PylontechSerialProtocol.ModuleValues[16];
	
	static final int PY_START_ADDRESS = 2;
	
	static final int PY_MAX_DEVICES = 16;
	
	private int m_numberOfDevices = 0;
	
	private Long m_lastDataReceiveTime = null;

	private AbstractDoc<Integer> m_currentDoc = 
			new IntegerDoc().unit(Unit.MILLIAMPERE).persistencePriority( PersistencePriority.HIGH).accessMode(AccessMode.READ_ONLY);

	private AbstractDoc<Boolean> m_stateDoc = 
			new BooleanDoc().persistencePriority( PersistencePriority.HIGH).accessMode(AccessMode.READ_ONLY);

	private HashMap<Integer, IntegerReadChannel> m_currentChannels = new HashMap<Integer, IntegerReadChannel>(); 

	private HashMap<String, BooleanReadChannel> m_statesPerModule = new HashMap<String, BooleanReadChannel>();
	
	private final PylontechUS2000CBatteryProtectionDefinition protectionDef = new PylontechUS2000CBatteryProtectionDefinition( 0, 0, 0 );

	private double[] m_minCellVoltageOfModule = new double[ PY_MAX_DEVICES ];

	private double[] m_maxCellVoltageOfModule  = new double[ PY_MAX_DEVICES ];

	@Activate
	void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled() );

		m_worker.setSerialInterface(serialConnection);
		
		m_worker.activate( config.id() + "Worker" );

		this.batteryProtection = BatteryProtection.create(this) //
				.applyBatteryProtectionDefinition(protectionDef,
						this.componentManager) //
				.build();
		
		Channel<Integer> numberOfDevicesChannel = this
				.channel(PylontechUS2000CBattery.ChannelId.SYSTEM_NUMBER_OF_PARALLEL_DEVICES);

		numberOfDevicesChannel.setNextValue( m_numberOfDevices );
	}

	
	@Deactivate
	protected void deactivate() {
		m_worker.deactivate();
		super.deactivate();
	}



	@Override
	public String debugLog() {
		super.debugLog();
		return Battery.generateDebugLog(this, this.stateMachine);
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

	@Override
	public void setStartStop(StartStop value) throws OpenemsNamedException {
		this.log.info("setStartStop called with value: " + value.toString());

		StartStop newValue = startStopTarget.getAndSet(value);
		
		if (newValue != value) {
			// If the Start/Stop target is changed - (i.e the battery has been started from
			// outside) -> force the state machine into undefined (so that the state machine
			// will stop/start accordingly)
			this.stateMachine.forceNextState(State.UNDEFINED);
		}
	}

	public void startCommunication() 
	{
		try
		{
			serialConnection.setStartStop( StartStop.START );

			m_worker.startCommunication();
		}
		catch( OpenemsNamedException ex )
		{
			this.stateMachine.forceNextState(State.UNDEFINED);
			log.error("Error in startCommunication", ex);
		}
		
	}

	public boolean checkCommunication()
	{
		String message = new StringBuilder().append("WRunning:").append(m_worker.isRunning()).append("|WCommError").append(m_worker.hasCommunicationError()).toString();
		
		logDebug(this.log, message );
		
		
		
		return !m_worker.hasCommunicationError() && m_worker.isRunning();
	}
	
	public void stopCommunication()
	{
		try
		{
			serialConnection.setStartStop( StartStop.STOP );
		}
		catch( OpenemsNamedException ex )
		{
			log.error("Error in startCommunication", ex);
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

	@SuppressWarnings("unchecked")
	private <T> Channel<T> addChannelImpl( String name, AbstractDoc<T> doc )
	{
		return ( Channel<T> )this.addChannel( new io.openems.edge.common.channel.ChannelId()
		{
				@Override
				public String name() {
					return name;
				}

				@Override
				public Doc doc() {
					return doc;
				}
		
		} );
	}
	
	private void applyChannelValues() {
		boolean newSerialNumber = false;
		boolean newManufacturerInfo = false;
		boolean newAlarmInfo = false;
		boolean newModuleValue = false;
		boolean newManagementInfo = false;
		
		if( m_worker.getDeviceCount() != m_numberOfDevices )
		{
			m_numberOfDevices = m_worker.getDeviceCount();
			
			int _initBmsMaxEverCharge = m_numberOfDevices * 25;
			int _initBmsMaxEverDischarge = m_numberOfDevices * 25;
			int _maxIncreasePerSecond = m_numberOfDevices * 5;
			
			protectionDef.setInitialBmsMaxEverChargeCurrent(_initBmsMaxEverCharge);
			protectionDef.setInitialBmsMaxEverDischargeCurrent(_initBmsMaxEverDischarge);
			protectionDef.setMaxIncreaseAmperePerSecond(_maxIncreasePerSecond);
			
			this.batteryProtection = BatteryProtection.create(this) //
					.applyBatteryProtectionDefinition(protectionDef,
							this.componentManager) //
					.build();
			
			for( int i = 0; i < m_numberOfDevices; i++ )
			{
				IntegerReadChannel currentChannel = m_currentChannels.get(i);
				
				String name;
				
				if( currentChannel == null )
				{
					name = PY_CURRENT + i;
					
					currentChannel = ( IntegerReadChannel )addChannelImpl( name, m_currentDoc );
				}
				
				name = PY_USE_BAT_POWER + i;
				
				if( !m_statesPerModule.containsKey(name) )
				{
					BooleanReadChannel ch = ( BooleanReadChannel )addChannelImpl(name, m_stateDoc);
					
					m_statesPerModule.put(name, ch );
				}
				
				name = PY_DISCHG_MOSFET + i;
				
				if( !m_statesPerModule.containsKey(name) )
				{
					BooleanReadChannel ch = ( BooleanReadChannel )addChannelImpl(name, m_stateDoc);
					
					m_statesPerModule.put(name, ch );
				}
				
				name = PY_CHARGE_MOSFET + i;
				
				if( !m_statesPerModule.containsKey(name) )
				{
					BooleanReadChannel ch = ( BooleanReadChannel )addChannelImpl(name, m_stateDoc);
					
					m_statesPerModule.put(name, ch );
				}
				
				name = PY_CHARGING + i;
				
				if( !m_statesPerModule.containsKey(name) )
				{
					BooleanReadChannel ch = ( BooleanReadChannel )addChannelImpl(name, m_stateDoc);
					
					m_statesPerModule.put(name, ch );
				}
				
				name = PY_DIS_CHARGING + i;
				
				if( !m_statesPerModule.containsKey(name) )
				{
					BooleanReadChannel ch = ( BooleanReadChannel )addChannelImpl(name, m_stateDoc);
					
					m_statesPerModule.put(name, ch );
				}

				name = PY_FULLY_CHARGED + i;
				
				if( !m_statesPerModule.containsKey(name) )
				{
					BooleanReadChannel ch = ( BooleanReadChannel )addChannelImpl(name, m_stateDoc);
					
					m_statesPerModule.put(name, ch );
				}

				name = PY_HV_WARNING + i;
				
				if( !m_statesPerModule.containsKey(name) )
				{
					BooleanReadChannel ch = ( BooleanReadChannel )addChannelImpl(name, m_stateDoc);
					
					m_statesPerModule.put(name, ch );
				}
			}
		}
		
		boolean communicationError = m_worker.hasCommunicationError();
		
		boolean saveTimeStamp = false;
		
		channel(PylontechUS2000CBattery.ChannelId.COMMUNICATION_ERROR).setNextValue(communicationError);

		if( stateMachine.getCurrentState() == State.RUNNING && m_lastDataReceiveTime != null )
		{
			if( System.currentTimeMillis() - m_lastDataReceiveTime > 60000l )
			{
				log.error( "Battery receive data timeout");
				communicationError = true;
			}
		}
		else
		{
			m_lastDataReceiveTime = null;
		}
		
		if( communicationError )
		{
			try {
				State current = stateMachine.getCurrentState();
				
				if( current == State.INIT_COMM || current == State.GO_RUNNING || current == State.RUNNING )
				{
					setStartStop(StartStop.UNDEFINED);
					m_worker.quitCommunicationERror();
				}
			} catch (OpenemsNamedException e) {
				log.error( e.getMessage(), e);
			}
			return;
		}
		
		FrameData receivedData;
		
		while( ( receivedData = m_worker.getNextFrame() ) != null ) {
			
			switch( receivedData.cmd )
			{
			case PylontechSerialProtocol.CMD_KEY_GET_SERIAL_NUMBER:
				String serialNumber = receivedData.getData();
				
				m_serialNumbers[ receivedData.address - PY_START_ADDRESS ] = serialNumber;
				
				newSerialNumber = true;
				break;
			case PylontechSerialProtocol.CMD_KEY_GET_MANUFACTURER_INFO:
				PylontechSerialProtocol.ManufacturerInfo manufacturerInfo = receivedData.getData();
				
				m_manufacturerInfos[ receivedData.address - PY_START_ADDRESS ] = manufacturerInfo;
				
				newManufacturerInfo = true;
				break;
			case PylontechSerialProtocol.CMD_KEY_GET_ALARM_INFO:
				PylontechSerialProtocol.AlarmInfo alarmInfo = receivedData.getData();
				
				m_alarmInfos[ receivedData.address - PY_START_ADDRESS ] = alarmInfo;

				newAlarmInfo = true;
				break;
			case PylontechSerialProtocol.CMD_KEY_GET_ANALOG_VALUE:
				PylontechSerialProtocol.ModuleValues moduleValues = receivedData.getData();

				m_moduleValues[ receivedData.address - PY_START_ADDRESS ] = moduleValues;

				newModuleValue = true;
				
				break;
			case PylontechSerialProtocol.CMD_KEY_GET_MANAGEMENT_INFO:
				PylontechSerialProtocol.ManagementInfo managementInfo = receivedData.getData();

				m_managementInfos[ receivedData.address - PY_START_ADDRESS ] = managementInfo;

				newManagementInfo = true;
				break;
			default:
				break;
			}
		}
		
		if( ( newSerialNumber || newManufacturerInfo ) && m_serialNumbers[ 0 ] != null && m_manufacturerInfos[ 0 ] != null )
		{
			this.channel( PylontechUS2000CBattery.ChannelId.VERSION_STRING ).setNextValue( m_manufacturerInfos[0].softwareVersion() );
		}
		
		if( newModuleValue && infoAvailable( m_moduleValues ) )
		{
			double minStateOfCharge = Double.MAX_VALUE;
			double maxStateOfCharge = Double.MIN_VALUE;
			double stateOfCharge = 0;
			double voltage = Double.MAX_VALUE;
			double current = 0;
			double capacity_Ah = 0.0;
			
			double minCellVoltage = Double.MAX_VALUE;
			double maxCellVoltage = 0.0;
			
			double minCellTemperature = Double.MAX_VALUE;
			double maxCellTemperature = 0.0;

			saveTimeStamp = true;
			
			for( int i = 0; i < m_numberOfDevices; i++ )
			{
				double moduleStateOfCharge = m_moduleValues[ i ].stateOfCharge();
				minStateOfCharge = Math.min( minStateOfCharge, moduleStateOfCharge );
				maxStateOfCharge = Math.max( maxStateOfCharge, moduleStateOfCharge );
				stateOfCharge += moduleStateOfCharge;
				voltage = Math.min( m_moduleValues[ i ].voltage(), voltage );
				double moduleCurrent =  m_moduleValues[ i ].current();
				current = current + moduleCurrent;
			
				double minCellVoltageOfModule = Double.MAX_VALUE;
				double maxCellVoltageOfModule = Double.MIN_VALUE;
				
				IntegerReadChannel currentChannel = m_currentChannels.get(i);
				
				if( currentChannel != null )
				{
					currentChannel.setNextValue( ( int ) ( moduleCurrent * 1000 ) );
				}
				capacity_Ah = capacity_Ah + m_moduleValues[ i ].remainingCapacity();
				
				for( int j = 0; j < m_moduleValues[i].numberOfCells(); j++ )
				{
					minCellVoltage = Math.min( minCellVoltage, m_moduleValues[i].cellVoltages()[j]);
					maxCellVoltage = Math.max( maxCellVoltage, m_moduleValues[i].cellVoltages()[j]);
					
					minCellVoltageOfModule = Math.min( minCellVoltageOfModule, m_moduleValues[i].cellVoltages()[j]);
					maxCellVoltageOfModule = Math.max( maxCellVoltageOfModule, m_moduleValues[i].cellVoltages()[j]);
				}

				for( int j = 0; j < m_moduleValues[i].numberOfTemperatures() - 1 ; j++ )
				{
					minCellTemperature = Math.min( minCellTemperature, m_moduleValues[i].groupedCellsTemperatures()[j]);
					maxCellTemperature = Math.max( maxCellTemperature, m_moduleValues[i].groupedCellsTemperatures()[j]);
				}
				
				minCellTemperature = Math.min( minCellTemperature , m_moduleValues[ i ].averageBmsTemperature() );
				maxCellTemperature = Math.max( maxCellTemperature , m_moduleValues[ i ].averageBmsTemperature() );
				
				m_minCellVoltageOfModule[ i ] = minCellVoltageOfModule;
				m_maxCellVoltageOfModule[ i ] = maxCellVoltageOfModule;
			}
			
			if( m_numberOfDevices > 0 )
			{
				stateOfCharge /= m_numberOfDevices;
			}
			else
			{
				stateOfCharge = 0;
			}
			
			channel( Battery.ChannelId.SOC ).setNextValue( ( int )( stateOfCharge * 100 ) );
			
			getPylontechBatteryMinModuleSocChannel().setNextValue( ( int )( minStateOfCharge * 100 ) );
			
			getPylontechBatteryMaxModuleSocChannel().setNextValue( ( int )( maxStateOfCharge * 100 ) );

			channel( Battery.ChannelId.VOLTAGE ).setNextValue( ( int )voltage );
			
			getPylontechBatteryVoltageChannel().setNextValue( ( int )( voltage * 10 ) );
			
			channel( Battery.ChannelId.CURRENT ).setNextValue( ( int )current );

			channel( Battery.ChannelId.CAPACITY ).setNextValue( ( int )( capacity_Ah * BATTERY_VOLTAGE ) ); 

			channel( Battery.ChannelId.MIN_CELL_TEMPERATURE ).setNextValue( ( int )minCellTemperature );

			channel( Battery.ChannelId.MAX_CELL_TEMPERATURE ).setNextValue( ( int )maxCellTemperature ); 

			channel( Battery.ChannelId.MIN_CELL_VOLTAGE ).setNextValue( ( int )( minCellVoltage * 1000.0 ) );

			channel( Battery.ChannelId.MAX_CELL_VOLTAGE ).setNextValue( (int )( maxCellVoltage * 1000.0 ) ); 
			
			m_worker.startNextCycle();
		}
		
		if( newManagementInfo && infoAvailable( m_managementInfos ) )
		{
			double chargeCurrent = 0;
			double dischargeCurrent = 0;
			
			double chargeVoltage = Double.MAX_VALUE;
			double dischargeVoltage = 0.0;

			boolean chargeEnable = false;
			boolean dischargeEnable = false;
			boolean fullChargeRequest = false;
			
			saveTimeStamp = true;
			
			for( int i = 0; i < m_numberOfDevices; i++ )
			{
				double moduleChargeLimit = m_managementInfos[ i ].chargeCurrentLimit(); // positive value
				
				double moduleDischargeLimit = m_managementInfos[ i ].dischargeCurrentLimit(); // negative value
				
				double dischargeCalculatedLimit = protectionDef.getDischargeTemperatureToPercent().getValue( m_minCellVoltageOfModule[ i ] ) * ( -25 );
				
				double chargeCalculatedLimit = protectionDef.getDischargeTemperatureToPercent().getValue( m_minCellVoltageOfModule[ i ] ) * 25;
				
				double resultingChargeLimit = Double.min(moduleChargeLimit, chargeCalculatedLimit);
				
				double resultingDishargeLimit = Double.min(moduleDischargeLimit, dischargeCalculatedLimit);

				chargeCurrent += resultingChargeLimit; // positive value
				
				dischargeCurrent -= resultingDishargeLimit;
				
				chargeVoltage = Double.min( m_managementInfos[ i ].chargeVoltageLimit(), chargeVoltage );
				dischargeVoltage = Double.max( m_managementInfos[ i ].dischargeVoltageLimit(), dischargeVoltage );

				chargeEnable |= m_managementInfos[ i ].chargeEnable();

				dischargeEnable |= m_managementInfos[ i ].dischargeEnable();
				
				fullChargeRequest |= m_managementInfos[ i ].fullChargeRequest();
			}

			getFullChargeRequestChannel().setNextValue( fullChargeRequest );
			
			protectionDef._setChargeAllowed(chargeEnable);
			
			getChargeEnabledChannel().setNextValue( chargeEnable );
			
			if( getPylontechBatteryMinModuleSoc() < config.minDischargeModuleSOC() )
			{
				dischargeEnable = false;
			}
			
			protectionDef._setDischargeAllowed(dischargeEnable);
			
			getDischargeEnabledChannel().setNextValue( dischargeEnable );

			channel( BatteryProtection.ChannelId.BP_CHARGE_BMS ).setNextValue( ( int )chargeCurrent );
			channel( BatteryProtection.ChannelId.BP_DISCHARGE_BMS ).setNextValue( ( int )dischargeCurrent );

			// channels Battery.ChannelId.DISCHARGE_MAX_CURRENT and Battery.ChannelId.CHARGE_MAX_CURRENT
			// are set by BatteryProtection

			channel( Battery.ChannelId.DISCHARGE_MIN_VOLTAGE ).setNextValue( ( int )dischargeVoltage );

			channel( Battery.ChannelId.CHARGE_MAX_VOLTAGE ).setNextValue( ( int )chargeVoltage ); 

			getMaxChargeVoltageChannel().setNextValue( ( int )( chargeVoltage * 10 ) );
			getMaxChargeCurrentChannel().setNextValue( ( int )( chargeCurrent * 10 ) );

			getMinDischargVoltagetChannel().setNextValue( ( int )( dischargeVoltage * 10 ) );
			getMaxDischargeCurrentChannel().setNextValue( ( int )( dischargeCurrent * 10 ) ); 
		}

		if( newAlarmInfo && infoAvailable( m_alarmInfos ) )
		{
//			boolean highVoltage = false;
//			boolean lowVoltage = false;
			boolean overVoltageProtection = false;
			boolean underVoltageProtection = false;

//			boolean systemOverVoltage = false;
//			boolean systemUnderVoltage = false;
			boolean systemOverVoltageProtection = false;
			boolean systemUnderVoltageProtection = false;

//			boolean chargeOverCurrent = false;
			boolean chargeOverCurrentProtection = false;
//			boolean dischargeOverCurrent = false;
			boolean dischargeOverCurrentProtection = false;
			
//			boolean highTemp = false;
//			boolean lowTemp = false;
			boolean overTempProtection = false;
			boolean underTempProtection = false;

			saveTimeStamp = true;

			boolean charging = false;
			boolean discharging = false;
			
			for( int i = 0; i < m_numberOfDevices; i++ )
			{
				for( int j = 0; j < m_alarmInfos[i].numberOfCells(); j++ )
				{
					overVoltageProtection |= m_alarmInfos[ i ].cellAlarms()[ j ].isAboveHighLimit();
					underVoltageProtection |= m_alarmInfos[ i ].cellAlarms()[ j ].isBelowLowLimit();
				}

				for( int j = 0; j < m_alarmInfos[i].numberOfSensors(); j++ )
				{
					overTempProtection |= m_alarmInfos[ i ].temperatureAlarms()[ j ].isAboveHighLimit();
					underTempProtection |= m_alarmInfos[ i ].temperatureAlarms()[ j ].isBelowLowLimit();
				}

				
				systemOverVoltageProtection |= m_alarmInfos[i].systemVoltageAlarm().isAboveHighLimit();
				systemUnderVoltageProtection |= m_alarmInfos[i].systemVoltageAlarm().isBelowLowLimit();
				
				chargeOverCurrentProtection |= m_alarmInfos[ i ].currentAlarm().isAboveHighLimit();

				dischargeOverCurrentProtection |= m_alarmInfos[ i ].disChargeCurrentAlarm().isAboveHighLimit();

				m_statesPerModule.get( PY_USE_BAT_POWER + i ).setNextValue( m_alarmInfos[i].extStatus().usingModulePower() );
				
				m_statesPerModule.get( PY_DISCHG_MOSFET + i ).setNextValue( m_alarmInfos[i].extStatus().dischargeMosfetOn() );

				m_statesPerModule.get( PY_CHARGE_MOSFET + i ).setNextValue( m_alarmInfos[i].extStatus().chargeMosfetOn() );
				
				charging |= m_alarmInfos[i].extStatus().isCharging();
				
				m_statesPerModule.get( PY_CHARGING + i ).setNextValue( m_alarmInfos[i].extStatus().isCharging() );

				discharging |= m_alarmInfos[i].extStatus().isDischarging();
				
				m_statesPerModule.get( PY_DIS_CHARGING + i ).setNextValue( m_alarmInfos[i].extStatus().isDischarging() );

				m_statesPerModule.get( PY_FULLY_CHARGED + i ).setNextValue( m_alarmInfos[i].extStatus().isFullyCharged() );

				m_statesPerModule.get( PY_HV_WARNING + i ).setNextValue( m_alarmInfos[i].extStatus().cellOVWarnings() != 0 );
			}
			
			if( charging && discharging )
			{
				charging = false;
			}
			
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_CHARGE_STATUS ).setNextValue( charging );
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_DISCHARGE_STATUS ).setNextValue( discharging );
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_IDLE_STATUS ).setNextValue( !discharging && !charging );
			
			Status basicStatus = Status.UNDEFINED;
			
			if( charging )
			{
				basicStatus = Status.CHARGE;
			}
			else if( discharging )
			{
				basicStatus = Status.DISCHARGE;
			}
			else
			{
				basicStatus = Status.IDLE;
			}
			
			channel( PylontechUS2000CBattery.ChannelId.BASIC_STATUS ).setNextValue( basicStatus );
			

			channel( PylontechUS2000CBattery.ChannelId.BATTERY_CELL_UNDER_VOLTAGE_PROTECTION ).setNextValue(underVoltageProtection);
			channel( PylontechUS2000CBattery.ChannelId.BATTERY_CELL_OVER_VOLTAGE_PROTECTION ).setNextValue(overVoltageProtection);

			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_OVER_VOLTAGE_PROTECTION ).setNextValue(systemOverVoltageProtection);
			channel( PylontechUS2000CBattery.ChannelId.SYSTEM_UNDER_VOLTAGE_PROTECTION ).setNextValue(systemUnderVoltageProtection);
			
			channel( PylontechUS2000CBattery.ChannelId.CHARGE_OVER_CURRENT_PROTECTION ).setNextValue(chargeOverCurrentProtection);
			channel( PylontechUS2000CBattery.ChannelId.DISCHARGE_OVER_CURRENT_PROTECTION ).setNextValue(dischargeOverCurrentProtection);

			channel( PylontechUS2000CBattery.ChannelId.MODULE_OVER_TEMPERATURE_PROTECTION ).setNextValue(overTempProtection);
			channel( PylontechUS2000CBattery.ChannelId.MODULE_UNDER_TEMPERATURE_PROTECTION ).setNextValue(underTempProtection);
		}

		m_worker.startNextCycle();
		
		if( saveTimeStamp && stateMachine.getCurrentState() == State.RUNNING )
		{
			m_lastDataReceiveTime = System.currentTimeMillis();
		}
		/*

SYSTEM_TEMPERATURE_WARNING
SYSTEM_IDLE_STATUS
SYSTEM_CHARGE_STATUS
SYSTEM_DISCHARGE_STATUS

CHARGE_UNDER_TEMPERATURE_PROTECTION
CHARGE_OVER_TEMPERATURE_PROTECTION
DISCHARGE_UNDER_TEMPERATURE_PROTECTION
DISCHARGE_OVER_TEMPERATURE_PROTECTION
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

		var context = new Context(this);

		try {
			this.stateMachine.run(context);
			this.channel(PylontechUS2000CBattery.ChannelId.RUN_FAILED).setNextValue(false);
		} catch (OpenemsNamedException e) {
			this.channel(PylontechUS2000CBattery.ChannelId.RUN_FAILED).setNextValue(true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
		}
	}


	@Override
	public String getJsonInfo(CMD_DESCRIPTORS descr, int addr ) {
		return m_worker.getJsonData(descr, addr);
	}

	@Override
	public String getRawInfo(int cmd, int addr ) {
		return m_worker.getRawData(cmd, addr);
	}


	@Override
	public int getParallelDevices() {
		// TODO Auto-generated method stub
		return m_numberOfDevices;
	}
	
	
}