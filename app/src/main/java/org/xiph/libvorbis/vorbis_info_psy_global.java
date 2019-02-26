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

public class vorbis_info_psy_global {

	int eighth_octave_lines;

	// for block long/short tuning; encode only
	float[] preecho_thresh;			// preecho_thresh[VE_BANDS]
	float[] postecho_thresh;		// postecho_thresh[VE_BANDS]
	float stretch_penalty;
	float preecho_minenergy;
	
	float ampmax_att_per_sec;

	int[] coupling_pkHz;			// coupling_pkHz[PACKETBLOBS]
	int[][] coupling_pointlimit;	// coupling_pointlimit[2][PACKETBLOBS]
	int[] coupling_prepointamp;		// coupling_prepointamp[PACKETBLOBS]
	int[] coupling_postpointamp;	// coupling_postpointamp[PACKETBLOBS]
	int[][] sliding_lowpass;		// sliding_lowpass[2][PACKETBLOBS]


	public vorbis_info_psy_global( int _eighth_octave_lines, float[] _preecho_thresh, float[] _postecho_thresh, float _stretch_penalty, float _preecho_minenergy, float _ampmax_att_per_sec,
					int[] _coupling_pkHz, int[][] _coupling_pointlimit, int[] _coupling_prepointamp, int[] _coupling_postpointamp, int[][] _sliding_lowpass ) {

		eighth_octave_lines = _eighth_octave_lines;

		preecho_thresh = new float[ VE_BANDS ];
		System.arraycopy( _preecho_thresh, 0, preecho_thresh, 0, _preecho_thresh.length );

		postecho_thresh = new float[ VE_BANDS ];
		System.arraycopy( _postecho_thresh, 0, postecho_thresh, 0, _postecho_thresh.length );

		stretch_penalty = _stretch_penalty;
		preecho_minenergy = _preecho_minenergy;
		ampmax_att_per_sec = _ampmax_att_per_sec;

		coupling_pkHz = new int[ PACKETBLOBS ];
		System.arraycopy( _coupling_pkHz, 0, coupling_pkHz, 0, _coupling_pkHz.length );

		coupling_pointlimit = new int[2][ PACKETBLOBS ];
		for ( int i=0; i < _coupling_pointlimit.length; i++ )
			System.arraycopy( _coupling_pointlimit[i], 0, coupling_pointlimit[i], 0, _coupling_pointlimit[i].length );


		coupling_prepointamp = new int[ PACKETBLOBS ];
		System.arraycopy( _coupling_prepointamp, 0, coupling_prepointamp, 0, _coupling_prepointamp.length );

		coupling_postpointamp = new int[ PACKETBLOBS ];
		System.arraycopy( _coupling_postpointamp, 0, coupling_postpointamp, 0, _coupling_postpointamp.length );

		sliding_lowpass = new int[2][ PACKETBLOBS ];
		for ( int i=0; i < _sliding_lowpass.length; i++ )
			System.arraycopy( _sliding_lowpass[i], 0, sliding_lowpass[i], 0, _sliding_lowpass[i].length );
	}

	public vorbis_info_psy_global( vorbis_info_psy_global src ) {

		this( src.eighth_octave_lines, src.preecho_thresh, src.postecho_thresh, src.stretch_penalty, src.preecho_minenergy, src.ampmax_att_per_sec,
					src.coupling_pkHz, src.coupling_pointlimit, src.coupling_prepointamp, src.coupling_postpointamp, src.sliding_lowpass );
	}
}
