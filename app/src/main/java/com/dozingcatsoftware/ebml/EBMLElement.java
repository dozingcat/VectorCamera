package com.dozingcatsoftware.ebml;

import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;

public class EBMLElement {
	
	EBMLElementType elementType;
	Object value;
	
	static byte[] EMPTY_BYTES = new byte[0];
	
	public EBMLElement(EBMLElementType elementType) {
		this.elementType = elementType;
	}
	
	public static EBMLElement createWithHexID(String hexID, Object value) {
		EBMLElement element = new EBMLElement(EBMLElementType.elementTypeForHexString(hexID));
		element.setValue(value);
		return element;
	}

	public long getContentSize() {
		return dataBytes().length;
	}
	
	public long getTotalSize() {
		long contentSize = getContentSize();
		return contentSize + EBMLUtilities.numberOfBytesToEncodeLength(contentSize) + elementType.getIDLength();
	}
	
	public Object getValue() {
		return value;
	}
	public void setValue(Object v) {
		value = v;
	}
	
	// TODO: add setDataBytes method to convert raw bytes to this element's type
	
	public EBMLElementType getElementType() {
		return elementType;
	}
	
	public byte[] dataBytes() {
		if (value==null) return EMPTY_BYTES;
		if (value instanceof byte[]) {
			return (byte[])value;
		}
		if (value instanceof Float) {
			return EBMLUtilities.dataBytesForFloat((Float)value);
		}
		if (value instanceof Double) {
			return EBMLUtilities.dataBytesForDouble((Double)value);
		}
		if (value instanceof Number) {
			return EBMLUtilities.dataBytesForLong(((Number)value).longValue());
		}
		if (value instanceof String) {
			return ((String)value).getBytes();
		}
		throw new IllegalArgumentException("Unknown value type:" + value + "(" + value.getClass() + ")");
	}
	
	public void writeToStream(OutputStream output) throws IOException {
		output.write(this.elementType.getIDBytes());
		byte[] data = this.dataBytes();
		output.write(EBMLUtilities.bytesForLength(data.length));
		output.write(data);
	}
	
	// because OutputStream doesn't implement DataOutput (interface used by RandomAccessFile), yay Java
	public void writeToStream(DataOutput output) throws IOException {
		output.write(this.elementType.getIDBytes());
		byte[] data = this.dataBytes();
		output.write(EBMLUtilities.bytesForLength(data.length));
		output.write(data);
	}
}
