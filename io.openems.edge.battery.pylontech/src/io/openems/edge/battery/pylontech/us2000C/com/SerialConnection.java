package io.openems.edge.battery.pylontech.us2000C.com;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface SerialConnection{
    void write(byte[] data) throws IOException;

    void write( int bt ) throws IOException;

    byte[] readLine() throws IOException;
    
    int bytesAvailable();
    
    int readBytes( byte[] buffer, int bytesToRead, int offset );
    
    int readBytes(byte[] buffer, int bytesToRead );
    
    boolean isOpen();
    
    void closePort();
    
	int writeBytes( byte[] buffer, int bytesToWrite );
	
	int getNextByte();
}