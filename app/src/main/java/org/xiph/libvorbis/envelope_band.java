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

class envelope_band {

	int begin;
	int end;
	float[] window;
	float total;


	public envelope_band( int _begin, int _end, float[] _window, float _total ) {

		begin = _begin;
		end = _end;

		window = new float[ _window.length ];
		System.arraycopy( _window, 0, window, 0, _window.length );

		total = _total;
	}

	public envelope_band( envelope_band src ) {

		this( src.begin, src.end, src.window, src.total );
	}

	public envelope_band() {}
}