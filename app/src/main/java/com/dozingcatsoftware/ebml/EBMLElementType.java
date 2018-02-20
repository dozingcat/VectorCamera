package com.dozingcatsoftware.ebml;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class EBMLElementType {

	byte[] idBytes;
	String idHexString;
	String name;

	public enum ValueType {
		CONTAINER, STRING, BINARY, UNSIGNED_INTEGER, SIGNED_INTEGER, FLOAT
	};
	ValueType valueType = ValueType.BINARY;

	// Elements with IDs of 4 bytes are containers, but some with shorter IDs are as well
	static Set<String> CONTAINER_IDS = new HashSet<String>(Arrays.asList(
			MatroskaID.TrackEntry, MatroskaID.Audio
			));

	// TODO: build map of known element types, with value types
	static ValueType valueTypeForID(String hexID) {
		if (hexID.length()>=8) return ValueType.CONTAINER;
		if (CONTAINER_IDS.contains(hexID.toLowerCase())) return ValueType.CONTAINER;
		return ValueType.BINARY;
	}

	public static EBMLElementType elementTypeForBytes(byte[] bytes) {
		EBMLElementType self = new EBMLElementType();
		self.idBytes = bytes.clone();
		self.idHexString = EBMLUtilities.bytesToHexString(self.idBytes);
		self.valueType = valueTypeForID(self.idHexString);
		return self;
	}

	public static EBMLElementType elementTypeForHexString(String hexString) {
		EBMLElementType self = new EBMLElementType();
		self.idHexString = hexString.toUpperCase();
		self.idBytes = EBMLUtilities.hexStringToBytes(self.idHexString);
		self.valueType = valueTypeForID(self.idHexString);
		return self;
	}

	public byte[] getIDBytes() {
		return idBytes.clone();
	}

	public String getIDHexString() {
		return idHexString;
	}

	public int getIDLength() {
		return idBytes.length;
	}

	public String getName() {
		return name;
	}
	public void setName(String value) {
		name = value;
	}


	public EBMLElement createElement() {
		if (valueType==ValueType.CONTAINER) {
			return new EBMLContainerElement(this);
		}
		else {
			return new EBMLElement(this);
		}
	}

	public boolean hexIDMatches(String hexID) {
		return (hexID!=null && this.idHexString.equals(hexID.toUpperCase()));
	}

	@Override
    public boolean equals(Object other) {
		if (!(other instanceof EBMLElementType)) return false;
		return this.idHexString.equals(((EBMLElementType)other).idHexString);
	}

	@Override
    public int hashCode() {
		return this.idHexString.hashCode();
	}

	@Override
    public String toString() {
		return this.idHexString;
	}
}
