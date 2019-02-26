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

public class att3 {

	int[] att;		// att[P_NOISECURVES]
	float boost;
	float decay;


	public att3( int[] _att, float _boost, float _decay ) {

		att = new int[ P_NOISECURVES ];
		System.arraycopy( _att, 0, att, 0, _att.length );

		boost = _boost;
		decay = _decay;
	}

	public att3( att3 src ) {

		this( src.att, src.boost, src.decay );
	}
}
