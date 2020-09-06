package com.dozingcatsoftware.vectorcamera;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;

import org.xiph.libogg.ogg_page;

import com.dozingcatsoftware.ebml.EBMLContainerElement;
import com.dozingcatsoftware.ebml.EBMLElement;
import com.dozingcatsoftware.ebml.EBMLFileWriter;
import com.dozingcatsoftware.ebml.EBMLReader;
import com.dozingcatsoftware.ebml.EBMLUtilities;
import com.dozingcatsoftware.ebml.MatroskaID;

// This class was copied from WireGoggles; there was no need to modify it or convert it to Kotlin.
public class CombineAudioVideo {

    public interface EncoderDelegate {
        void receivedOggPage(long bytesRead);
    }

    /** Writes SimpleBlock elements containing Ogg pages to the EBML output file. Stops writing when the next Ogg page
     * timestamp would exceed timecodeLimit in seconds. If timecodeLimit is less than 0, all remaining Ogg pages
     * are written.
     */
    static void writeAudioBlocks(VorbisEncoder encoder, EBMLFileWriter writer, double timecodeLimit, long clusterStartMillis,
                                 EncoderDelegate encoderDelegate) throws Exception {
        ByteArrayOutputStream blockBytes = null;
        while (encoder.hasNext() && (timecodeLimit<0 || encoder.getNextPageTime()<=timecodeLimit)) {
            ogg_page og = encoder.next();
            if (encoder.isInHeader()) continue;

            if (blockBytes==null) blockBytes = new ByteArrayOutputStream();
            System.out.println(String.format("Header: %s, Body: %s, Total: %s", og.header_len, og.body_len, og.header_len+og.body_len));
            blockBytes.reset();
            blockBytes.write(0x82); // EBML-encoded track number (2)
            // timecode is 16-byte int (big-endian?), compute milliseconds from bytes read when this packet started
            int timecode = Math.max(0, (int)((1000*encoder.getLastPageTime()) - clusterStartMillis));
            blockBytes.write((timecode>>8) & 0xff);
            blockBytes.write(timecode & 0xff);
            System.out.println(timecode);

            // flags: keyframe (0x80) and Xiph lacing (0x02)
            blockBytes.write(0x82);
            // number of Ogg frames are in header at 26th byte, frame lengths follow
            int numFrames = og.header[26];
            assert og.header_len == 27 + numFrames;
            // write number of frames minus 1, then length of frames except for last
            blockBytes.write(numFrames-1);
            // if a packet length is 255, we need to write an extra 00 byte because of Xiph lacing (FF means keep going)
            for(int li=0; li<numFrames-1; li++) {
                int length = og.header[27+li] & 0xFF; // avoid sign extension for bytes>127
                blockBytes.write(length);
                if (length==0xFF) blockBytes.write(0x00);
            }

            // and finally the body
            blockBytes.write(og.body, 0, og.body_len);
            writer.writeElement(MatroskaID.SimpleBlock, blockBytes.toByteArray());

            if (encoderDelegate!=null) {
                encoderDelegate.receivedOggPage(encoder.getBytesRead());
            }
        }
    }

    static int timecodeOffsetForBlock(byte[] blockBytes) {
        return ((blockBytes[1] & 0xFF) << 8) | (blockBytes[2] & 0xFF);
    }

    static class TimecodePosition {
        long clusterTimecode = 0;
    }

    public static void insertAudioIntoWebm(String audioPath, String webmPath, String outputPath,
                                           final EncoderDelegate encoderDelegate) throws Exception {
        final EBMLFileWriter writer = new EBMLFileWriter(outputPath);
        final VorbisEncoder vorbisEncoder = new VorbisEncoder(new FileInputStream(audioPath));
        final TimecodePosition timecodePosition = new TimecodePosition();

        EBMLReader.Delegate ebmlDelegate = new EBMLReader.Delegate() {

            @Override
            public void parsedEBMLElement(EBMLElement element) {
                System.out.println(String.format("Copying element: %s with %s bytes (%s total)",
                        element.getElementType().getIDHexString(), element.getContentSize(), element.getTotalSize()));
                // This may miss audio blocks at the end, because it doesn't know to write the remaining blocks
                // once all video blocks have been read. To fix we could do a first pass using getClusterTimestamps
                // to figure out how many Clusters to expect, and then write remaining audio blocks before the last one ends.
                try {
                    // for Timecode element, update position
                    if (element.getElementType().hexIDMatches(MatroskaID.Timecode)) {
                        timecodePosition.clusterTimecode = EBMLUtilities.longForDataBytes((byte[])element.getValue());
                    }
                    else if (element.getElementType().hexIDMatches(MatroskaID.SimpleBlock)) {
                        // for (presumably video) SimpleBlock, write any audio blocks that come before it
                        int offset = timecodeOffsetForBlock((byte[])element.getValue());
                        double timecodeLimit = (timecodePosition.clusterTimecode + offset) / 1000.0;
                        writeAudioBlocks(vorbisEncoder, writer, timecodeLimit, timecodePosition.clusterTimecode, encoderDelegate);
                    }
                    // and copy the original element
                    writer.writeElement(element.getElementType().getIDHexString(), element.getValue());
                }
                catch(Exception ex) {throw new RuntimeException(ex);}
            }

            @Override
            public void startedEBMLContainerElement(EBMLContainerElement element) {
                System.out.println(String.format("Copying container: %s with size %s", element.getElementType().getIDHexString(),
                        (element.isUnknownSize() ? "unknown" : element.getContentSize())));
                try {
                    writer.startContainerElement(element.getElementType().getIDHexString());
                }
                catch(Exception ex) {throw new RuntimeException(ex);}
            }

            @Override
            public void finishedEBMLContainerElement(EBMLContainerElement element) {
                // write TrackEntry for audio track after the video TrackEntry
                boolean isTrackEntry = (MatroskaID.TrackEntry.equals(element.getElementType().getIDHexString().toLowerCase()));

                System.out.println("Finished container: " + element.getElementType().getIDHexString());
                try {
                    writer.endContainerElement();
                    if (isTrackEntry) {
                        System.out.println("Adding Vorbis TrackEntry");
                        writer.startContainerElement(MatroskaID.TrackEntry);
                        {
                            writer.writeElement(MatroskaID.TrackNumber, 2);
                            writer.writeElement(MatroskaID.TrackUID, 42); // does this matter?
                            writer.writeElement(MatroskaID.TrackType, 2); // audio
                            writer.writeElement(MatroskaID.CodecID, "A_VORBIS");
                            writer.writeElement(MatroskaID.CodecPrivate, vorbisEncoder.matroskaCodecPrivateData());
                            writer.writeElement(MatroskaID.Language, "und");
                            writer.startContainerElement(MatroskaID.Audio);
                            {
                                writer.writeElement(MatroskaID.SamplingFrequency, 44100.0f);
                                writer.writeElement(MatroskaID.Channels, 2);
                            }
                            writer.endContainerElement();
                        }
                        writer.endContainerElement();
                    }
                }
                catch(Exception ex) {throw new RuntimeException(ex);}
            }

            @Override
            public void finishedEBMLStream() {
                System.out.println("Done reading file");
            }

        };

        EBMLReader reader = new EBMLReader(new FileInputStream(webmPath), ebmlDelegate);
        reader.go();
    }

}
