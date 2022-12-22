package com.dozingcatsoftware.ebml;

import java.io.IOException;
import java.io.InputStream;

public class EBMLUtilities {

	static String HEX_CHARS_STRING = "0123456789ABCDEF";
	static char[] HEX_CHARS = HEX_CHARS_STRING.toCharArray();

	public static String bytesToHexString(byte[] bytes) {
		StringBuilder sb = new StringBuilder(2*bytes.length);
		for(int i=0; i<bytes.length; i++) {
			sb.append(HEX_CHARS[(bytes[i]>>>4) & 0x0F]);
			sb.append(HEX_CHARS[bytes[i] & 0x0F]);
		}
		return sb.toString();
	}

	public static byte[] hexStringToBytes(String hexString) {
		if (hexString.length()%2!=0) throw new IllegalArgumentException("Hex string length must be even: " + hexString);
		byte[] bytes = new byte[hexString.length() / 2];
		char[] ucChars = hexString.toUpperCase().toCharArray();
		for(int i=0; i<bytes.length; i++) {
			int index1 = HEX_CHARS_STRING.indexOf(ucChars[2*i]);
			int index2 = HEX_CHARS_STRING.indexOf(ucChars[2*i+1]);
			if (index1==-1 || index2==-1) throw new IllegalArgumentException("Non-hex character in string: " + hexString);
			bytes[i] = (byte)((index1<<4) + index2);
		}
		return bytes;
	}

	/** Returns a byte array containing the self-delimited length. The number of bytes of length is the number
	 * of leading zeros in the first byte, plus one. (The first byte is included in that count). The bits
	 * indicating the length follow the first one bit, with most significant bits first.
	 * Examples:
	 * 1 -> 10000001 = 0x81
	 * 126 -> 11111110 = 0xFE
	 * 127 -> 01000000 01111111 = 0x40, 0x7F (can't fit in one byte because EBML reserves length bytes of all 1s)
	 * 128 -> 01000000 10000000 = 0x40, 0x80
	 *
	 */
	public static byte[] bytesForLength(long length) {
		byte[] bytes = new byte[numberOfBytesToEncodeLength(length)];
		// put a leading 1 in the encoded bytes, shifted left by 7*size. This will create the correct number of leading zeros.
		long mask = 1L << (7*bytes.length);
		long value = mask | length;
		// big-endian, least significant bytes at end of array
		for(int i=bytes.length-1; i>=0; i--) {
			bytes[i] = (byte)(value & 0xFF);
			value >>= 8;
		}
		return bytes;
	}

	public static int numberOfBytesToEncodeLength(long length) {
		if (length<0) throw new IllegalArgumentException("Length is negative: " + length);
		if (length < (1L<<7 - 1)) return 1;
		if (length < (1L<<14 - 1)) return 2;
		if (length < (1L<<21 - 1)) return 3;
		if (length < (1L<<28 - 1)) return 4;
		if (length < (1L<<35 - 1)) return 5;
		if (length < (1L<<42 - 1)) return 6;
		if (length < (1L<<49 - 1)) return 7;
		if (length < (1L<<56 - 1)) return 8;
		throw new IllegalArgumentException("Length exceeds maximum: " + length);
	}

	// bytesReadOut is optional output parameter that will have the total number of bytes read stored at index 0
	public static long readEncodedLength(InputStream input, int[] bytesReadOut) {
		try {
			int lbyte = input.read();
			if (lbyte==-1) return -1;
			if (lbyte==0) throw new IllegalStateException("First length byte is 0");
			// there will always be 24 extra leading zeros from the first 3 bytes
			int nbytes = 1 + Integer.numberOfLeadingZeros(lbyte) - 24;

			// start with the first byte with the leading 1 masked out
			long value = lbyte ^ Integer.highestOneBit(lbyte);
			// read successive bytes, shifting current value each time (big-endian, most significant bytes first)
			for(int i=1; i<nbytes; i++) {
				lbyte = input.read();
				if (lbyte==-1) return -1;
				value = (value<<8) | lbyte;
			}
			if (bytesReadOut!=null) bytesReadOut[0] = nbytes;
			return value;
		}
		catch(IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static long readEncodedLength(InputStream input) {
		return readEncodedLength(input, null);
	}

	public static byte[] dataBytesForLong(long value, int minBytes) {
		// every 8 leading zeros is one less byte (than 8) needed
		int numBytes = 8 - (Long.numberOfLeadingZeros(value) / 8);
		if (numBytes<minBytes) numBytes = minBytes;

		byte[] bytes = new byte[numBytes];
		// big-endian, least significant bytes at end
		for(int i=0; i<bytes.length; i++) {
			bytes[bytes.length-i-1] = (byte)(value & 0xFF);
			value >>>= 8;
		}
		return bytes;
	}

	public static byte[] dataBytesForLong(long value) {
		return dataBytesForLong(value, 1);
	}

	public static long longForDataBytes(byte[] bytes) {
		long value = 0;
		for(int i=0; i<bytes.length; i++) {
			value <<= 8;
			long byteval = bytes[i] & 0xFF;
			value |= byteval;
		}
		return value;
	}

	// floating-point values must be exactly 4 or 8 bytes
	public static byte[] dataBytesForFloat(float value) {
		// make sure upper 32 bits are zero (if value is negative they'll get filled with ones by promotion to long)
		long floatBits = Float.floatToIntBits(value) & 0xFFFFFFFFL;
		return dataBytesForLong(floatBits, 4);
	}

	public static byte[] dataBytesForDouble(double value) {
		long doubleBits = Double.doubleToLongBits(value);
		return dataBytesForLong(doubleBits, 8);
	}

}
