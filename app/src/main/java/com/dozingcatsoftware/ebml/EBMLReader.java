package com.dozingcatsoftware.ebml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Stack;

public class EBMLReader {
	
	public static interface Delegate {
		public void parsedEBMLElement(EBMLElement element);
		public void startedEBMLContainerElement(EBMLContainerElement element);
		public void finishedEBMLContainerElement(EBMLContainerElement element);
		public void finishedEBMLStream();
	}
	
	InputStream input;
	long bytesRead;
	Delegate delegate;
	
	static class ContainerStackEntry {
		public EBMLContainerElement container;
		public long endPosition;
	}
	
	Stack<ContainerStackEntry> containerStack;
	
	public EBMLReader(InputStream input, Delegate delegate) {
		this.input = input;
		this.delegate = delegate;
	}
	
	public void go() {
		containerStack = new Stack<ContainerStackEntry>();
		
		while (true) {
			try {
				byte[] idBytes = readElementID();
				if (idBytes==null) break;
				EBMLElement element = EBMLElementType.elementTypeForBytes(idBytes).createElement();
				// capture number of bytes storing the length, so we can increment bytesRead correctly
				int[] numLengthBytes = new int[1];
				long length = EBMLUtilities.readEncodedLength(input, numLengthBytes);
				this.bytesRead += numLengthBytes[0];
				if (length<=0) break;
				if (element instanceof EBMLContainerElement) {
					EBMLContainerElement container = (EBMLContainerElement)element;
					container.setContentSize(length);
					delegate.startedEBMLContainerElement(container);
					// push onto container stack with ending position
					ContainerStackEntry stackEntry = new ContainerStackEntry();
					stackEntry.container = container;
					stackEntry.endPosition = this.bytesRead + container.getContentSize();
					containerStack.push(stackEntry);
				}
				else {
					byte[] value = readBytes((int)length);
					element.setValue(value);
					delegate.parsedEBMLElement(element);
					// check for closed containers
					while (!containerStack.empty()) {
						ContainerStackEntry stackEntry = containerStack.peek();
						if (bytesRead >= stackEntry.endPosition) {
							containerStack.pop();
							delegate.finishedEBMLContainerElement(stackEntry.container);
						}
						else {
							break;
						}
					}
				}
			}
			catch(Exception ex) {
				break;
			}
		}
		delegate.finishedEBMLStream();
	}
	

	int readByte() throws IOException {
		int value = input.read();
		if (value!=-1) bytesRead++;
		return value;
	}
	
	byte[] readBytes(int numBytes) throws IOException {
		byte[] bytes = new byte[numBytes];
		int nbytes = 0;
		while (nbytes < numBytes) {
			int result = this.input.read(bytes, nbytes, bytes.length-nbytes);
			if (result==-1) return null;
			nbytes += result;
		}
		this.bytesRead += numBytes;
		return bytes;
	}
	
	public byte[] readElementID() {
		try {
			int first = readByte();
			if (first==-1) return null;
			if (first >= 0x80) {
				// one-byte ID
				return new byte[] {(byte)first};
			}
			
			int second = readByte();
			if (second==-1) return null;
			if (first >= 0x40) {
				// two-byte ID
				return new byte[] {(byte)first, (byte)second};
			}
			
			int third = readByte();
			if (third==-1) return null;
			if (first >= 0x20) {
				// three-byte ID
				return new byte[] {(byte)first, (byte)second, (byte)third};
			}
			
			// must be four bytes
			int fourth = readByte();
			if (fourth==-1) return null;
			return new byte[] {(byte)first, (byte)second, (byte)third, (byte)fourth};
		}
		catch(Exception ex) {
			throw new RuntimeException(ex);
		}
	}
}
