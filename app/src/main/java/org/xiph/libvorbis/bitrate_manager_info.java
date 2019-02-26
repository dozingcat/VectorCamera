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

// NOTE - rewritten in Vorbislib 1.1.2

class bitrate_manager_info {	// detailed bitrate management setup

	int avg_rate;			// long avg_rate
	int min_rate;			// long min_rate
	int max_rate;			// long max_rate
	int reservoir_bits;		// long reservoir_bits
	float reservoir_bias;	// double reservoir_bias
	
	float slew_damp;		// double slew_damp
	
	/*
	float queue_avg_time;
	float queue_avg_center;
	float queue_minmax_time;
	float queue_hardmin;
	float queue_hardmax;
	float queue_avgmin;
	float queue_avgmax;

	float avgfloat_downslew_max;
	float avgfloat_upslew_max;
	*/

	public bitrate_manager_info() {}
	
	public bitrate_manager_info( int _avg_rate, int _min_rate, int _max_rate, int _reservoir_bits, float _reservoir_bias, float _slew_damp ) {
		
		avg_rate = _avg_rate;
		min_rate = _min_rate;
		max_rate = _max_rate;
		reservoir_bits = _reservoir_bits;
		reservoir_bias = _reservoir_bias;
		
		slew_damp = _slew_damp;
	}
}
