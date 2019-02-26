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

class highlevel_encode_setup {

	ve_setup_data_template setup;	// void *setup
	int set_in_stone;

	float base_setting;				// double base_setting
	float long_setting;				// double long_setting
	float short_setting;			// double short_setting
	float impulse_noisetune;		// double impulse_noisetune

	int managed;
	int bitrate_min;				// long bitrate_min
	int bitrate_av;					// long bitrate_av
	float bitrate_av_damp;			// double bitrate_av_damp
	int bitrate_max;				// long bitrate_max
	int bitrate_reservoir;			// long bitrate_reservoir
	float bitrate_reservoir_bias;	// double bitrate_reservoir_bias

	int impulse_block_p;
	int noise_normalize_p;

	float stereo_point_setting;		// double stereo_point_setting
	float lowpass_kHz;				// double lowpass_kHz

	float ath_floating_dB;			// double ath_floating_dB
	float ath_absolute_dB;			// double ath_absolute_dB

	float amplitude_track_dBpersec;	// double amplitude_track_dBpersec
	float trigger_setting;			// double trigger_setting

	highlevel_byblocktype[] block;	// highlevel_byblocktype block[4] // padding, impulse, transition, long


	public highlevel_encode_setup() {

		setup = null;

		block = new highlevel_byblocktype[4];
	}
}