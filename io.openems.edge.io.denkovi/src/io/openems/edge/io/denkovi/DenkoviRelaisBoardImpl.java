package io.openems.edge.io.denkovi;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.Context;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;

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

		// Standard-IDs für FTDI FT245 / FT232 Chips
		private static final short VENDOR_ID = (short) 0x0403;
		private static final short PRODUCT_ID = (short) 0x6001;

		// FTDI-spezifische USB-Steuerbefehle (aus dem FTDI D2XX Protokoll)
		private static final byte FTDI_REQ_SET_BITMODE = 0x0B;

		// Bitmaske für den Modus:
		// Die oberen 8 Bit (0xFF) definieren alle 8 Pins als AUSGÄNGE.
		// Die unteren 8 Bit (0x01) aktivieren den klassischen asynchronen
		// Bit-Bang-Modus.
		private static final short BITMODE_ASYNC_BITBANG = (short) 0x01FF;

		private DeviceHandle deviceHandle;

		private Context usbContext = null;
		
		private byte bitmask = 0;

		private BooleanWriteChannel[] writeChannels = {};

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
				this.writeChannels[i] = channel;
			}

			usbContext = new Context();

			int result = LibUsb.init(usbContext);
			if (result != LibUsb.SUCCESS) {
				throw new LibUsbException("USB-Subsystem konnte nicht gestartet werden", result);
			}

			deviceHandle = LibUsb.openDeviceWithVidPid(usbContext, VENDOR_ID, PRODUCT_ID);
			if (deviceHandle == null) {
				throw new RuntimeException("Denkovi-Board (FTDI-Chip) wurde nicht gefunden!");
			}

			// 3. Kernel-Treiber unter Linux/macOS lösen, falls aktiv
			if (LibUsb.hasCapability(LibUsb.CAP_HAS_CAPABILITY)) {
				int detachResult = LibUsb.detachKernelDriver(deviceHandle, 0);
				if (detachResult == LibUsb.SUCCESS) {
					log.info("Standard-Treiber erfolgreich temporär getrennt.");
				}
			}

			// 4. Interface für die Übertragung beanspruchen
			result = LibUsb.claimInterface(deviceHandle, 0);
			if (result != LibUsb.SUCCESS) {
				throw new LibUsbException("Interface konnte nicht beansprucht werden", result);
			}

			// 5. BIT-BANG MODUS AKTIVIEREN (Control Transfer)
			// Sendet den ftdi_set_bitmode Befehl an das Gerät
			int transfered = LibUsb.controlTransfer(deviceHandle,
					(byte) (LibUsb.REQUEST_TYPE_VENDOR | LibUsb.RECIPIENT_DEVICE | LibUsb.ENDPOINT_OUT), // bmRequestType
					FTDI_REQ_SET_BITMODE, // bRequest
					BITMODE_ASYNC_BITBANG, // wValue (0x01FF setzt Pins auf Output + BitBang an)
					(short) 1, // wIndex (Interface 1)
					ByteBuffer.allocateDirect(0), // Kein Daten-Payload im Control-Setup nötig
					5000 // Timeout 5s
			);

			if (transfered < 0) {
				throw new RuntimeException("Fehler beim Aktivieren des Bit-Bang-Modus.");
			}
			log.info("Bit-Bang-Modus erfolgreich aktiviert!");
			
			setBitMask( bitmask );
		}

		@Override
		@Deactivate
		protected void deactivate() {
			super.deactivate();
			// 7. Aufräumen und Schließen
			if (deviceHandle != null) {
				LibUsb.releaseInterface(deviceHandle, 0);
				// Optional: Unter Linux den Treiber wieder anbinden
				LibUsb.attachKernelDriver(deviceHandle, 0);
				LibUsb.close(deviceHandle);
			}

			if (usbContext != null) {

				LibUsb.exit(usbContext);

				usbContext = null;
			}

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
			int tempBitmask = 0;
			for (int i = 0, bit = 1; i < this.writeChannels.length; i++, bit <<= 1 ) {
				BooleanWriteChannel channel = this.writeChannels[i];

				if( channel.value().orElse(Boolean.FALSE).booleanValue() )
				{
					tempBitmask |= bit;
				}
			}
			setBitMask( (byte)tempBitmask );
		}
			
		private synchronized void setBitMask( byte _bitmask )
		{
			// Jedes Bit steht für ein Relais.
			// Relais 1 + Relais 3 einschalten: binär 00000101 = dezimal 5 = 0x05

			bitmask = _bitmask;
			
			ByteBuffer buffer = ByteBuffer.allocateDirect(1);
			buffer.put( bitmask );
			buffer.rewind();

			IntBuffer transferredBytes = IntBuffer.allocate(1);

			// 0x02 ist die Standard-Endpoint-Adresse für OUT-Übertragungen bei FTDI-Chips
			int writeResult = LibUsb.bulkTransfer(deviceHandle, (byte) 0x02, buffer, transferredBytes, 5000);

			if (writeResult != LibUsb.SUCCESS) {
				log.error("Fehler beim Senden der Schalt-Bits.");
			}

		}

}
