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

package org.xiph.libvorbis;

import org.xiph.libogg.*;

public class vorbis_comment {
	
	// unlimited user comment fields.  libvorbis writes 'libvorbis' whatever vendor is set to in encode
	
	 public byte[][] user_comments;
	 public int[] comment_lengths; 
	 public int comments;
	 public byte[] vendor;
	 
	 public vorbis_comment() {
		 
		 vorbis_comment_init();
	 }
	
	 public void vorbis_comment_init() {
		 
		 user_comments=null;
		 comments=0;
		 vendor=null;	 
	 }
	 
	 public void vorbis_comment_add( String comment ) {
		 
		 add(comment.getBytes());	 
	 }
	 
	 private void add( byte[] comment ) {
		 
		 byte[][] foo=new byte[comments+2][];
		 if (user_comments!=null) {
			 System.arraycopy(user_comments, 0, foo, 0, comments);	 
		 }
		 user_comments=foo;
		 
		 int[] goo=new int[comments+2];
		 if (comment_lengths!=null) {
			 System.arraycopy(comment_lengths, 0, goo, 0, comments);
		 }
		 comment_lengths=goo;
		 
		 byte[] bar=new byte[comment.length+1];
		 System.arraycopy(comment, 0, bar, 0, comment.length);
		 user_comments[comments]=bar;
		 comment_lengths[comments]=comment.length;
		 comments++;
		 user_comments[comments]=null;	 
	 }
	 
	 public void vorbis_comment_add_tag( String tag, String contents ) {
		 
		 if (contents==null)
			 contents = "";
		 vorbis_comment_add( tag +"="+ contents );	 
	 }
	 
	 public boolean vorbis_commentheader_out( ogg_packet op ) {
		 
		 oggpack_buffer opb = new oggpack_buffer();
		 if ( !opb._vorbis_pack_comment( this ) )
				return false;
			
		// build the packet
		op.packet = new byte[ opb.oggpack_bytes() ];
		// memcpy(b->header1,opb.buffer,oggpack_bytes(&opb));
		System.arraycopy( opb.buffer, 0, op.packet, 0, opb.oggpack_bytes() );
		op.bytes = opb.oggpack_bytes();
		op.b_o_s = 0;
		op.e_o_s = 0;
		op.granulepos = 0;
		op.packetno = 1;
		
		return true;
	 }
}

