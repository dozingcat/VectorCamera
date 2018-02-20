package com.dozingcatsoftware.ebml;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Stack;

public class EBMLFileWriter {
	
	// We need a random access file because typically for container elements the content size is initially unknown, so 
	// we have to write a placeholder value (using the full 8 bytes) and go back and fill it in when we know the size.
	RandomAccessFile randomAccessFile;

	// As we write container elements, keep track of the total lengths of their sub-elements. Record the starting position
	// so that when the container is closed, we can go back and write the correct length.
	static class ContainerStackEntry {
		public EBMLContainerElement container;
		public long startPosition;
		public long contentLength = 0;
	}
	
	Stack<ContainerStackEntry> containerElementStack = new Stack<ContainerStackEntry>();
	
	public EBMLFileWriter(String path) throws FileNotFoundException {
		File f = new File(path);
		if (f.exists()) f.delete();
		randomAccessFile = new RandomAccessFile(f, "rw");
	}

	public void writeElement(EBMLElement element) throws IOException {
		element.writeToStream(randomAccessFile);
		// update parent container with the total length of this element
		if (!containerElementStack.empty()) {
			ContainerStackEntry parent = containerElementStack.peek();
			parent.contentLength += element.getTotalSize();
		}
	}
	
	public void writeElement(String hexID, Object value) throws IOException {
		writeElement(EBMLElement.createWithHexID(hexID, value));
	}
	
	public void startContainerElement(EBMLContainerElement container) throws IOException {
		ContainerStackEntry stackEntry = new ContainerStackEntry();
		stackEntry.container = container;
		stackEntry.startPosition = randomAccessFile.getFilePointer();
		containerElementStack.push(stackEntry);		
		
		container.writeToStream(randomAccessFile);
	}
	
	public void startContainerElement(String hexID) throws IOException {
		startContainerElement(EBMLContainerElement.createWithHexID(hexID));
	}
	
	public EBMLContainerElement endContainerElement() throws IOException {
		ContainerStackEntry stackEntry = containerElementStack.pop();
		// the length should be correct, so go back to the length placeholder and write the correct length
		long currentPosition = randomAccessFile.getFilePointer();
		// The location to write the length is the saved start position, plus the number of ID bytes, plus 1 for the 
		// leading 01 length byte (which indicates that the length is stored in the next 7 bytes).
		long lengthPosition = stackEntry.startPosition + stackEntry.container.getElementType().getIDLength() + 1;
		randomAccessFile.seek(lengthPosition);
		// EBMLElementContainer.writeElement always writes 8 bytes for the length as a placeholder, first byte is always 01
		byte[] lengthBytes = EBMLUtilities.dataBytesForLong(stackEntry.contentLength, 7);
		randomAccessFile.write(lengthBytes);
		
		randomAccessFile.seek(currentPosition);
		
		// if there's a parent container of this container, update its content size.
		// The total size of this container its content size, plus the length of its ID, plus 8 length bytes
		if (!containerElementStack.empty()) {
			ContainerStackEntry parent = containerElementStack.peek();
			parent.contentLength += stackEntry.contentLength + stackEntry.container.getElementType().getIDLength() + 8;
		}
		
		stackEntry.container.setContentSize(stackEntry.contentLength);
		return stackEntry.container;
	}
	
	public void close() throws IOException {
		randomAccessFile.close();
	}
}
