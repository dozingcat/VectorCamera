package com.dozingcatsoftware.ebml;

import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;

public class EBMLContainerElement extends EBMLElement {
	
	static byte[] UNKNOWN_LENGTH_BYTES = new byte[] {0x01, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF};
	long contentSize=-1;
	
	
	public EBMLContainerElement(EBMLElementType elementType) {
		super(elementType);
	}
	
	public static EBMLContainerElement createWithHexID(String hexID) {
		return new EBMLContainerElement(EBMLElementType.elementTypeForHexString(hexID));
	}
	
	// content size is stored directly in an ivar so getContentSize() is overridden, getTotalSize still works
	@Override
	public long getContentSize() {
		return contentSize;
	}
	
	public void setContentSize(long value) {
		contentSize = value;
	}
	
	public boolean isUnknownSize() {
		return contentSize < 0;
	}
	
	// overridden to write only ID and length bytes
	@Override
	public void writeToStream(OutputStream output) throws IOException {
		output.write(getElementType().getIDBytes());
		if (isUnknownSize()) {
			output.write(UNKNOWN_LENGTH_BYTES);
		}
		else {
			output.write(EBMLUtilities.bytesForLength(getContentSize()));
		}
	}

	// because OutputStream doesn't implement DataOutput (interface used by RandomAccessFile), yay Java
	@Override
	public void writeToStream(DataOutput output) throws IOException {
		output.write(getElementType().getIDBytes());
		if (isUnknownSize()) {
			output.write(UNKNOWN_LENGTH_BYTES);
		}
		else {
			output.write(EBMLUtilities.bytesForLength(getContentSize()));
		}
	}

}
