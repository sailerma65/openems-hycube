package io.openems.edge.battery.pylontech.us2000C.com;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.fazecast.jSerialComm.SerialPort;

public class SerialPortWrapper implements SerialConnection {
	SerialPort m_port;
	
	public SerialPortWrapper( String i_port )
	{
		m_port = SerialPort.getCommPort(i_port);
	}

	public SerialPort getPort()
	{
		return m_port;
	}
	
	@Override
	public void write(byte[] data) throws IOException {
		m_port.writeBytes( data, data.length );
	}

	@Override
	public void write( int bt ) throws IOException {
		byte[] data = new byte[] { ( byte )bt };
		
		m_port.writeBytes( data, data.length );
	}

	public int getNextByte()
	{
		try
		{
			byte[] buffer = new byte[4];
			
			int count = m_port.readBytes(buffer, 1);
			
			if( count == 1 )
			{
				return (int)buffer[0];
			}
			else
			{
				throw new RuntimeException("no bytes received");
			}
		}
		catch( Exception ex )
		{
			throw new RuntimeException(ex);
		}
	}
	@Override
	public byte[] readLine() throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();

		while( true )
		{
			int read = getNextByte();

			bo.write(read);
			
			if( read == '\r' || read == '\n' )
			{
				break;
			}
		}
		return bo.toByteArray();
	}

	@Override
	public int bytesAvailable() {
		return m_port.bytesAvailable();
	}

	@Override
	public int readBytes(byte[] buffer, int bytesToRead, int offset) {
		return m_port.readBytes(buffer, bytesToRead, offset);
	}

	@Override
	public int readBytes(byte[] buffer, int bytesToRead) {
		return m_port.readBytes(buffer, bytesToRead);
	}

	@Override
	public boolean isOpen() {
		return m_port.isOpen();
	}

	@Override
	public void closePort() {
		m_port.closePort();
	}

	@Override
	public int writeBytes(byte[] buffer, int bytesToWrite) {
		return m_port.writeBytes(buffer, bytesToWrite);
	}
	
	
}
