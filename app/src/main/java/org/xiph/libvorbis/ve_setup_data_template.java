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

public class ve_setup_data_template {

	int mappings;
	float[] rate_mapping;							// double *rate_mapping
	float[] quality_mapping;						// double *quality_mapping
	int coupling_restriction;
	int samplerate_min_restriction;					// long samplerate_min_restriction
	int samplerate_max_restriction;					// long samplerate_max_restriction

	int[] blocksize_short;
	int[] blocksize_long;

	att3[] psy_tone_masteratt;
	int[] psy_tone_0dB;
	int[] psy_tone_dBsuppress;

	vp_adjblock[] psy_tone_adj_impulse;
	vp_adjblock[] psy_tone_adj_long;
	vp_adjblock[] psy_tone_adj_other;

	noiseguard[] psy_noiseguards;
	noise3[] psy_noise_bias_impulse;
	noise3[] psy_noise_bias_padding;
	noise3[] psy_noise_bias_trans;
	noise3[] psy_noise_bias_long;
	int[] psy_noise_dBsuppress;

	compandblock[] psy_noise_compand;
	float[] psy_noise_compand_short_mapping;		// double *psy_noise_compand_short_mapping
	float[] psy_noise_compand_long_mapping;			// double *psy_noise_compand_long_mapping

	int[][] psy_noise_normal_start;
	int[][] psy_noise_normal_partition;
	float[] psy_noise_normal_thresh;				// double *psy_noise_normal_thresh

	int[] psy_ath_float;
	int[] psy_ath_abs;

	float[] psy_lowpass;							// double *psy_lowpass

	vorbis_info_psy_global[] global_params;
	float[] global_mapping;							// double *global_mapping;
	adj_stereo[] stereo_modes;

	static_codebook[][] floor_books;
	vorbis_info_floor1[] floor_params;
	int[] floor_short_mapping;
	int[] floor_long_mapping;

	vorbis_mapping_template[] maps;
	
	
	public ve_setup_data_template() {}

    public ve_setup_data_template(  int _mappings, float[] _rate_mapping, float[] _quality_mapping, int _coupling_restriction, int _samplerate_min_restriction, int _samplerate_max_restriction,
									int[] _blocksize_short, int[] _blocksize_long,
									att3[] _psy_tone_masteratt, int[] _psy_tone_0dB, int[] _psy_tone_dBsuppress,
									vp_adjblock[] _psy_tone_adj_impulse, vp_adjblock[] _psy_tone_adj_long, vp_adjblock[] _psy_tone_adj_other,
									noiseguard[] _psy_noiseguards, noise3[] _psy_noise_bias_impulse, noise3[] _psy_noise_bias_padding, noise3[] _psy_noise_bias_trans, noise3[] _psy_noise_bias_long, int[] _psy_noise_dBsuppress,
									compandblock[] _psy_noise_compand, float[] _psy_noise_compand_short_mapping, float[] _psy_noise_compand_long_mapping,
									int[][] _psy_noise_normal_start, int[][] _psy_noise_normal_partition, float[] _psy_noise_normal_thresh,
									int[] _psy_ath_float, int[] _psy_ath_abs,
									float[] _psy_lowpass,
									vorbis_info_psy_global[] _global_params, float[] _global_mapping, adj_stereo[] _stereo_modes,
									static_codebook[][] _floor_books, vorbis_info_floor1[] _floor_params, int[] _floor_short_mapping, int[] _floor_long_mapping,
									vorbis_mapping_template[] _maps	)
    {
		mappings = _mappings;
		rate_mapping = _rate_mapping;
		quality_mapping = _quality_mapping;
		coupling_restriction = _coupling_restriction;
		samplerate_min_restriction = _samplerate_min_restriction;
		samplerate_max_restriction = _samplerate_max_restriction;
		
		blocksize_short = _blocksize_short;
		blocksize_long = _blocksize_long;
		
		psy_tone_masteratt = _psy_tone_masteratt;
		psy_tone_0dB = _psy_tone_0dB;
		psy_tone_dBsuppress = _psy_tone_dBsuppress;
		
		psy_tone_adj_impulse = _psy_tone_adj_impulse;
		psy_tone_adj_long = _psy_tone_adj_long;
		psy_tone_adj_other = _psy_tone_adj_other;
		
		psy_noiseguards = _psy_noiseguards;
		psy_noise_bias_impulse = _psy_noise_bias_impulse;
		psy_noise_bias_padding = _psy_noise_bias_padding;
		psy_noise_bias_trans = _psy_noise_bias_trans;
		psy_noise_bias_long = _psy_noise_bias_long;
		psy_noise_dBsuppress = _psy_noise_dBsuppress;
		
		psy_noise_compand = _psy_noise_compand;
		psy_noise_compand_short_mapping = _psy_noise_compand_short_mapping;
		psy_noise_compand_long_mapping = _psy_noise_compand_long_mapping;
		
		psy_noise_normal_start = _psy_noise_normal_start;
		psy_noise_normal_partition = _psy_noise_normal_partition;
		psy_noise_normal_thresh = _psy_noise_normal_thresh;
		
		psy_ath_float = _psy_ath_float;
		psy_ath_abs = _psy_ath_abs;
		
		psy_lowpass = _psy_lowpass;
		
		global_params = _global_params;
		global_mapping = _global_mapping;
		stereo_modes = _stereo_modes;
		
		floor_books = _floor_books;
		floor_params = _floor_params;
		floor_short_mapping = _floor_short_mapping;
		floor_long_mapping = _floor_long_mapping;
		
		maps = _maps;	
    }
    
}
			