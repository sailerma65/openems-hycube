package io.openems.edge.hycube.pvinverter;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

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

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.type.Phase.SinglePhase;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.hycube.ess.HycubeEss;
import io.openems.edge.hycube.ess.HycubeEssImpl;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.SinglePhaseMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Implementation of the Hycube Battery Inverter component.
 *
 * <p>
 * This component communicates with Hycube systems  using
 * Modbus-TCP (Unit-ID 100 for system data). It reads system-level power flows,
 * battery status, and ESS control parameters.
 *
 * <p>
 * The inverter is controlled indirectly through the ESS component
 * ({@link HycubeEss}), which handles power setpoints.
 *
 * @see <a href=
 *      Modbus TCP list</a>
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "HycubePV-Inverter", //
		immediate = true, //
		configurationPolicy = REQUIRE //
) //
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class HycubePvInverterImpl extends AbstractOpenemsComponent implements HycubePvInverter,
		ManagedSymmetricPvInverter,  EventHandler, ElectricityMeter, OpenemsComponent, SinglePhaseMeter {

	private final Logger log = LoggerFactory.getLogger(HycubePvInverterImpl.class);

	@Reference
	protected ComponentManager componentManager;

	@Reference
	protected ConfigurationAdmin cm;

//	@Reference
//	private Power power;

	protected Config config;

	public static final int BATTERY_VOLTAGE = 48; // for capacity calculation we cannot use current voltage

	public HycubePvInverterImpl() throws OpenemsNamedException {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				HycubePvInverter.ChannelId.values() //
		);
	}

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private ManagedSymmetricEss ess;
	
	private HycubeEssImpl hyEss;
	
	@Activate
	protected void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled());

		if( OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "ess", config.ess_id()) )
		{
			return;
		}
		
		if( ess instanceof HycubeEssImpl _hyEss )
		{
			hyEss = _hyEss;
		}
		else
		{
			return;
		}
		
		io.openems.edge.common.channel.ChannelId meterPowerOtherA;
		io.openems.edge.common.channel.ChannelId meterPowerOtherB;
		io.openems.edge.common.channel.ChannelId meterPowerChannel;
		
		io.openems.edge.common.channel.ChannelId meterCurrentOtherA;
		io.openems.edge.common.channel.ChannelId meterCurrentOtherB;
		io.openems.edge.common.channel.ChannelId meterCurrentChannel;
		
		io.openems.edge.common.channel.ChannelId meterVoltageOtherA;
		io.openems.edge.common.channel.ChannelId meterVoltageOtherB;
		io.openems.edge.common.channel.ChannelId meterVoltageChannel;

		IntegerReadChannel voltageHycubeChannel;

		switch (config.phase()) {
		case L1 -> {
			meterPowerChannel = ElectricityMeter.ChannelId.ACTIVE_POWER_L1;
			meterPowerOtherA = ElectricityMeter.ChannelId.ACTIVE_POWER_L2;
			meterPowerOtherB = ElectricityMeter.ChannelId.ACTIVE_POWER_L3;

			meterCurrentChannel = ElectricityMeter.ChannelId.CURRENT_L1;
			meterCurrentOtherA = ElectricityMeter.ChannelId.CURRENT_L2;
			meterCurrentOtherB = ElectricityMeter.ChannelId.CURRENT_L3;

			meterVoltageChannel = ElectricityMeter.ChannelId.VOLTAGE_L1;
			meterVoltageOtherA = ElectricityMeter.ChannelId.VOLTAGE_L2;
			meterVoltageOtherB = ElectricityMeter.ChannelId.VOLTAGE_L3;
			

			voltageHycubeChannel = hyEss.getGridVoltageChannelL1();
		}
		case L2 -> { 
			meterPowerChannel = ElectricityMeter.ChannelId.ACTIVE_POWER_L2;
			meterPowerOtherA = ElectricityMeter.ChannelId.ACTIVE_POWER_L1;
			meterPowerOtherB = ElectricityMeter.ChannelId.ACTIVE_POWER_L3;

			meterCurrentChannel = ElectricityMeter.ChannelId.CURRENT_L2;
			meterCurrentOtherA = ElectricityMeter.ChannelId.CURRENT_L1;
			meterCurrentOtherB = ElectricityMeter.ChannelId.CURRENT_L3;

			meterVoltageChannel = ElectricityMeter.ChannelId.VOLTAGE_L2;
			meterVoltageOtherA = ElectricityMeter.ChannelId.VOLTAGE_L1;
			meterVoltageOtherB = ElectricityMeter.ChannelId.VOLTAGE_L3;
			

			voltageHycubeChannel = hyEss.getGridVoltageChannelL2();
		}
		case L3 -> { 
			meterPowerChannel = ElectricityMeter.ChannelId.ACTIVE_POWER_L3;
			meterPowerOtherA = ElectricityMeter.ChannelId.ACTIVE_POWER_L1;
			meterPowerOtherB = ElectricityMeter.ChannelId.ACTIVE_POWER_L2;

			meterCurrentChannel = ElectricityMeter.ChannelId.CURRENT_L3;
			meterCurrentOtherA = ElectricityMeter.ChannelId.CURRENT_L1;
			meterCurrentOtherB = ElectricityMeter.ChannelId.CURRENT_L2;

			meterVoltageChannel = ElectricityMeter.ChannelId.VOLTAGE_L3;
			meterVoltageOtherA = ElectricityMeter.ChannelId.VOLTAGE_L1;
			meterVoltageOtherB = ElectricityMeter.ChannelId.VOLTAGE_L2;
			
			voltageHycubeChannel = hyEss.getGridVoltageChannelL3();
		}
		default -> {
			this.logError(this.log, "Hycube PV Inverter supports only 1 phase ");
			return;
		}
		};

		addCopyListener( hyEss.getSumSolarPowerChannel(), ElectricityMeter.ChannelId.ACTIVE_POWER, ElementToChannelConverter.DIRECT_1_TO_1 );
		addCopyListener( hyEss.getSumSolarPowerChannel(), meterPowerChannel, ElementToChannelConverter.DIRECT_1_TO_1 );

		addCopyListener( voltageHycubeChannel, ElectricityMeter.ChannelId.VOLTAGE, ElementToChannelConverter.SCALE_FACTOR_MINUS_2 );
		addCopyListener( voltageHycubeChannel, meterVoltageChannel, ElementToChannelConverter.SCALE_FACTOR_MINUS_2 );
		
		addCopyListener( hyEss.getOffGridFrequencyChannel(), ElectricityMeter.ChannelId.FREQUENCY, ElementToChannelConverter.SCALE_FACTOR_3 );
		
		this.channel(  meterPowerOtherA ).setNextValue( Integer.valueOf( 0 ) );
		this.channel(  meterPowerOtherB ).setNextValue( Integer.valueOf( 0 ) );
		
		this.channel(  meterCurrentChannel ).setNextValue( Integer.valueOf( 0 ) );
		this.channel(  meterCurrentOtherA ).setNextValue( Integer.valueOf( 0 ) );
		this.channel(  meterCurrentOtherB ).setNextValue( Integer.valueOf( 0 ) );

		this.channel(  meterVoltageOtherA ).setNextValue( Integer.valueOf( 0 ) );
		this.channel(  meterVoltageOtherB ).setNextValue( Integer.valueOf( 0 ) );

		/* Channels to be provided:

		 * */

	}

	@Override
	public void handleEvent(Event event) {
		// TODO Auto-generated method stub
		
	}

	/**
	 * Adds a Copy-Listener. It listens on setNextValue() and copies the value to
	 * the target channel.
	 *
	 * @param <T>             the Channel type
	 * @param sourceChannel   the source Channel
	 * @param targetChannelId the target ChannelId
	 */
	private <T> void addCopyListener(Channel<T> sourceChannel,
			io.openems.edge.common.channel.ChannelId targetChannelId, ElementToChannelConverter i_converter ) {
		Consumer<Value<T>> callback = value -> {
			Channel<T> targetChannel = this.channel(targetChannelId);
			T raw = value.get();
			
			@SuppressWarnings("unchecked")
			T raw2 = ( T )i_converter.channelToElement(raw);

			value = new Value<T>( sourceChannel, raw2 );
			
			targetChannel.setNextValue(value);
		};
		sourceChannel.onSetNextValue(callback);
		callback.accept(sourceChannel.getNextValue());
	}


	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public String debugLog() {
		return "";
	}

	/**
	 * Uses Info Log for further debug features.
	 */
	@Override
	protected void logDebug(Logger log, String message) {
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}

	@Override
	public SinglePhase getPhase() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public MeterType getMeterType() {
		// TODO Auto-generated method stub
		return HycubePvInverter.super.getMeterType();
	}

}
