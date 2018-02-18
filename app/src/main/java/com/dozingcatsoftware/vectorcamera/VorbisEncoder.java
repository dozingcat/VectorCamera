package com.dozingcatsoftware.vectorcamera;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import org.xiph.libogg.ogg_packet;
import org.xiph.libogg.ogg_page;
import org.xiph.libogg.ogg_stream_state;
import org.xiph.libvorbis.vorbis_block;
import org.xiph.libvorbis.vorbis_comment;
import org.xiph.libvorbis.vorbis_dsp_state;
import org.xiph.libvorbis.vorbis_info;
import org.xiph.libvorbis.vorbisenc;

public class VorbisEncoder implements Iterator<ogg_page> {

    public static interface Delegate {
        public void createdOggPage(ogg_page page, int byteStartPosition);
    }

    public static class HeaderPackets {
        ogg_packet header, commentHeader, codecHeader;
    }

    InputStream pcmInput;
    int rate = 44100;
    int channels = 2;
    int bits = 16;
    boolean littleEndian = true;

    int totalBytesRead = 0;
    int previousBytesRead = 0;
    boolean eos = false;
    int READ = 1024;
    byte[] readbuffer = new byte[READ*4+44];

    boolean initialized = false;
    boolean inHeader = true;

    HeaderPackets headerPackets = new HeaderPackets();

    vorbis_info vi;
    vorbis_comment vc;
    vorbisenc encoder;
    vorbis_dsp_state vd;
    ogg_stream_state os;

    vorbis_block vb;
    ogg_page og;
    ogg_packet op;

    public VorbisEncoder(InputStream pcmInput) {
        this.pcmInput = pcmInput;
        init();
    }

    void init() {
        vi = new vorbis_info();
        encoder = new vorbisenc();
        if ( !encoder.vorbis_encode_init_vbr( vi, channels, rate, .3f ) ) {
            throw new RuntimeException( "Failed to Initialize vorbisenc" );
        }

        vd = new vorbis_dsp_state();
        if ( !vd.vorbis_analysis_init( vi ) ) {
            throw new RuntimeException( "Failed to Initialize vorbis_dsp_state" );
        }

        vb = new vorbis_block( vd );


        java.util.Random generator = new java.util.Random();  // need to randomize seed
        os = new ogg_stream_state( generator.nextInt(256) );

        vc = new vorbis_comment();
        vc.vorbis_comment_add_tag( "ENCODER", "Java Vorbis Encoder" );

        headerPackets.header = new ogg_packet();
        headerPackets.commentHeader = new ogg_packet();
        headerPackets.codecHeader = new ogg_packet();

        vd.vorbis_analysis_headerout( vc, headerPackets.header, headerPackets.commentHeader, headerPackets.codecHeader );
        os.ogg_stream_packetin( headerPackets.header); // automatically placed in its own page
        os.ogg_stream_packetin( headerPackets.commentHeader );
        os.ogg_stream_packetin( headerPackets.codecHeader );

        og = new ogg_page();
        op = new ogg_packet();
    }

    public HeaderPackets getHeaderPackets() {
        return headerPackets;
    }

    @Override
    public boolean hasNext() {
        return !eos;
    }

    void readFromInput() throws IOException {
        int bytes = pcmInput.read( readbuffer, 0, READ*2 ); // mono
        totalBytesRead += bytes;

        if ( bytes==0 ) {
            vd.vorbis_analysis_wrote( 0 );
        }
        else {
            // data to encode
            // expose the buffer to submit data
            float[][] buffer = vd.vorbis_analysis_buffer( READ );
            int i;
            // for mono, 16-bit little-endian
            for(i=0; i<bytes/2; i++) {
                buffer[0][vd.pcm_current + i] = ( (readbuffer[i*2+1]<<8) | (0x00ff&readbuffer[i*2]) ) / 32768.f;
                buffer[1][vd.pcm_current + i] = buffer[0][vd.pcm_current + i];
            }
            // tell the library how much we actually submitted
            vd.vorbis_analysis_wrote( i );
        }

    }

    /** The natural form of this code is something like:
     *
     * while (!eos) {
     *     readFromInput();
     *     while (vb.vorbis_analysis_blockout(vd)) {
     *         ...
     *         while (vd.vorbis_bitrate_flushpacket(op)) {
     *             ...
     *             while (os.ogg_stream_pageout(og)) {
     *                 [handle Ogg page]
     *             }
     *         }
     *     }
     * }
     *
     * But to make it work in iterator/generator style, we have to invert the calling sequence and pick up
     * where we left off each time. The state ivar keeps track of which "loop" we're in, starting at 0
     * means we'll call readFromInput first.
     */
    int state = 0;

    @Override
    public ogg_page next() {
        if (eos) return null;

        // The first page(s) returned will contain the header pages. When writing Matroska/WebM files we handle them separately
        // so isInHeader can be used to check. When writing Ogg files directly header pages are written the same as others.
        if (inHeader) {
            if (os.ogg_stream_flush( og ) ) {
                return og;
            }
            else {
                inHeader = false;
            }
        }

        previousBytesRead = totalBytesRead;
        while(true) {
            if (state==3 && os.ogg_stream_pageout(og)) {
                eos = (og.ogg_page_eos()>0);
                return og;
            }
            else {
                state = 2;
            }
            // no page available, any more packets available?
            if (state==2 && vd.vorbis_bitrate_flushpacket( op )) {
                os.ogg_stream_packetin( op );
                state = 3;
                continue;
            }
            else {
                state = 1;
            }

            if (state==1 && vb.vorbis_analysis_blockout( vd ) ) {
                // analysis, assume we want to use bitrate management
                vb.vorbis_analysis( null );
                vb.vorbis_bitrate_addblock();
                state = 2;
                continue;
            }
            else {
                state = 0;
            }

            try {
                readFromInput();
                state = 1;
            }
            catch(IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /** Returns the timestamp of the start of the Ogg page that was last returned by next()
     */
    public double getLastPageTime() {
        return (previousBytesRead/2.0) / rate;
    }

    /** Returns the timestamp of the next Ogg page that will be returned by next()
     */
    public double getNextPageTime() {
        return (totalBytesRead/2.0) / rate;
    }

    public long getBytesRead() {
        return totalBytesRead;
    }

    public boolean isInHeader() {
        return inHeader;
    }

    @Override
    public void remove() {
        throw new RuntimeException("remove() not supported in VorbisEncoder");
    }

    /** Returns a byte array containing the "CodecPrivate" data that should be inserted into a Matroska/WebM container.
     * This is the first 3 header packets, preceded by their lengths. (Actually just the first 2 lengths).
     */
    public byte[] matroskaCodecPrivateData() {
        ByteArrayOutputStream codecPrivateBytes = new ByteArrayOutputStream();
        codecPrivateBytes.write(0x02); // fixed, indicates 2+1 packets in content
        codecPrivateBytes.write((byte)headerPackets.header.packet.length); // length of first packet, hopefully <127
        codecPrivateBytes.write((byte)headerPackets.commentHeader.packet.length); // length of second packet, ditto
        try {
            codecPrivateBytes.write(headerPackets.header.packet);
            codecPrivateBytes.write(headerPackets.commentHeader.packet);
            codecPrivateBytes.write(headerPackets.codecHeader.packet);
        }
        catch(IOException ex) {
            throw new RuntimeException(ex);
        }
        return codecPrivateBytes.toByteArray();
    }

}
