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

public class noise3 {

	int[][] data;		// data[P_NOISECURVES][17]


	public noise3( int[][] _data ) {

		data = new int[ P_NOISECURVES ][ P_BANDS ];
		for ( int i=0; i < _data.length; i++ )
			System.arraycopy( _data[i], 0, data[i], 0, _data[i].length );
	}

	public noise3( noise3 src ) {

		this( src.data );
	}
}
