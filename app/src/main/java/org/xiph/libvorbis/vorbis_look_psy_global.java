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

class vorbis_look_psy_global {

	float ampmax;
	int channels;

	vorbis_info_psy_global gi;
	int[][] coupling_pointlimit; 	// int coupling_pointlimit[2][P_NOISECURVES];


	public vorbis_look_psy_global( float _ampmax, int _channels, vorbis_info_psy_global _gi, int[][] _coupling_pointlimit ) {

		ampmax = _ampmax;
		channels = _channels;
		gi = _gi;

		coupling_pointlimit = new int[2][ P_NOISECURVES ];
		for ( int i=0; i < _coupling_pointlimit.length; i++ )
			System.arraycopy( _coupling_pointlimit[i], 0, coupling_pointlimit[i], 0, _coupling_pointlimit[i].length );
	}

	public vorbis_look_psy_global( vorbis_look_psy_global src ) {

		this( src.ampmax, src.channels, new vorbis_info_psy_global( src.gi ), src.coupling_pointlimit );
	}

	public vorbis_look_psy_global( vorbis_info _vi ) {

		coupling_pointlimit = new int[2][ P_NOISECURVES ];
		
		channels = _vi.channels;
		
		ampmax = -9999.0f;
		gi = _vi.codec_setup.psy_g_param;
	}
}

