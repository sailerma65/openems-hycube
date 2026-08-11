package io.openems.edge.hycube.pvinverter;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.INVERT;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1_AND_INVERT;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.battery.pylontech.powercubem2.PylontechPowercubeM2Battery;
import io.openems.edge.battery.pylontech.us2000C.PylontechUS2000CBattery;
import io.openems.edge.batteryinverter.api.BatteryInverterConstraint;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.modbusslave.ModbusType;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.Phase.SingleOrAllPhase;
import io.openems.edge.common.type.Phase.SinglePhase;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.hycube.batteryinverter.HycubeBatteryInverterImpl;
import io.openems.edge.hycube.batteryinverter.statemachine.Context;
import io.openems.edge.hycube.batteryinverter.statemachine.StateMachine;
import io.openems.edge.hycube.batteryinverter.statemachine.StateMachine.State;
import io.openems.edge.hycube.enums.DeviceType;
import io.openems.edge.hycube.ess.HycubeEss;
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
		name = "Hycube.PV-Inverter", //
		immediate = true, //
		configurationPolicy = REQUIRE //
) //
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class HycubePvInverterImpl extends AbstractOpenemsComponent implements HycubePvInverter,
		ManagedSymmetricPvInverter, OpenemsComponent, SinglePhaseMeter {

	private final Logger log = LoggerFactory.getLogger(HycubePvInverterImpl.class);

	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);

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
				SymmetricBatteryInverter.ChannelId.values(), //
				ManagedSymmetricBatteryInverter.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				HycubePvInverter.ChannelId.values() //
		);
	}

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private ManagedSymmetricBatteryInverter batteryInverter;
	
	private HycubeBatteryInverterImpl hyBatteryInverter;
	
	@Activate
	protected void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled());

		if( OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "BatteryInverter", config.batteryInverter_id()) )
		{
			return;
		}
		
		if( batteryInverter instanceof HycubeBatteryInverterImpl pyBat )
		{
			hyBatteryInverter = pyBat;
		}
		else
		{
			return;
		}
		
		/* Channels to be provided:

		 * */

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
			
			raw = ( T )i_converter.channelToElement(raw);

			value = new Value<T>( sourceChannel, raw );
			
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
		return this.stateMachine.getCurrentState().asCamelCase();
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

}
