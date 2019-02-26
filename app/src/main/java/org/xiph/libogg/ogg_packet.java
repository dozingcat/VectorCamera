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

public class ogg_packet {

	public byte[] packet;	// unsigned char *packet;
	public int bytes;	// long
	public int b_o_s;	// long
	public int e_o_s;	// long

	public int granulepos;	// ogg_int64_t  
  
	public int packetno;	// ogg_int64_t  

	// sequence number for decode; the framing knows where there's a hole in the data, but we need 
	// coupling so that the codec (which is in a seperate abstraction layer) also knows about the gap


	public ogg_packet() {}

}