package io.openems.edge.battery.pylontech.us2000C.com;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Logger;

import com.fazecast.jSerialComm.SerialPort;

/**
 * Java port of pylontech.py without external dependencies.
 *
 * <p>The class expects a line-oriented serial connection where frames end with '\r'.
 */
public class Pylontech {
	
	public static final long  TIMEOUT_MILLI = 1500l;
	public static final long FACTOR_MILLI_TO_NANO = 1000000l;
	
	public static enum CMD_DESCRIPTORS
	{
		CMD_GET_ANALOG_VALUE( 0x42 ),
		CMD_GET_ALARM_INFO( 0x44 ),
	    CMD_GET_SYSTEM_PARAMETERS( 0x47 ),
		CMD_GET_PROTOCOL_VERSION( 0x4F ),
		CMD_GET_MANUFACTURER_INFO( 0x51 ),
		CMD_GET_MANAGEMENT_INFO( 0x92 ),
		CMD_GET_SERIAL_NUMBER( 0x93 ),
		CMD_SET_CHARGE_DISCHARGE_MANAGEMENT( 0x94 ),
		CMD_TURNOFF( 0x95 ),
		CMD_GET_FIRMWARE_INFO( 0x96 ),
		CMD_RETRIEVE_BATT_BASIC_INFO( 0x60 ),
		CMD_GET_SYSTEM_ANALOG_DATA( 0x61 ),
		CMD_GET_SYSTEM_ALARM_INFO( 0x62 ),
		CMD_GET_SYSTEM_CHARGE_DISCHARGE_MANAGEMENT( 0x63 ),
		CMD_SYSTEM_SHUTDOWN( 0x64 );
		
		private int m_cmd;
		
		private CMD_DESCRIPTORS( int cmd )
		{
			m_cmd = cmd;
		}
		public int getCmd()
		{
			return m_cmd;
		}
	}
	
	
	private static final Logger LOGGER = Logger.getLogger(Pylontech.class.getName());

    public record Frame (int version, int address, int cid1, int cid2, int infoLength, byte[] info,
			String asciiFrame, byte[] rawFrame )
    {
    }
   
    private final SerialConnection connection;

    private ByteArrayOutputStream m_receiveBuffer = new ByteArrayOutputStream();
    
    private long m_receiveStartTime;
    
    
    public Pylontech(String i_portName) {
		SerialPortWrapper wrapper = new SerialPortWrapper(i_portName);
		
		wrapper.getPort().setBaudRate(115200);
		wrapper.getPort().setNumDataBits(8);
		wrapper.getPort().setNumStopBits(0);
		wrapper.getPort().openPort();

		wrapper.getPort().setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

    	this.connection = wrapper;
    }

    public static int getFrameChecksum(byte[] frame) {
        int sum = 0;
        for (byte b : frame) {
            sum = (sum + (b & 0xFF)) & 0xFFFF;
        }
        // two's complement (so that sum(frame + checksum) == 0 modulo 0x10000)
        sum = (~sum + 1) & 0xFFFF;
        return sum;
    }

    public static int getInfoLength(byte[] info) {
        int lenId = info.length;
        if (lenId == 0) {
            return 0;
        }

        int lenIdSum = (lenId & 0xF) + ((lenId >> 4) & 0xF) + ((lenId >> 8) & 0xF);
        int lenIdModulo = lenIdSum % 16;
        int lenIdInvertPlusOne = 0b1111 - lenIdModulo + 1;
        return (lenIdInvertPlusOne << 12) + lenId;
    }

    public void sendCmd(int address, CMD_DESCRIPTORS i_cmdDescr, byte[] info) throws IOException {
        sendCmdRaw(address, i_cmdDescr.getCmd(), info );
    }

    public void sendCmdWithAddressInfo(int address, CMD_DESCRIPTORS i_cmdDescr ) throws IOException {
        byte[] bdEvId = String.format("%02X", address).getBytes(StandardCharsets.US_ASCII);

    	sendCmdRaw(address, i_cmdDescr.getCmd(), bdEvId );
    }

    public void sendCmd(int address, CMD_DESCRIPTORS i_cmdDescr ) throws IOException {
        sendCmdRaw( address, i_cmdDescr.getCmd() );
    }

    public void sendCmdRaw(int address, int cmd, byte[] info) throws IOException {
        byte[] rawFrame = encodeCmd(address, 0x46, cmd, info == null ? new byte[0] : info);
        connection.write(rawFrame);
    }

    public void sendCmdRaw(int address, int cmd) throws IOException {
        sendCmdRaw(address, cmd, new byte[0]);
    }

    public static byte[] encodeCmd(int address, int cid1, int cid2, byte[] info) {
        int infoLength = getInfoLength(info);

        String head = String.format("%02X%02X%02X%02X%04X", 0x20, address, cid1, cid2, infoLength);
        byte[] headAscii = head.getBytes(StandardCharsets.US_ASCII);

        // INFO must be sent as ASCII HEX (each byte -> 2 ASCII chars)
        byte[] infoHexAscii = bytesToHex(info).getBytes(StandardCharsets.US_ASCII);

        byte[] frame = concat(headAscii, infoHexAscii);

        int frameChecksum = getFrameChecksum(frame);
        String checksumHex = String.format("%04X", frameChecksum);

        return concat(
                new byte[]{'~'},
                frame,
                checksumHex.getBytes(StandardCharsets.US_ASCII),
                new byte[]{'\r'}
        );
    }

    public static byte[] decodeHwFrame(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length < 7) {
            throw new IllegalArgumentException("Frame is too short");
        }
        if (rawFrame[0] != '~' || rawFrame[rawFrame.length - 1] != '\r') {
            throw new IllegalArgumentException("Invalid frame delimiters");
        }

        // frame: ~ [header+info ascii hex] [4 ascii checksum chars] \r
        byte[] frameData = Arrays.copyOfRange(rawFrame, 1, rawFrame.length - 5);
        byte[] frameChecksum = Arrays.copyOfRange(rawFrame, rawFrame.length - 5, rawFrame.length - 1);

        int gotFrameChecksum = getFrameChecksum(frameData);
        int expected = Integer.parseInt(new String(frameChecksum, StandardCharsets.US_ASCII), 16);
        if (gotFrameChecksum != expected) {
            throw new IllegalArgumentException("Checksum mismatch: got " + String.format("%04X", gotFrameChecksum) + " expected " + String.format("%04X", expected));
        }

        return frameData;
    }

    public static Frame decodeFrame(byte[] frame, byte[] rawFrame ) {
        String ascii = new String(frame, StandardCharsets.US_ASCII);
        if (ascii.length() < 12) {
            throw new IllegalArgumentException("Protocol frame too short");
        }

        int version = parseHexByte(ascii.substring(0, 2));
        int address = parseHexByte(ascii.substring(2, 4));
        int cid1 = parseHexByte(ascii.substring(4, 6));
        int cid2 = parseHexByte(ascii.substring(6, 8));
        String infoLenStr = ascii.substring(8, 12);
        int infoLength = ( Integer.parseInt(infoLenStr, 16) & 0xFFF ) / 2;

        String infoHex = ascii.substring(12);
        byte[] info = hexToBytes(infoHex);
        
        return new Frame(version, address, cid1, cid2, infoLength, info, ascii, rawFrame );
    }

    public void startReceive()
    {
    	m_receiveBuffer.reset();
    	m_receiveStartTime = System.nanoTime();
    }
    
    public void clearReceiveBuffer() throws Exception
    {
    	int avail;
    	
    	while( ( avail = connection.bytesAvailable() ) > 0 )
    	{
        	byte[] buffer = new byte[ avail ];
    		
        	int received = connection.readBytes(buffer, avail);
        	
        	if( received < avail )
        	{
        		throw new Exception( "UNEXPECTED number of received bytes!" );
        	}
    	}
    	startReceive();
    }

    public static class UnexpectedStartOfFrame extends Exception
    {
    	UnexpectedStartOfFrame()
    	{
    		super("UNEXPECTED start of frame character!");
    	}
    }
    
    public static class FrameTimeoutException extends Exception
    {
    	FrameTimeoutException()
    	{
    		super("Timeout waiting for frame");
    	}
    }

    public Frame receiveOrWait() throws IOException, UnexpectedStartOfFrame, FrameTimeoutException {
    	byte[] buffer = new byte[ 1 ];
		
    	while( connection.bytesAvailable() > 0 )
    	{
        	int received = connection.readBytes(buffer, 1 );
        	
        	if( received != 1 )
        	{
        		throw new IOException( "UNEXPECTED number of received bytes!" );
        	}
        	
        	if( m_receiveBuffer.size() == 0 )
        	{
        		if(  buffer[0] != '~' )
        		{
        			throw new UnexpectedStartOfFrame();
        		}
        	}
        	
        	m_receiveBuffer.write(buffer);
        	
        	if( buffer[0] == '\r' )
        	{
        		byte[] rawFrame = m_receiveBuffer.toByteArray();
        		
                byte[] f = decodeHwFrame(rawFrame);
                return decodeFrame(f, rawFrame );
        	}
    	}

    	if( System.nanoTime() - m_receiveStartTime > FACTOR_MILLI_TO_NANO * TIMEOUT_MILLI )
    	{
    		throw new FrameTimeoutException();
    	}
    	return null;
    }
    
    public Frame readFrame() throws IOException {
        byte[] rawFrame = connection.readLine();
        
        byte[] f = decodeHwFrame(rawFrame);
        return decodeFrame(f, rawFrame );
    }

    public Frame getSystemParameters(Integer devId) throws IOException {
        if (devId != null && devId != 0) {
            byte[] bdEvId = String.format("%02X", devId).getBytes(StandardCharsets.US_ASCII);
            sendCmd(devId, CMD_DESCRIPTORS.CMD_GET_SYSTEM_PARAMETERS, bdEvId);
        } else {
            sendCmd(2, CMD_DESCRIPTORS.CMD_GET_SYSTEM_PARAMETERS);
        }

        Frame f = readFrame();
        return f;
    }

    public Frame getManagementInfo(int devId) throws IOException {
        byte[] bdEvId = String.format("%02X", devId).getBytes(StandardCharsets.US_ASCII);
		sendCmd(devId, CMD_DESCRIPTORS.CMD_GET_MANAGEMENT_INFO, bdEvId);
        Frame f = readFrame();
        return f;
    }

    public Frame getAlarmInfo(int devId) throws IOException {
        byte[] bdEvId = String.format("%02X", devId).getBytes(StandardCharsets.US_ASCII);
		sendCmd(devId, CMD_DESCRIPTORS.CMD_GET_ALARM_INFO, bdEvId);
        Frame f = readFrame();
        return f;
    }

    public Frame getModuleSerialNumber(Integer devId) throws IOException {
        if (devId != null && devId != 0) {
            byte[] bdEvId = String.format("%02X", devId).getBytes(StandardCharsets.US_ASCII);
            sendCmd(devId, CMD_DESCRIPTORS.CMD_GET_SERIAL_NUMBER, bdEvId);
        } else {
            sendCmd(2, CMD_DESCRIPTORS.CMD_GET_SERIAL_NUMBER);
        }

        Frame f = readFrame();
        return f;
    }

    public Frame getModuleManufacturerInfo(Integer devId) throws IOException {
        if (devId != null && devId != 0) {
            byte[] bdEvId = String.format("%02X", devId).getBytes(StandardCharsets.US_ASCII);
            sendCmd(devId, CMD_DESCRIPTORS.CMD_GET_MANUFACTURER_INFO, bdEvId);
        } else {
            sendCmd(2, CMD_DESCRIPTORS.CMD_GET_MANUFACTURER_INFO);
        }

        Frame f = readFrame();
        
        return f;
    }

    public Frame getValuesSingle(int devId) throws IOException {
        byte[] bdEvId = String.format("%02X", devId).getBytes(StandardCharsets.US_ASCII);
        sendCmd(devId, CMD_DESCRIPTORS.CMD_GET_ANALOG_VALUE, bdEvId);
        Frame f = readFrame();
        return f;
    }


    private static boolean parseBit( int input, int bitNumber )
    {
    	return ( input & ( 1 << bitNumber ) ) != 0;
    }
    
    private static int encodeBit( int input, int bitNumber, boolean i_value )
    {
    	if( i_value )
    		return ( input | ( 1 << bitNumber ) );
    	else
    		return input;
    }


    private static int parseHexByte(String twoChars) {
        return Integer.parseInt(twoChars, 16) & 0xFF;
    }

    private static String encodeHexByte( int bt ) {
        return String.format("%02X", bt & 0xFF);
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() % 2) != 0) {
            throw new IllegalArgumentException("Hex string has odd length");
        }

        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int idx = i * 2;
            out[i] = (byte) Integer.parseInt(hex.substring(idx, idx + 2), 16);
        }
        return out;
    }

    private static String bytesToHex(byte[] i_bytes ) {
        StringBuilder result = new StringBuilder(i_bytes.length * 2);
        
        for (int i = 0; i < i_bytes.length; i++ ) {
        	result.append( encodeHexByte( i_bytes[i] & 0xFF ) );
        }
    	
        return result.toString();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }

        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    public static byte[] slice(byte[] data, int from) {
        if (from >= data.length) {
            return new byte[0];
        }
        return Arrays.copyOfRange(data, from, data.length);
    }

    public static final class ReadCursor {
        private final byte[] data;
        private int readPos;

        public ReadCursor(byte[] data) {
            this.data = Objects.requireNonNull(data, "data");
            this.readPos = 0;
        }

        public int remaining() {
            return data.length - readPos;
        }

        public byte[] readBytes(int count) {
            ensure(count);
            byte[] out = Arrays.copyOfRange(data, readPos, readPos + count);
            readPos += count;
            return out;
        }

        public String readAscii(int count) {
            return new String(readBytes(count), StandardCharsets.US_ASCII);
        }

        public int readUInt8() {
            ensure(1);
            return data[readPos++] & 0xFF;
        }

        public int readInt16() {
            ensure(2);
            int hi = data[readPos++] & 0xFF;
            int lo = data[readPos++] & 0xFF;
            int value = (hi << 8) | lo;
            if ((value & 0x8000) != 0) {
                value -= 0x10000;
            }
            return value;
        }

        public int readUInt16() {
            ensure(2);
            int hi = data[readPos++] & 0xFF;
            int lo = data[readPos++] & 0xFF;
            return (hi << 8) | lo;
        }

        public int readUInt24() {
            ensure(3);
            int b1 = data[readPos++] & 0xFF;
            int b2 = data[readPos++] & 0xFF;
            int b3 = data[readPos++] & 0xFF;
            return (b1 << 16) | (b2 << 8) | b3;
        }

        public int getLength()
        {
        	return data.length;
        }
        private void ensure(int count) {
            if (readPos + count > data.length) {
                throw new IllegalArgumentException("Unexpected end of payload at offset " + readPos);
            }
        }
    }
    private static final class WriteCursor{
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        WriteCursor() {
        }

        int position() {
        	return out.size();
        }

        void writeBytes( byte[] i_bytes )
        {
        	try {
				out.write(i_bytes);
			} catch (IOException e) {
				// ByteArrayOutputStream won't actually throw here
			}
        }

        int writeAscii( String i_str )
        {
        	byte[] bytes = i_str.getBytes(StandardCharsets.US_ASCII);
        	writeBytes(bytes);
        	return bytes.length;
        }

        int writeAscii( String i_str, int i_maxLen )
        {
        	String str;
        	
        	if( i_str.length() > i_maxLen )
        	{
        		str = i_str.substring(0, i_maxLen );
        	}
        	else
        	{
        		str = i_str;
        	}
        	byte[] bytes = str.getBytes(StandardCharsets.US_ASCII);
        	
        	writeBytes(bytes);
        	// pad with zeros to fixed length
        	for (int i = bytes.length; i < i_maxLen; i++) {
        		out.write(0);
        	}
        	return bytes.length;
        }

        void writeUInt8( int i_data )
        {
        	out.write(i_data & 0xFF);
        }

        void writeInt16( int i_data )
        {
        	out.write((i_data >> 8) & 0xFF);
        	out.write(i_data & 0xFF);
        }

        void writeUInt16( int i_data )
        {
        	out.write((i_data >> 8) & 0xFF);
        	out.write(i_data & 0xFF);
        }

        void writeUInt24( int i_data ) {
        	out.write((i_data >> 16) & 0xFF);
        	out.write((i_data >> 8) & 0xFF);
        	out.write(i_data & 0xFF);
        }
        
        byte[] getData()
        {
        	return out.toByteArray();
        }

    }
    
	public record ManufacturerInfo( String deviceName, String softwareVersion, String manufacturerName )
	{
		
	}
	
    public static ManufacturerInfo parseManufacturerInfo(byte[] info) {
        ReadCursor c = new ReadCursor(info);
        String deviceName = c.readAscii(10);
        byte[] versionBytes = c.readBytes(2);
        String softwareVersion = "%c.%c".formatted( ( '0' + versionBytes[0] ), ( '0' + versionBytes[1] ) );
        String manufacturerName = c.readAscii(c.remaining());
        
        return new ManufacturerInfo(deviceName, softwareVersion, manufacturerName);
        
    }

    public record ManagementInfo( double chargeVoltageLimit, double dischargeVoltageLimit, double chargeCurrentLimit, double dischargeCurrentLimit, 
    		boolean chargeEnable, boolean dischargeEnable, boolean chargeImmediately1, boolean chargeImmediately2, boolean fullChargeRequest )
    {
    	
    }
    public static ManagementInfo parseManagementInfo(byte[] info) {
        ReadCursor c = new ReadCursor(info);
        int commandValue = c.readUInt8();
        
        double chargeVoltageLimit = divideBy1000(c.readUInt16());
        double dischargeVoltageLimit = divideBy1000(c.readUInt16());
        double chargeCurrentLimit = toAmp(c.readInt16());
        double dischargeCurrentLimit = toAmp(c.readInt16());

        int statusByte = c.readUInt8();
        boolean chargeEnable = (statusByte & 0b1000_0000) != 0;
        boolean dischargeEnable = (statusByte & 0b0100_0000) != 0;
        boolean chargeImmediately2 = (statusByte & 0b0010_0000) != 0;
        boolean chargeImmediately1 = (statusByte & 0b0001_0000) != 0;
        boolean fullChargeRequest = (statusByte & 0b0000_1000) != 0;
        
        return new ManagementInfo(chargeVoltageLimit, dischargeVoltageLimit, chargeCurrentLimit, dischargeCurrentLimit, 
        		chargeEnable, dischargeEnable, chargeImmediately1, chargeImmediately2, fullChargeRequest);
    }

    
    public static String parseModuleSerialNumber(byte[] info) {
        ReadCursor c = new ReadCursor(info);
        int commandValue = c.readUInt8();
        String moduleSerialNumber = c.readAscii(16);
        return moduleSerialNumber;

    }

    public record CellAlarm( boolean cellLowVoltage, boolean cellHighVoltage, boolean cellUnderVoltageProtection, boolean cellOverVoltageProtection, boolean cellInbalance )
    {
    	
    }
    
    private static CellAlarm parseCellAlarm(ReadCursor c) {
    	int byteValue = c.readUInt8();
 
    	return new CellAlarm( parseBit( byteValue, 0 ), parseBit( byteValue, 1 ), parseBit( byteValue, 2 ), parseBit( byteValue, 3 ), 
    			( byteValue & 0xF0 ) != 0 );
    }

    public record TemperatureAlarm( boolean lowTemperature, boolean highTemperature, boolean underTemperatureProtection, boolean overTemperatureProtection )
    {
    	
    }
    
    private static TemperatureAlarm parseTemperatureAlarm(ReadCursor c) {

    	int byteValue = c.readUInt8();

    	return new TemperatureAlarm( parseBit( byteValue, 0 ), parseBit( byteValue, 1 ), parseBit( byteValue, 2 ), parseBit( byteValue, 3 ) );
    }

    public record CurrentAlarm( boolean overCurrent, boolean overCurrentProtection )
    {
    	
    }
    
    private static CurrentAlarm parseCurrentAlarm(ReadCursor c) {
    	int byteValue = c.readUInt8();

    	return new CurrentAlarm(parseBit( byteValue, 0 ), parseBit( byteValue, 1 ) );
    }

    public record VoltageAlarm( boolean systemUnderVoltage, boolean systemOverVoltage, boolean underVoltageProtection, boolean overVoltageProtection )
    {
    	
    }
    
    private static VoltageAlarm parseVoltageAlarm(ReadCursor c) {
    	int byteValue = c.readUInt8();
 
    	return new VoltageAlarm( parseBit( byteValue, 0 ),   parseBit( byteValue, 1 ),   parseBit( byteValue, 2 ),   parseBit( byteValue, 3 ) );
    }

    public record AlarmInfo( int numberOfCells, CellAlarm[] cellAlarms, int numberOfSensors, TemperatureAlarm[] temperatureAlarms, CurrentAlarm currentAlarm, 
      VoltageAlarm systemVoltageAlarm, CurrentAlarm disChargeCurrentAlarm ){}
      
    public static AlarmInfo parseAlarm(byte[] info )
    {
        ReadCursor c = new ReadCursor(info);
        int dataFlag = c.readUInt8();
        int commandValue = c.readUInt8();
        int numberOfCells = c.readUInt8();
        
        CellAlarm[] cellAlarms = new CellAlarm[ numberOfCells ];

        for( int i = 0; i < numberOfCells; i++ ) {
        	cellAlarms[ i ] = parseCellAlarm(c);
        }

        int numberOfSensors = c.readUInt8();

        TemperatureAlarm[] temperatureAlarms = new TemperatureAlarm[ numberOfSensors ];
  
        for( int i = 0; i < numberOfSensors; i++ ) {
        	temperatureAlarms[ i ] = parseTemperatureAlarm(c);
        }
        
        CurrentAlarm chargeCurrentAlarm = parseCurrentAlarm(c);
        VoltageAlarm systemVoltageAlarm = parseVoltageAlarm(c);
        CurrentAlarm disChargeCurrentAlarm = parseCurrentAlarm(c);
        
        // Status 1:
        // 7: module under voltage
        // 6: charge over temperature
        // 5: discharge over temperature
        // 4: discharge over current
        // 2: charge over current
        // 1: cell under voltage
        // 0: module over voltage
        
        
        // Status 2:
        // 3: using battery module power
        // 2: discharge mosfet  1 on 0 off
        // 1: charge mosfet 1: on 0 off
        
        // Status 3:
        // 7: effective charge current > 0.1A
        // 6: effective discharge current < -0.1 A
        // 3: fully charged 1 = full
        // 0: buzzer 1 on 0 off
        
        // Status 4:
        // 7 cell voltage 8 1: error, 0 : normal
        // ....
        // 0 cell voltage 1 1: error, 0 : normal
        
        // Status 5:
        // 7 cell voltage 16 1: error, 0 : normal
        // ....
        // 0 cell voltage 9  1: error, 0 : normal
        
        
        return new AlarmInfo(numberOfCells, cellAlarms, numberOfSensors, temperatureAlarms, chargeCurrentAlarm, systemVoltageAlarm, disChargeCurrentAlarm);
    }

    public record ModuleValues( int numberOfCells, double[] cellVoltages, int numberOfTemperatures, double averageBmsTemperature, double[] groupedCellsTemperatures,
    	double current, double voltage, double power, double remainingCapacity, double totalCapacity, double stateOfCharge, int cycleNumber ) {}
    
    
    private static ModuleValues parseModuleValues(ReadCursor c) {
    	int numberOfCells = c.readUInt8();
    
    	double[] cellVoltages = new double[ numberOfCells ];
    	
        for (int i = 0; i < numberOfCells; i++) {
            cellVoltages[ i ] = toVolt(c.readInt16());
        }

        int numberOfTemperatures = c.readUInt8();
        
        double averageBmsTemperature = toCelsius(c.readInt16());
        

        int groupedCount = Math.max(0, numberOfTemperatures - 1);
        
        double[] groupedCellsTemperatures = new double[ groupedCount ];
        
        for (int i = 0; i < groupedCount; i++) {
            groupedCellsTemperatures[ i ] = toCelsius(c.readInt16());
        }

        double current = toAmp(c.readInt16());
        double voltage = toVolt(c.readUInt16());
        double power = current * voltage;

        double remainingCapacity1 = divideBy1000(c.readUInt16());
        int userDefinedItems = c.readUInt8();
        double totalCapacity1 = divideBy1000(c.readUInt16());
        int cycleNumber = c.readUInt16();

        double remainingCapacity = remainingCapacity1;
        double totalCapacity = totalCapacity1;
        if (userDefinedItems > 2) {
            remainingCapacity = divideBy1000(c.readUInt24());
            totalCapacity = divideBy1000(c.readUInt24());
        }
        double stateOfCharge = remainingCapacity / totalCapacity;
        
        return new ModuleValues(numberOfCells, cellVoltages, numberOfTemperatures, averageBmsTemperature, 
        		groupedCellsTemperatures, current, voltage, power, remainingCapacity, totalCapacity, stateOfCharge, cycleNumber );
    }

    public static ModuleValues parseValuesSingle(byte[] info) {
        ReadCursor c = new ReadCursor(info);
        
        // command value == addr
        int commandValue = c.readUInt8();
        
        int numberOfModule = c.readUInt8();
        return parseModuleValues(c);
    }

    private static double divideBy1000(int value) {
        return value / 1000.0;
    }

    private static double toVolt(int value) {
        return value / 1000.0;
    }

    private static double toAmp(int value) {
        return value / 10.0;
    }

    private static double toCelsius(int value) {
        return (value - 2731) / 10.0;
    }

    
}