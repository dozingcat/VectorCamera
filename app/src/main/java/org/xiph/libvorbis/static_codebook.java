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

public class static_codebook {

	int dim;			// long dim // codebook dimensions (elements per vector)
	int entries;		// long entries // codebook entries
	int[] lengthlist;	// long *lengthlist // codeword lengths in bits

	// mapping **************************************************
	int maptype;		// 0=none
						// 1=implicitly populated values from map column 
						// 2=listed arbitrary values

	// The below does a linear, single monotonic sequence mapping.
	int q_min;			// long q_min // packed 32 bit float; quant value 0 maps to minval
	int q_delta;		// long q_delta // packed 32 bit float; val 1 - val 0 == delta
	int q_quant;		// bits: 0 < quant <= 16
	int q_sequencep;	// bitflag

	int[] quantlist;	// long *quantlist
						// map == 1: (int)(entries^(1/dim)) element column map
			   			// map == 2: list of dim*entries quantized entry vals

	// encode helpers *******************************************
	encode_aux_nearestmatch nearest_tree;	// *nearest_tree
	encode_aux_threshmatch thresh_tree;		// *thresh_tree
	encode_aux_pigeonhole pigeon_tree;		// *pigeon_tree

	int allocedp;


	public static_codebook( int _dim, int _entries, int[] _lengthlist, int _maptype, int _q_min, int _q_delta, int _q_quant, int _q_sequencep, int[] _quantlist, 
				encode_aux_nearestmatch _nearest_tree, encode_aux_threshmatch _thresh_tree, encode_aux_pigeonhole _pigeon_tree, int _allocedp ) {

		dim = _dim;
		entries = _entries;

		if ( _lengthlist == null )
			lengthlist = null;
		else
			lengthlist = (int[])_lengthlist.clone();


		maptype = _maptype;

		q_min = _q_min;
		q_delta = _q_delta;
		q_quant = _q_quant;
		q_sequencep = _q_sequencep;

		if ( _quantlist == null )
			quantlist = null;
		else
			quantlist = (int[])_quantlist.clone();

		nearest_tree = _nearest_tree;
		thresh_tree = _thresh_tree;
		pigeon_tree = _pigeon_tree;

		allocedp = _allocedp;
	}

	public static_codebook( int _dim ) {

		dim = _dim;
		entries = _dim;
		lengthlist = new int[] {};
		maptype = _dim;

		q_min = _dim;
		q_delta = _dim;
		q_quant = _dim;
		q_sequencep = _dim;

		quantlist = new int[] {};

		nearest_tree = null;
		thresh_tree = null;
		pigeon_tree = null;

		allocedp = _dim;
	}

	public static_codebook( static_codebook src ) {

		this( src.dim, src.entries, src.lengthlist, src.maptype, src.q_min, src.q_delta, src.q_quant, src.q_sequencep, src.quantlist, 
				new encode_aux_nearestmatch( src.nearest_tree), new encode_aux_threshmatch( src.thresh_tree ), new encode_aux_pigeonhole( src.pigeon_tree ), src.allocedp );
	}
	
	// public long _book_maptype1_quantvals()
	public int _book_maptype1_quantvals() {

		// long vals
		int vals= new Double( Math.floor( Math.pow( new Integer(entries).floatValue(), 1.0f/dim ) ) ).intValue();

		// the above *should* be reliable, but we'll not assume that FP is
		// ever reliable when bitstream sync is at stake; verify via integer
		// means that vals really is the greatest value of dim for which
		// vals^b->bim <= b->entries

		// treat the above as an initial guess
		while (true) {
			long acc=1;
			long acc1=1;
			int i;
			for ( i=0; i < dim; i++ ) {
				acc*=vals;
				acc1*=vals+1;
			}

			if ( acc <= entries && acc1 > entries ){
				return vals;
			} else{
				if ( acc > entries ) {
					vals--;
				} else {
					vals++;
				}
			}
		}
	}
}