package io.openems.edge.hycube.denkovi;

import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE;

import java.util.Objects;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.bridge.http.api.BridgeHttp;
import io.openems.common.bridge.http.api.BridgeHttpFactory;
import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.edge.common.channel.BooleanDoc;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.io.api.DigitalOutput;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Denkovi.8.IO.DigitalOutput", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
	TOPIC_CYCLE_EXECUTE_WRITE, //
	TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})

public class DenkoviRelaisBoardImpl extends AbstractOpenemsComponent
	implements DenkoviRelaisBoard, EventHandler, DigitalOutput, OpenemsComponent { 
		public static final String CHANNEL_NAME = "INPUT_OUTPUT%d";

		private final Logger log = LoggerFactory.getLogger(DenkoviRelaisBoardImpl.class);

		private BooleanWriteChannel[] writeChannels = {};

		@Reference
		private BridgeHttpFactory httpBridgeFactory;

		private BridgeHttp httpBridge;
		
		private String baseUrl;

		public DenkoviRelaisBoardImpl() {
			super(//
					OpenemsComponent.ChannelId.values(), //
					DigitalOutput.ChannelId.values(), //
					DenkoviRelaisBoard.ChannelId.values() //
			);
		}

		@Activate
		private void activate(ComponentContext context, Config config) {
			super.activate(context, config.id(), config.alias(), config.enabled());

			this.baseUrl = "http://" + config.serverIp() + ":" + Integer.toString( config.serverPort() );
			
			this.httpBridge = this.httpBridgeFactory.get();

			// Generate OutputChannels
			this.writeChannels = new BooleanWriteChannel[config.numberOfOutputs()];
			for (var i = 0; i < config.numberOfOutputs(); i++) {
				var channelName = String.format(CHANNEL_NAME, i);
				var doc = new BooleanDoc() //
						.persistencePriority(PersistencePriority.VERY_HIGH) //
						.accessMode(AccessMode.READ_WRITE);
				var channel = (BooleanWriteChannel) this.addChannel(new MyChannelId(channelName, doc));

				// default to OFF
				channel.setNextValue(false);
				this.logInfo(this.log, "Creating Denkovi DigitalOutput [" + channel.address() + "]");
				// register listener for write-events on the channel to set its new value
				channel.onSetNextWrite(value -> {
					this.logInfo(this.log,
							"DigitalOutput [" + channel.address() + "] was turned " + (value ? "ON" : "OFF"));
					channel.setNextValue(value);
				});
				this.writeChannels[i] = channel;
			}
		}

		@Override
		@Deactivate
		protected void deactivate() {
			if (this.httpBridge != null) {
				this.httpBridgeFactory.unget(this.httpBridge);
				this.httpBridge = null;
			}
			super.deactivate();
		}

		@Override
		public BooleanWriteChannel[] digitalOutputChannels() {
			return this.writeChannels;
		}

		@Override
		public void handleEvent(Event event) {
			if (!this.isEnabled()) {
				return;
			}

			switch (event.getTopic()) {
			case TOPIC_CYCLE_EXECUTE_WRITE //
				-> this.executeWrite();
			}
		}

		@Override
		public String debugLog() {
			var b = new StringBuilder();
			for (BooleanWriteChannel channel : this.writeChannels) {
				var valueOpt = channel.value().asOptional();
				if (valueOpt.isPresent()) {
					b.append(valueOpt.get() ? "x" : "-");
				} else {
					b.append("?");
				}
			}
			return b.toString();
		}
		
		/**
		 * Execute on Cycle Event "Execute Write".
		 */
		private void executeWrite() {
			for (int i = 0; i < this.writeChannels.length; i++) {
				this.executeWrite(this.writeChannels[i], i);
			}
		}

		private void executeWrite(BooleanWriteChannel channel, int index) {
			var readValue = channel.value().get();
			var writeValue = channel.getNextWriteValueAndReset();
			if (writeValue.isEmpty()) {
				return;
			}
			if (Objects.equals(readValue, writeValue.get())) {
				return;
			}
			final String url = this.baseUrl + "/relay/" + index + "?turn=" + (writeValue.get() ? "on" : "off");
			this.httpBridge.get(url).whenComplete((t, e) -> {
				setValue(this, DenkoviRelaisBoard.ChannelId.SLAVE_COMMUNICATION_FAILED, e != null);
				if (e == null) {
					this.logInfo(this.log, "Executed write successfully for URL: " + url);
				} else {
					this.logError(this.log, "Failed to execute write for URL: " + url + "; Error: " + e.getMessage());
				}
			});
		}
}
