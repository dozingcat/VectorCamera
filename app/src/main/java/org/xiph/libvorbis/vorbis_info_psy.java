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

class vorbis_info_psy {

	int blockflag;

	float ath_adjatt;
	float ath_maxatt;

	float[] tone_masteratt;		// tone_masteratt[P_NOISECURVES]
	float tone_centerboost;
	float tone_decay;
	float tone_abs_limit;
	float[] toneatt;			// toneatt[P_BANDS]

	int noisemaskp;
	float noisemaxsupp;
	float noisewindowlo;
	float noisewindowhi;
	int noisewindowlomin;
	int noisewindowhimin;
	int noisewindowfixed;
	float[][] noiseoff;			// noiseoff[P_NOISECURVES][P_BANDS]
	float[] noisecompand;		// noisecompand[NOISE_COMPAND_LEVELS]

	float max_curve_dB;

	int normal_channel_p;
	int normal_point_p;
	int normal_start;
	int normal_partition;
	float normal_thresh;		// double normal_thresh


	public vorbis_info_psy( int _blockflag, float _ath_adjatt, float _ath_maxatt, float[] _tone_masteratt, float _tone_centerboost, float _tone_decay, float _tone_abs_limit, float[] _toneatt,
			int _noisemaskp, float _noisemaxsupp, float _noisewindowlo, float _noisewindowhi, int _noisewindowlomin, int _noisewindowhimin, int _noisewindowfixed, float[][] _noiseoff, float[] _noisecompand,
			float _max_curve_dB, int _normal_channel_p, int _normal_point_p, int _normal_start, int _normal_partition, float _normal_thresh ) {

		blockflag = _blockflag;

		ath_adjatt = _ath_adjatt;
		ath_maxatt = _ath_maxatt;

		tone_masteratt = new float[ P_NOISECURVES ];
		System.arraycopy( _tone_masteratt, 0, tone_masteratt, 0, _tone_masteratt.length );

		tone_centerboost = _tone_centerboost;
		tone_decay = _tone_decay;
		tone_abs_limit = _tone_abs_limit;

		toneatt = new float[ P_BANDS ];
		System.arraycopy( _toneatt, 0, toneatt, 0, _toneatt.length );

		noisemaskp = _noisemaskp;
		noisemaxsupp = _noisemaxsupp;
		noisewindowlo = _noisewindowlo;
		noisewindowhi = _noisewindowhi;
		noisewindowlomin = _noisewindowlomin;
		noisewindowhimin = _noisewindowhimin;
		noisewindowfixed = _noisewindowfixed;

		noiseoff = new float[ P_NOISECURVES ][ P_BANDS ];
		for ( int i=0; i < _noiseoff.length; i++ )
			System.arraycopy( _noiseoff[i], 0, noiseoff[i], 0, _noiseoff[i].length );

		noisecompand = new float[ NOISE_COMPAND_LEVELS ];
		System.arraycopy( _noisecompand, 0, noisecompand, 0, _noisecompand.length );

		max_curve_dB = _max_curve_dB;

		normal_channel_p = _normal_channel_p;
		normal_point_p = _normal_point_p;
		normal_start = _normal_start;
		normal_partition = _normal_partition;
		normal_thresh = _normal_thresh;
	}

	public vorbis_info_psy( vorbis_info_psy src ) {

		this( src.blockflag, src.ath_adjatt, src.ath_maxatt, src.tone_masteratt, src.tone_centerboost, src.tone_decay, src.tone_abs_limit, src.toneatt,
			src.noisemaskp, src.noisemaxsupp, src.noisewindowlo, src.noisewindowhi, src.noisewindowlomin, src.noisewindowhimin, src.noisewindowfixed, src.noiseoff, src.noisecompand,
			src.max_curve_dB, src.normal_channel_p, src.normal_point_p, src.normal_start, src.normal_partition, src.normal_thresh );
	}
}