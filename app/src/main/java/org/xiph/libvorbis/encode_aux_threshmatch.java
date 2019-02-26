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

public class encode_aux_threshmatch {

	float[] quantthresh;
	int[] quantmap;			// long *quantmap
	int quantvals;
	int threshvals;


	public encode_aux_threshmatch( float[] _quantthresh, int[] _quantmap, int _quantvals, int _threshvals ) {

		if ( _quantthresh == null )
			quantthresh = null;
		else
			quantthresh = (float[])_quantthresh.clone();

		if ( _quantmap == null )
			quantmap = null;
		else
			quantmap = (int[])_quantmap.clone();

		quantvals = _quantvals;
		threshvals = _threshvals;
	}

	public encode_aux_threshmatch( encode_aux_threshmatch src ) {

		this( src.quantthresh, src.quantmap, src.quantvals, src.threshvals );
	}
}