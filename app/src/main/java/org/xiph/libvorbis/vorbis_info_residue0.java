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

public class vorbis_info_residue0 {

	// block-partitioned VQ coded straight residue
	int begin;			// long begin
	int end;			// long end

	// first stage (lossless partitioning)
	int grouping;		// group n vectors per partition
	int partitions;		// possible codebooks for a partition
	int groupbook;		// huffbook for partitioning
	int[] secondstages;	// expanded out to pointers in lookup   secondstages[64]
	int[] booklist;		// list of second stage books  booklist[256]

	float[] classmetric1;	// classmetric1[64]
	float[] classmetric2;	// classmetric2[64]


	public vorbis_info_residue0( int _begin, int _end, int _grouping, int _partitions, int _groupbook, int[] _secondstages, int[] _booklist, float[] _classmetric1, float[] _classmetric2 ) {

		begin = _begin;
		end = _end;

		grouping = _grouping;
		partitions = _partitions;
		groupbook = _groupbook;
		
		secondstages = new int[ 64 ];
		System.arraycopy( _secondstages, 0, secondstages, 0, _secondstages.length );

		booklist = new int[ 256 ];
		System.arraycopy( _booklist, 0, booklist, 0, _booklist.length );

		classmetric1 = new float[ 64 ];
		System.arraycopy( _classmetric1, 0, classmetric1, 0, _classmetric1.length );

		classmetric2 = new float[ 64 ];
		System.arraycopy( _classmetric2, 0, classmetric2, 0, _classmetric2.length );
	}

	public vorbis_info_residue0( vorbis_info_residue0 src ) {

		this( src.begin, src.end, src.grouping, src.partitions, src.groupbook, src.secondstages, src.booklist, src.classmetric1, src.classmetric2 );
	}
}