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

public class ovectl_ratemanage2_arg {
	
	  int    management_active;

	  int   bitrate_limit_min_kbps;		// long
	  int   bitrate_limit_max_kbps;		// long
	  int   bitrate_limit_reservoir_bits;	// long
	  float bitrate_limit_reservoir_bias;	// float

	  int   bitrate_average_kbps;		// long
	  float bitrate_average_damping;	// float
}
