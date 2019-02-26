/********************************************************************
 *                                                                  *
 * THIS FILE IS PART OF THE OggVorbis SOFTWARE CODEC SOURCE CODE.   *
 * USE, DISTRIBUTION AND REPRODUCTION OF THIS LIBRARY SOURCE IS     *
 * GOVERNED BY A BSD-STYLE SOURCE LICENSE INCLUDED WITH THIS SOURCE *
 * IN 'COPYING'. PLEASE READ THESE TERMS BEFORE DISTRIBUTING.       *
 *                                                                  *
 * THE OggVorbis SOURCE CODE IS (C) COPYRIGHT 1994-2002             *
 * by the Xiph.Org Foundation http://www.xiph.org/                  *
 *                                                                  *
 ********************************************************************/

package org.xiph.libogg;

public class ogg_stream_state {
	
	byte[] body_data;	// unsigned char // bytes from packet bodies
	int body_storage;	// long //storage elements allocated
	int body_fill;		// long //elements stored; fill mark
	int body_returned;	// long //elements of fill returned
	
	int[] lacing_vals;		// The values that will go to the segment table
	long[] granule_vals;	// ogg_int64_t //granulepos values for headers. Not compact this way, but it is simple coupled to the lacing fifo
	int lacing_storage;		// long
	int lacing_fill;		// long
	int lacing_packet;		// long
	int lacing_returned;	// long

	byte[] header;		// unsigned char    header[282];      //working space for header encode
	int header_fill;

	int e_o_s;		//set when we have buffered the last packet in the logical bitstream
	int b_o_s;		//set after we've written the initial page of a logical bitstream
	int serialno;	// long
	int pageno;		// long
	long packetno;	// ogg_int64_t  // sequence number for decode; the framing knows where there's a hole in the data, but we need coupling so
					// that the codec (which is in a seperate abstraction layer) also knows about the gap

	long granulepos;		// ogg_int64_t   


	public ogg_stream_state () {

		header = new byte[ 282 ];
	}

	public ogg_stream_state( int _serialno ) {
		
		header = new byte[ 282 ];
		
		body_storage = 16*1024;

		// body_data = _ogg_malloc(os->body_storage*sizeof(*os->body_data));
		body_data = new byte[ body_storage ];

		lacing_storage = 1024;
		lacing_vals = new int[ lacing_storage ];
		granule_vals = new long[ lacing_storage ];

		serialno = _serialno;
	}
	
	public int getSerialNo() {
		return serialno;
	}

	// Helpers for ogg_stream_encode; this keeps the structure and what's happening fairly clear
	
	public void _os_body_expand( int needed ) {
		
		if ( body_storage <= body_fill + needed ) {
			
			body_storage += (needed+1024);
			
			// body_data = _ogg_realloc(os->body_data,os->body_storage*sizeof(*os->body_data));
			byte[] temp = new byte[ body_storage ];
    		System.arraycopy( body_data, 0, temp, 0, body_data.length );
    		body_data = temp;
		}
	}

	public void _os_lacing_expand( int needed ) {
		
		if ( lacing_storage <= lacing_fill + needed ) {
			
			lacing_storage += (needed+32);
			
			// lacing_vals = _ogg_realloc(os->lacing_vals,os->lacing_storage*sizeof(*os->lacing_vals));
			int[] temp = new int[ lacing_storage ];
    		System.arraycopy( lacing_vals, 0, temp, 0, lacing_vals.length );
    		lacing_vals = temp;
    		
			// granule_vals = _ogg_realloc(os->granule_vals,os->lacing_storage*sizeof(*os->granule_vals));
    		long[] temp2 = new long[ lacing_storage ];
    		System.arraycopy( granule_vals, 0, temp, 0, granule_vals.length );
    		granule_vals = temp2;
	  }
	}
	
	public boolean ogg_stream_packetin( ogg_packet op ) {
		
// OOP varaible lacing_vals renamed lacing_vals_local
		int lacing_vals_local = op.bytes/255+1;
		int i;
		
		if ( body_returned > 0 ) {
			
			/* advance packet data according to the body_returned pointer. We
			 * had to keep it around to return a pointer into the buffer last call */
			
			body_fill -= body_returned;
			if ( body_fill > 0 ) {
				// memmove( body_data, body_data + body_returned, body_fill );
	    		System.arraycopy( body_data, body_returned, body_data, 0, body_fill );
			}
			body_returned = 0;
		}
		
		// make sure we have the buffer storage
		_os_body_expand( op.bytes );
		_os_lacing_expand( lacing_vals_local );
		
		/* Copy in the submitted packet.  Yes, the copy is a waste; this is
		 * the liability of overly clean abstraction for the time being.  It
		 * will actually be fairly easy to eliminate the extra copy in the future */
		
		// memcpy( body_data + body_fill, op.packet, op.bytes );
		System.arraycopy( op.packet, 0, body_data, body_fill, op.bytes );
		body_fill += op.bytes;
		
		// Store lacing vals for this packet
		for ( i=0; i < lacing_vals_local-1; i++ ) {
			lacing_vals[ lacing_fill+i ] = 255;
			granule_vals[ lacing_fill+i ] = granulepos;
		}
		
		lacing_vals[ lacing_fill+i ] = (op.bytes)%255;
		granulepos = granule_vals[ lacing_fill+i] = op.granulepos;
		
		// flag the first segment as the beginning of the packet
		lacing_vals[ lacing_fill ] |= 0x100;
		lacing_fill += lacing_vals_local;
		
		// for the sake of completeness
		packetno++;
		
		if ( op.e_o_s > 0 )
			e_o_s = 1;
		
		return true;
	}
	
	/* This will flush remaining packets into a page (returning nonzero),
	 * even if there is not enough data to trigger a flush normally
	 * (undersized page). If there are no packets or partial packets to
	 * flush, ogg_stream_flush returns 0.  Note that ogg_stream_flush will
	 * try to flush a normal sized page like ogg_stream_pageout; a call to
	 * ogg_stream_flush does not guarantee that all packets have flushed.
	 * Only a return value of 0 from ogg_stream_flush indicates all packet
	 * data is flushed into pages.
	 * 
	 * since ogg_stream_flush will flush the last page in a stream even if
	 * it's undersized, you almost certainly want to use ogg_stream_pageout
	 * (and *not* ogg_stream_flush) unless you specifically need to flush 
	 * an page regardless of size in the middle of a stream. */
	
	public boolean ogg_stream_flush( ogg_page og ) {
		
		int maxvals = (lacing_fill>255?255:lacing_fill);
		if ( maxvals == 0 )
			return false;
		
		int i;
		int vals=0;
		int bytes=0;
		int acc=0;
		// ogg_int64_t granule_pos=os->granule_vals[0];
		long granule_pos = granule_vals[0];


		// construct a page
		// decide how many segments to include
		
		// If this is the initial header case, the first page must only include the initial header packet
		if ( b_o_s == 0 ) {  // 'initial header page' case
			granule_pos=0;
			for ( vals=0; vals < maxvals; vals++ ) {
				if ( (lacing_vals[vals]&0x0ff) < 255 ) {
					vals++;
					break;
				}
			}
		} else {
			for ( vals=0; vals < maxvals; vals++ ) {
				if ( acc > 4096 )
					break;
				acc += lacing_vals[vals]&0x0ff;
				granule_pos = granule_vals[vals];
			}
		}
		
		// construct the header in temp storage
		// memcpy(os->header,"OggS",4);
		// header[0] = 'O';
		// header[1] = 'g';
		// header[2] = 'g';
		// header[3] = 'S';
		System.arraycopy( "OggS".getBytes(), 0, header, 0, 4 );
		
		// stream structure version
		header[4] = 0x00;
		
		// continued packet flag?
		header[5] = 0x00;
		if ( (lacing_vals[0]&0x100) == 0 )
			header[5] |= 0x01;
		// first page flag?
		if ( b_o_s == 0 )
			header[5] |= 0x02;
		// last page flag?
		if ( (e_o_s > 0) && (lacing_fill == vals) )
			header[5] |= 0x04;
		b_o_s = 1;
		
		// 64 bits of PCM position
		for ( i=6; i < 14; i++ ) {
			header[i] = (byte)(granule_pos);
			granule_pos >>>= 8;
		}
		
		// 32 bits of stream serial number
// local OOP varname serialno to serialno_local
		int serialno_local = serialno;
		for ( i=14; i <18; i++ ) {
			header[i] = (byte)(serialno_local);
			serialno_local >>>= 8;
		}
		
		// 32 bits of page counter (we have both counter and page header because this val can roll over)
		if ( pageno == -1)
			pageno = 0; 
		
		/* because someone called stream_reset; this would be a strange thing to do in an
		 * encode stream, but it has plausible uses */
		
// local OOP varname serialno to serialno_local
		int pageno_local = pageno++;
		for ( i=18; i < 22; i++ ) {
			header[i] = (byte)(pageno_local);
			pageno_local >>>= 8;
		}
		
		// zero for computation; filled in later
		header[22] = 0;
		header[23] = 0;
		header[24] = 0;
		header[25] = 0;
		
		// segment table
		header[26] = (byte)(vals);
		for ( i=0; i < vals; i++ ) {
			header[i+27] = (byte)(lacing_vals[i]);
			bytes += (header[i+27] & 0xff);
		}
			
		
// this has been hacked together
// do not know if pointers (offsets) are ok
// or if actual data needs to be copied to ogg_page
		
		// set pointers in the ogg_page struct
		og.header = header;
		og.header_len = header_fill = vals+27;
		// og.body = body_data + body_returned;
		og.body = new byte[ body_data.length - body_returned ];
		System.arraycopy( body_data, body_returned, og.body, 0, body_data.length - body_returned );
		og.body_len = bytes;
		
		// advance the lacing data and set the body_returned pointer
		lacing_fill -= vals;
		
		// memmove(os->lacing_vals, os->lacing_vals+vals, os->lacing_fill*sizeof(*os->lacing_vals));
		// int[] temp = new int[ lacing_fill ];
		// System.arraycopy( lacing_vals, vals, temp, 0, lacing_fill );
		// lacing_vals = temp;
		// System.arraycopy( lacing_vals, vals, lacing_vals, 0, lacing_fill*4 );
		System.arraycopy( lacing_vals, vals, lacing_vals, 0, lacing_fill );
		
		
		// memmove(os->granule_vals, os->granule_vals+vals, os->lacing_fill*sizeof(*os->granule_vals));
		// long[] temp2 = new long[ lacing_fill ];
		// System.arraycopy( granule_vals, vals, temp2, 0, lacing_fill );
		// granule_vals= temp2;
		// System.arraycopy( granule_vals, vals, granule_vals, 0, lacing_fill*8 );
		System.arraycopy( granule_vals, vals, granule_vals, 0, lacing_fill );
		
		body_returned += bytes;
		
		// calculate the checksum
		og.ogg_page_checksum_set();
		
		return true;
	}
	
	/* This constructs pages from buffered packet segments.  The pointers
	 * returned are to static buffers; do not free. The returned buffers are
	 * good only until the next call (using the same ogg_stream_state) */

	public boolean ogg_stream_pageout( ogg_page og ) {
		
		if ( (e_o_s > 0 && lacing_fill > 0 ) ||		// 'were done, now flush' case
			body_fill-body_returned > 4096 ||		// 'page nominal size' case
			lacing_fill >= 255 ||					// 'segment table full' case
			(lacing_fill > 0 && b_o_s <= 0) ) {		// 'initial header page' case
	        
	    return ogg_stream_flush(og);
	  }
	  
	  // not enough data to construct a page and not end of stream
	  return false;
	}
	
}