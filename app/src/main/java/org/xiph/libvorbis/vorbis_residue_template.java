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

public class vorbis_residue_template {

	int res_type;
	int limit_type; // 0 lowpass limited, 1 point stereo limited

	vorbis_info_residue0 res;

	static_codebook book_aux;
	static_codebook book_aux_managed;
	static_bookblock books_base;
	static_bookblock books_base_managed;


	public vorbis_residue_template( int _res_type, int _limit_type, vorbis_info_residue0 _res, 
				static_codebook _book_aux, static_codebook _book_aux_managed, static_bookblock _books_base, static_bookblock _books_base_managed ) {

		res_type = _res_type;
		limit_type = _limit_type;

		res = _res;

		book_aux = _book_aux;
		book_aux_managed = _book_aux_managed;
		books_base = _books_base;
		books_base_managed = _books_base_managed;
	}

	public vorbis_residue_template( vorbis_residue_template src ) {

		this( src.res_type, src.limit_type, new vorbis_info_residue0( src.res ), 
			new static_codebook( src.book_aux ), new static_codebook( src.book_aux_managed ), new static_bookblock( src.books_base ), new static_bookblock( src.books_base_managed ) );
	}
}
