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

public class static_bookblock {

	static_codebook[][] books;


	public static_bookblock( static_codebook[][] _books ) {

		books = new static_codebook[12][3];

		for ( int i=0; i < _books.length; i++ )
			System.arraycopy( _books[i], 0, books[i], 0, _books[i].length );
	}

	public static_bookblock( static_bookblock src ) {
	
		this( src.books );
	}
}