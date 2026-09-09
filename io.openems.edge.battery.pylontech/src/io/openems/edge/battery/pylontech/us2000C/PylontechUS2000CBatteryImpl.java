package io.openems.edge.battery.pylontech.us2000C;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;

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
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.battery.protection.BatteryProtection;
import io.openems.edge.battery.pylontech.us2000C.PylontechProtocolWorker.FrameData;
import io.openems.edge.battery.pylontech.us2000C.com.PylontechSerialProtocol;
import io.openems.edge.battery.pylontech.us2000C.com.SerialReadWrite;
import io.openems.edge.battery.pylontech.us2000C.statemachine.Context;
import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine;
import io.openems.edge.battery.pylontech.us2000C.statemachine.StateMachine.State;
import io.openems.edge.common.channel.Channel;
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
	
	private int m_numberOfDevices;
	
	private PylontechUS2000CBatteryProtectionDefinition protectionDef;
	
	@Activate
	void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled() );

		m_worker.setParallelDevices( config.devicesInParallel() );
		
		m_worker.setSerialInterface(serialConnection);
		
		m_worker.activate( config.id() + "Worker" );

		m_numberOfDevices = this.config.devicesInParallel();
		
		int _initBmsMaxEverCharge = m_numberOfDevices * 25;
		int _initBmsMaxEverDischarge = m_numberOfDevices * 25;
		int _maxIncreasePerSecond = m_numberOfDevices * 5;
		
		protectionDef = new PylontechUS2000CBatteryProtectionDefinition( 
				_initBmsMaxEverCharge, _initBmsMaxEverDischarge, _maxIncreasePerSecond );
		
		// TODO Protection-Werte abhängig von devicesInParallel
		// maxEverCharge, maxEverDischarge, increasePerSecond
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

	private void applyChannelValues() {
		boolean newSerialNumber = false;
		boolean newManufacturerInfo = false;
		boolean newAlarmInfo = false;
		boolean newModuleValue = false;
		boolean newManagementInfo = false;
		
		boolean communicationError = m_worker.hasCommunicationError();
		
		channel(PylontechUS2000CBattery.ChannelId.COMMUNICATION_ERROR).setNextValue(communicationError);
		
		if( communicationError )
		{
			try {
				State current = stateMachine.getCurrentState();
				
				if( current == State.INIT_COMM || current == State.RUNNING )
				{
					setStartStop(StartStop.UNDEFINED);
					m_worker.quitCommunicationERror();
				}
			} catch (OpenemsNamedException e) {
				// TODO Auto-generated catch block
				log.error( e.getMessage(), e);
			}
			return;
		}
		
		FrameData receivedData;
		
		while( ( receivedData = m_worker.getNextFrame() ) != null ) {
			
			switch( receivedData.cmd )
			{
			case CMD_GET_SERIAL_NUMBER:
				String serialNumber = PylontechSerialProtocol.parseModuleSerialNumber( receivedData.frame.info() );
				
				m_serialNumbers[ receivedData.address - PY_START_ADDRESS ] = serialNumber;

				newSerialNumber = true;
				break;
			case CMD_GET_MANUFACTURER_INFO:
				PylontechSerialProtocol.ManufacturerInfo manufacturerInfo = PylontechSerialProtocol.parseManufacturerInfo(receivedData.frame.info());
				
				m_manufacturerInfos[ receivedData.address - PY_START_ADDRESS ] = manufacturerInfo;
				
				newManufacturerInfo = true;
				break;
			case CMD_GET_ALARM_INFO:
				PylontechSerialProtocol.AlarmInfo alarmInfo = PylontechSerialProtocol.parseAlarm(receivedData.frame.info());
				
				m_alarmInfos[ receivedData.address - PY_START_ADDRESS ] = alarmInfo;

				newAlarmInfo = true;
				break;
			case CMD_GET_ANALOG_VALUE:
				PylontechSerialProtocol.ModuleValues moduleValues = PylontechSerialProtocol.parseValuesSingle(receivedData.frame.info());

				m_moduleValues[ receivedData.address - PY_START_ADDRESS ] = moduleValues;

				newModuleValue = true;
				
				break;
			case CMD_GET_MANAGEMENT_INFO:
				PylontechSerialProtocol.ManagementInfo managementInfo = PylontechSerialProtocol.parseManagementInfo(receivedData.frame.info());

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
				
				capacity_Ah = capacity_Ah + m_moduleValues[ i ].remainingCapacity();
				
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
			
			stateOfCharge /= m_numberOfDevices;
			
			channel( Battery.ChannelId.SOC ).setNextValue( ( int )( stateOfCharge * 100 ) );
			
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

			boolean chargeEnable = true;
			boolean dichargeEnable = true;
			
			for( int i = 0; i < m_numberOfDevices; i++ )
			{
				chargeCurrent += m_managementInfos[ i ].chargeCurrentLimit(); // positive value
				dischargeCurrent -= m_managementInfos[ i ].dischargeCurrentLimit(); // negative value
				
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

			protectionDef._setChargeAllowed(chargeEnable);
			protectionDef._setDischargeAllowed(dichargeEnable);
			
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

		m_worker.startNextCycle();
	// TODO	m_worker.startNextCycle( )
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