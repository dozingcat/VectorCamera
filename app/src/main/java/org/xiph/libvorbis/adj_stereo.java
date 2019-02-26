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

import static org.xiph.libvorbis.vorbis_constants.integer_constants.*;

public class adj_stereo {

	int[] pre;
	int[] post;
	float[] kHz;
	float[] lowpasskHz;


	public adj_stereo( int[] _pre, int[] _post, float[] _kHz, float[] _lowpasskHz ) {

		pre = new int[ PACKETBLOBS ];
		System.arraycopy( _pre, 0, pre, 0, _pre.length );

		post = new int[ PACKETBLOBS ];
		System.arraycopy( _post, 0, post, 0, _post.length );

		kHz = new float[ PACKETBLOBS ];
		System.arraycopy( _kHz, 0, kHz, 0, _kHz.length );

		lowpasskHz = new float[ PACKETBLOBS ];
		System.arraycopy( _lowpasskHz, 0, lowpasskHz, 0, _lowpasskHz.length );
	}

	public adj_stereo( adj_stereo src ) {

		this( src.pre, src.post, src.kHz, src.lowpasskHz );
	}
}