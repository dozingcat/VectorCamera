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

public class vorbis_info_floor1 {

	int partitions;
	int[] partitionclass;	// #define VIF_PARTS 31

	int[] class_dim;		// #define VIF_CLASS 16
	int[] class_subs;
	int[] class_book;
	int[][] class_subbook;	// [VIF_CLASS][subs]

	int mult;
	int[] postlist;		// #define VIF_POSIT 63 + 2

	// encode side analysis parameters
	float maxover;
	float maxunder;
	float maxerr;

	float twofitweight;
	float twofitatten;

	int n;


	public vorbis_info_floor1( int _partitions, int[] _partitionclass, int[] _class_dim, int[] _class_subs, int[] _class_book, int[][] _class_subbook, int _mult, int[] _postlist,
				float _maxover, float _maxunder, float _maxerr, float _twofitweight, float _twofitatten, int _n ) {

		partitions = _partitions;

		partitionclass = new int[ VIF_PARTS ];
		System.arraycopy( _partitionclass, 0, partitionclass, 0, _partitionclass.length );

		class_dim = new int[ VIF_CLASS ];
		System.arraycopy( _class_dim, 0, class_dim, 0, _class_dim.length );

		class_subs = new int[ VIF_CLASS ];
		System.arraycopy( _class_subs, 0, class_subs, 0, _class_subs.length );

		class_book = new int[ VIF_CLASS ];
		System.arraycopy( _class_book, 0, class_book, 0, _class_book.length );

		class_subbook = new int[ VIF_CLASS ][8];
		for ( int i=0; i < _class_subbook.length; i++ )
			System.arraycopy( _class_subbook[i], 0, class_subbook[i], 0, _class_subbook[i].length );

		mult = _mult;

		postlist = new int[ VIF_POSIT+2 ];
		System.arraycopy( _postlist, 0, postlist, 0, _postlist.length );

		maxover = _maxover;
		maxunder = _maxunder;
		maxerr = _maxerr;

		twofitweight = _twofitweight;
		twofitatten = _twofitatten;

		n = _n;
	}

	public vorbis_info_floor1( vorbis_info_floor1 src ) {

		this( src.partitions, src.partitionclass, src.class_dim, src.class_subs, src.class_book, src.class_subbook, src.mult, src.postlist,
				src.maxover, src.maxunder, src.maxerr, src.twofitweight, src.twofitatten, src.n );
	}
}