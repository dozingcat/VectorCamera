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

public class vorbis_info {

	int version;
	public int channels;
	int rate;				// long rate
	int bitrate_upper;		// long bitrate_upper
	int bitrate_nominal;	// long bitrate_nominal
	int bitrate_lower;		// long bitrate_lower
	int bitrate_window;		// long bitrate_window

	codec_setup_info codec_setup;	// void * codec_setup


	public vorbis_info() {

		codec_setup = new codec_setup_info();
	}

	public void vorbis_info_clear() {

		version = 0;
		channels = 0;
		rate = 0;
		bitrate_upper = 0;
		bitrate_nominal = 0;
		bitrate_lower = 0;
		bitrate_window = 0;

		// free codec_setup memory
		codec_setup = null;
	}
}