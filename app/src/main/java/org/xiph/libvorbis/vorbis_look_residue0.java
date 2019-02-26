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

class vorbis_look_residue0 {

	vorbis_info_residue0 info;

	int parts;
	int stages;

	codebook[] fullbooks;		// *fullbooks;
	codebook phrasebook;		// *phrasebook;
	codebook[][] partbooks;		// ***partbooks;

	int partvals;
	int[][] decodemap;		// **decodemap;

	int postbits;
	int phrasebits;
	int frames;

	// #ifdef TRAIN_RES
	// int        train_seq;
	// long      *training_data[8][64];
	// float      training_max[8][64];
	// float      training_min[8][64];
	// float     tmin;
	// float     tmax;
	// #endif


	public vorbis_look_residue0() {

	}
}
