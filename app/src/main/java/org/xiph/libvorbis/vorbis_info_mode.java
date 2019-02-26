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

class vorbis_info_mode {

	int blockflag;
	int windowtype;
	int transformtype;
	int mapping;


	public vorbis_info_mode( int _blockflag, int _windowtype, int _transformtype, int _mapping ) {

		blockflag = _blockflag;
		windowtype = _windowtype;
		transformtype = _transformtype;
		mapping = _mapping;
	}

	public vorbis_info_mode( vorbis_info_mode src ) {

		this( src.blockflag, src.windowtype, src.transformtype, src.mapping );
	}
}
