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

import org.xiph.libvorbis.modes.*;
import static org.xiph.libvorbis.vorbis_constants.integer_constants.*;


/*********************************************************************
   Encoding using a VBR quality mode.  The usable range is -.1
   (lowest quality, smallest file) to 1. (highest quality, largest file).
   Example quality mode .4: 44kHz stereo coupled, roughly 128kbps VBR 
  
   ret = vorbis_encode_init_vbr(&vi,2,44100,.4);

   ---------------------------------------------------------------------

   Encoding using an average bitrate mode (ABR).
   example: 44kHz stereo coupled, average 128kbps VBR 
  
   ret = vorbis_encode_init(&vi,2,44100,-1,128000,-1);

   ---------------------------------------------------------------------

   Encode using a quality mode, but select that quality mode by asking for
   an approximate bitrate.  This is not ABR, it is true VBR, but selected
   using the bitrate interface, and then turning bitrate management off:

   ret = ( vorbis_encode_setup_managed(&vi,2,44100,-1,128000,-1) ||
           vorbis_encode_ctl(&vi,OV_ECTL_RATEMANAGE_AVG,NULL) ||
           vorbis_encode_setup_init(&vi));

   *********************************************************************/


public class vorbisenc {

	private vorbis_info vi;
	
	private ve_setup_data_template[] setup_list;


	public vorbisenc() {
		
		setup_44 ve_setup_44_stereo = new setup_44();
		setup_list = new ve_setup_data_template[] { ve_setup_44_stereo.data };
	}
	
	public boolean vorbis_encode_setup_vbr( int channels, int rate, float quality ) {
		
		quality += .0000001;
		if ( quality >= 1. )
			quality = .9999f;

		if ( !get_setup_template( channels, rate, quality, 0 )) {
			System.out.println( "Unable to find setup template - " + channels + " channels, " + rate + " rate, " + quality + " quality" );
			return false;
		}

		return vorbis_encode_setup_setting( channels, rate );
	}

	public boolean vorbis_encode_init_vbr( vorbis_info _vi, int channels, int rate, float base_quality ) {

		if ( _vi == null ) {
			System.out.println( "vorbis_info doesnt exist" );
			return false;
		}
		
		vi = _vi;
		
		if ( !vorbis_encode_setup_vbr( channels, rate, base_quality) )
			return false;

		return vorbis_encode_setup_init();
	}

	public boolean vorbis_encode_setup_managed( vorbis_info _vi, int channels, int rate, int max_bitrate, int nominal_bitrate, int min_bitrate ) {

		vi = _vi;

		float tnominal = new Integer( nominal_bitrate ).floatValue();

		if ( nominal_bitrate <= 0. ) {
			if ( max_bitrate > 0. ) {
				if ( min_bitrate > 0. )
					nominal_bitrate = new Double( (max_bitrate+min_bitrate)*.5).intValue();
				else
					nominal_bitrate = new Double( max_bitrate * .875 ).intValue();
			} else {
				if ( min_bitrate > 0. ) 
					nominal_bitrate = min_bitrate;
				else
					return false;
			}
		}

		if ( !get_setup_template( channels, rate, nominal_bitrate, 1 )) {
			System.out.println( "Unable to find setup template - " + channels + " channels, " + rate + " rate, " + nominal_bitrate + " nominal_bitrate" );
			return false;
		}

		vorbis_encode_setup_setting( channels, rate );

		highlevel_encode_setup hi = vi.codec_setup.hi;

		// initialize management with sane defaults
		hi.managed = 1;
		hi.bitrate_min = min_bitrate;
		hi.bitrate_max = max_bitrate;
		hi.bitrate_av = new Float( tnominal ).intValue();
		hi.bitrate_av_damp = 1.5f;
		hi.bitrate_reservoir = nominal_bitrate*2;
		hi.bitrate_reservoir_bias = .1f;	// bias toward hoarding bits

		return true;
	}

	public boolean vorbis_encode_init( vorbis_info _vi, int channels, int rate, int max_bitrate, int nominal_bitrate, int min_bitrate ) {
		
		if ( _vi == null )
			return false;
		
		if ( !vorbis_encode_setup_managed( _vi, channels, rate, max_bitrate, nominal_bitrate, min_bitrate ) )
			return false;

		return vorbis_encode_setup_init();
	}
	
	public Object vorbis_encode_ctl( vorbis_info _vi, int number, Object arg ) {
		
		if ( _vi == null )
			return false;
		
		codec_setup_info ci = vi.codec_setup;
		highlevel_encode_setup hi = ci.hi;
		int setp = (number&0xf); // a read request has a low nibble of 0
		
		if ( ( setp > 0 ) && ( hi.set_in_stone > 0 ) )
			return false;
		
		switch (number) {
		
			// now deprecated *****************
			/*
			case OV_ECTL_RATEMANAGE_GET: {
				
				ovectl_ratemanage_arg ai= ( ovectl_ratemanage_arg )arg;
				
				ai.management_active=hi.managed;
				ai.bitrate_hard_window=ai.bitrate_av_window=(double)hi.bitrate_reservoir/vi.rate;
				ai.bitrate_av_window_center=1.;
				ai.bitrate_hard_min=hi.bitrate_min;
				ai.bitrate_hard_max=hi.bitrate_max;
				ai.bitrate_av_lo=hi.bitrate_av;
				ai.bitrate_av_hi=hi.bitrate_av;
			}
			return true;
			
			// now deprecated *****************
			case OV_ECTL_RATEMANAGE_SET: {
				
				ovectl_ratemanage_arg ai= ( ovectl_ratemanage_arg )arg;
				
				if ( ai == null ) {
					hi.managed=0;	
				} else {
					hi.managed=ai.management_active;
					vorbis_encode_ctl( vi, OV_ECTL_RATEMANAGE_AVG, arg );
					vorbis_encode_ctl( vi, OV_ECTL_RATEMANAGE_HARD, arg );
				}
			}
			return true;
			
			// now deprecated *****************
			case OV_ECTL_RATEMANAGE_AVG: {
				
				ovectl_ratemanage_arg ai= ( ovectl_ratemanage_arg )arg;
				
				if (ai == null) {
					hi.bitrate_av=0;
				} else {
					hi.bitrate_av=(ai.bitrate_av_lo+ai.bitrate_av_hi)*.5;
				}
			}
			return true;
			
			// now deprecated *****************
			case OV_ECTL_RATEMANAGE_HARD: {
				
				ovectl_ratemanage_arg ai= ( ovectl_ratemanage_arg )arg;
				
				if (ai == null ) {
					hi.bitrate_min=0;
					hi.bitrate_max=0;	
				} else {
					hi.bitrate_min=ai.bitrate_hard_min;
					hi.bitrate_max=ai.bitrate_hard_max;
					hi.bitrate_reservoir=ai.bitrate_hard_window*(hi.bitrate_max+hi.bitrate_min)*.5;	
				}
				if ( hi.bitrate_reservoir < 128.f )
					hi.bitrate_reservoir=128;
			}
			return true;
			*/
		
			// replacement ratemanage interface
			case OV_ECTL_RATEMANAGE2_GET: {
				
				ovectl_ratemanage2_arg ai = ( ovectl_ratemanage2_arg )arg;
				
				if ( ai == null )
					return false;
				
				ai.management_active=hi.managed;
				ai.bitrate_limit_min_kbps=hi.bitrate_min/1000;
				ai.bitrate_limit_max_kbps=hi.bitrate_max/1000;
				ai.bitrate_average_kbps=hi.bitrate_av/1000;
				ai.bitrate_average_damping=hi.bitrate_av_damp;
				ai.bitrate_limit_reservoir_bits=hi.bitrate_reservoir;
				ai.bitrate_limit_reservoir_bias=hi.bitrate_reservoir_bias;
				
				return ai;
			}
			
			case OV_ECTL_RATEMANAGE2_SET: {
				
				ovectl_ratemanage2_arg ai = (ovectl_ratemanage2_arg )arg;
				
				if ( ai == null ) {
					hi.managed=0;
				} else {
					// sanity check; only catch invariant violations
					if ( ai.bitrate_limit_min_kbps>0 && ai.bitrate_average_kbps>0 && ai.bitrate_limit_min_kbps>ai.bitrate_average_kbps )
						return false;
					
					if ( ai.bitrate_limit_max_kbps>0 && ai.bitrate_average_kbps>0 && ai.bitrate_limit_max_kbps<ai.bitrate_average_kbps )
						return false;
					
					if ( ai.bitrate_limit_min_kbps>0 && ai.bitrate_limit_max_kbps>0 && ai.bitrate_limit_min_kbps>ai.bitrate_limit_max_kbps)
						return false;
					
					if ( ai.bitrate_average_damping <= 0. )
						return false;
					
					if ( ai.bitrate_limit_reservoir_bits < 0 )
						return false;
					
					if ( ai.bitrate_limit_reservoir_bias < 0. )
						return false;
					
					if ( ai.bitrate_limit_reservoir_bias > 1. )
						return false;
					
					hi.managed=ai.management_active;
					hi.bitrate_min=ai.bitrate_limit_min_kbps * 1000;
					hi.bitrate_max=ai.bitrate_limit_max_kbps * 1000;
					hi.bitrate_av=ai.bitrate_average_kbps * 1000;
					hi.bitrate_av_damp=ai.bitrate_average_damping;
					hi.bitrate_reservoir=ai.bitrate_limit_reservoir_bits;
					hi.bitrate_reservoir_bias=ai.bitrate_limit_reservoir_bias;
				}
			}
			return true;
			
			case OV_ECTL_LOWPASS_GET: {
				
				return hi.lowpass_kHz;	
			}
			
			case OV_ECTL_LOWPASS_SET: {
				
				hi.lowpass_kHz = (Float)arg;
				
				if ( hi.lowpass_kHz < 2.f )
					hi.lowpass_kHz = 2.f;
				if ( hi.lowpass_kHz > 99.f )
					hi.lowpass_kHz = 99.f;
				
			}
			return true;
			
			case OV_ECTL_IBLOCK_GET: {
				
				return hi.impulse_noisetune;
			}

			case OV_ECTL_IBLOCK_SET: {
				
				hi.impulse_noisetune = (Float)arg;
				
				if ( hi.impulse_noisetune > 0.f )
					hi.impulse_noisetune = 0.f;
				if ( hi.impulse_noisetune < -15.f )
					hi.impulse_noisetune = -15.f;
				
			}
			return true;
		}
		return true;
	}
	
	private boolean get_setup_template( int ch, int srate, float req, int q_or_bitrate ) {

		int i=0, j;
		highlevel_encode_setup hi = vi.codec_setup.hi;
		
		if ( q_or_bitrate > 0 )
			req /= ch;
		
		while ( setup_list[i] != null ) {
			
			if ( setup_list[i].coupling_restriction == -1 || setup_list[i].coupling_restriction == ch ) {
				
				if ( srate >= setup_list[i].samplerate_min_restriction && srate <= setup_list[i].samplerate_max_restriction ) {
					
					int mappings = setup_list[i].mappings;
					float[] map;
					if ( q_or_bitrate > 0 )
						map = setup_list[i].rate_mapping;
					else
						map = setup_list[i].quality_mapping;
					
					// the template matches.  Does the requested quality mode fall within this templates modes?
					if ( req < map[0] ) {
						++i;
						continue;
					}
					
					if ( req > map[setup_list[i].mappings]) {
						++i;
						continue;
					}
					
					for ( j=0; j < mappings; j++)
						if ( req >= map[j] && req < map[j+1] )
							break;
					
					// an all points match
					hi.setup = setup_list[i];
					
					if ( j == mappings )
						hi.base_setting = j - .001f;
					else {
						float low = map[j];
						float high = map[j+1];
						float del = (req-low) / (high-low);
						hi.base_setting = j + del;
					}
					
					return true;
				}
			}
			i++;
		}
		hi.setup = null;
		return false;
	}

	private boolean vorbis_encode_setup_setting( int channels, int rate ) {

		int i, is;

		codec_setup_info ci = vi.codec_setup;
		highlevel_encode_setup hi = ci.hi;
		ve_setup_data_template setup = hi.setup;
		float ds;	// double

		// vorbis_encode_toplevel_setup(vorbis_info *vi,int ch,long rate)
		vi.version = 0;
		vi.channels = channels;
		vi.rate = rate;

		is = new Float(hi.base_setting).intValue();
		ds = hi.base_setting - is;

		hi.short_setting = hi.base_setting;
		hi.long_setting = hi.base_setting;

		hi.managed = 0;

		hi.impulse_block_p = 1;
		hi.noise_normalize_p = 1;

		hi.stereo_point_setting = hi.base_setting;
		hi.lowpass_kHz = setup.psy_lowpass[is]*(1.0f-ds)+setup.psy_lowpass[is+1]*ds;  
  
		hi.ath_floating_dB = setup.psy_ath_float[is]*(1.0f-ds)+setup.psy_ath_float[is+1]*ds;
		hi.ath_absolute_dB = setup.psy_ath_abs[is]*(1.0f-ds)+setup.psy_ath_abs[is+1]*ds;

		hi.amplitude_track_dBpersec = -6.0f;
		hi.trigger_setting = hi.base_setting;

		for ( i=0; i < 4; i++ )
			hi.block[i] = new highlevel_byblocktype( hi.base_setting, hi.base_setting, hi.base_setting, hi.base_setting );
		
		return true;
	}

	public boolean vorbis_encode_setup_init() {		// the final setup call

		int i0=0;
		int singleblock=0;

		codec_setup_info ci = vi.codec_setup;
		ve_setup_data_template setup = null;
		highlevel_encode_setup hi = ci.hi;

		if ( ci == null ) {
			System.out.println( "vorbis_info.codec_setup_info doesnt exist");
			return false;
		}
			
		if ( hi.impulse_block_p == 0 )
			i0 = 1;

		// too low/high an ATH floater is nonsensical, but doesn't break anything
		if ( hi.ath_floating_dB > -80 )
			hi.ath_floating_dB = -80;
		if ( hi.ath_floating_dB < -200 )
			hi.ath_floating_dB = -200;

  		// again, bound this to avoid the app shooting itself in the foot
		if ( hi.amplitude_track_dBpersec > 0. )
			hi.amplitude_track_dBpersec = 0.0f;
		if ( hi.amplitude_track_dBpersec < -99999. )
			hi.amplitude_track_dBpersec = -99999.0f;
  
		// get the appropriate setup template; matches the fetch in previous stages
		setup = hi.setup;
		if (setup == null ) {
			System.out.println( "vorbis_info.codec_setup.highlevel_encode_setup.ve_setup_data_template doesnt exist");
			return false;
		}

		hi.set_in_stone=1;

		// choose block sizes from configured sizes as well as paying attention 
		// to long_block_p and short_block_p.  If the configured short and long 
		// blocks are the same length, we set long_block_p and unset short_block_p

		vorbis_encode_blocksize_setup( hi.base_setting, setup.blocksize_short, setup.blocksize_long );
		if ( ci.blocksizes[0] == ci.blocksizes[1] )
			singleblock = 1;

		// floor setup; choose proper floor params.  Allocated on the floor stack in order; if we alloc only long floor, it's 0
		vorbis_encode_floor_setup( hi.short_setting, 0, setup.floor_books, setup.floor_params, setup.floor_short_mapping );
		if ( singleblock == 0 )
			vorbis_encode_floor_setup( hi.long_setting, 1, setup.floor_books, setup.floor_params, setup.floor_long_mapping );

		// setup of [mostly] short block detection and stereo
		vorbis_encode_global_psych_setup( hi.trigger_setting, setup.global_params, setup.global_mapping );
		vorbis_encode_global_stereo( hi, setup.stereo_modes );

		// basic psych setup and noise normalization
		vorbis_encode_psyset_setup( hi.short_setting, setup.psy_noise_normal_start[0], setup.psy_noise_normal_partition[0], setup.psy_noise_normal_thresh, 0 );
		vorbis_encode_psyset_setup( hi.short_setting, setup.psy_noise_normal_start[0], setup.psy_noise_normal_partition[0], setup.psy_noise_normal_thresh, 1 );
		if ( singleblock == 0 ) {
			vorbis_encode_psyset_setup( hi.long_setting, setup.psy_noise_normal_start[1], setup.psy_noise_normal_partition[1], setup.psy_noise_normal_thresh, 2 );
			vorbis_encode_psyset_setup( hi.long_setting, setup.psy_noise_normal_start[1], setup.psy_noise_normal_partition[1], setup.psy_noise_normal_thresh, 3 );
		}

		// tone masking setup
		vorbis_encode_tonemask_setup( hi.block[i0].tone_mask_setting, 0, setup.psy_tone_masteratt, setup.psy_tone_0dB, setup.psy_tone_adj_impulse) ;
		vorbis_encode_tonemask_setup( hi.block[1].tone_mask_setting, 1, setup.psy_tone_masteratt, setup.psy_tone_0dB, setup.psy_tone_adj_other );
		if ( singleblock == 0 ) {
			vorbis_encode_tonemask_setup( hi.block[2].tone_mask_setting, 2, setup.psy_tone_masteratt, setup.psy_tone_0dB, setup.psy_tone_adj_other );
			vorbis_encode_tonemask_setup( hi.block[3].tone_mask_setting, 3, setup.psy_tone_masteratt, setup.psy_tone_0dB, setup.psy_tone_adj_long );
		}

		// noise companding setup
		vorbis_encode_compand_setup( hi.block[i0].noise_compand_setting, 0, setup.psy_noise_compand, setup.psy_noise_compand_short_mapping );
		vorbis_encode_compand_setup( hi.block[1].noise_compand_setting, 1, setup.psy_noise_compand, setup.psy_noise_compand_short_mapping );
		if ( singleblock == 0 ) {
			vorbis_encode_compand_setup( hi.block[2].noise_compand_setting, 2, setup.psy_noise_compand, setup.psy_noise_compand_long_mapping );
			vorbis_encode_compand_setup( hi.block[3].noise_compand_setting, 3, setup.psy_noise_compand, setup.psy_noise_compand_long_mapping );
		}

		// peak guarding setup
		vorbis_encode_peak_setup( hi.block[i0].tone_peaklimit_setting, 0, setup.psy_tone_dBsuppress );
		vorbis_encode_peak_setup( hi.block[1].tone_peaklimit_setting, 1, setup.psy_tone_dBsuppress );
		if ( singleblock == 0 ) {
			vorbis_encode_peak_setup( hi.block[2].tone_peaklimit_setting, 2, setup.psy_tone_dBsuppress );
			vorbis_encode_peak_setup( hi.block[3].tone_peaklimit_setting, 3, setup.psy_tone_dBsuppress );
		}

		// noise bias setup
		float userbias = 0;
		if ( i0 == 0 )
			userbias = hi.impulse_noisetune;
		vorbis_encode_noisebias_setup( hi.block[i0].noise_bias_setting, 0, setup.psy_noise_dBsuppress, setup.psy_noise_bias_impulse, setup.psy_noiseguards, userbias );
		vorbis_encode_noisebias_setup( hi.block[1].noise_bias_setting, 1, setup.psy_noise_dBsuppress, setup.psy_noise_bias_padding, setup.psy_noiseguards, 0.0f );
		if ( singleblock == 0 ) {
			vorbis_encode_noisebias_setup( hi.block[2].noise_bias_setting, 2, setup.psy_noise_dBsuppress, setup.psy_noise_bias_trans, setup.psy_noiseguards, 0.0f );
			vorbis_encode_noisebias_setup( hi.block[3].noise_bias_setting, 3, setup.psy_noise_dBsuppress, setup.psy_noise_bias_long, setup.psy_noiseguards, 0.0f );
		}

		vorbis_encode_ath_setup( 0 );
		vorbis_encode_ath_setup( 1 );
		if ( singleblock == 0 ) {
			vorbis_encode_ath_setup( 2 );
			vorbis_encode_ath_setup( 3 );
		}

		vorbis_encode_map_n_res_setup( hi.base_setting, setup.maps );

		// set bitrate readonlies and management
		if ( hi.bitrate_av > 0 )
			vi.bitrate_nominal = hi.bitrate_av;
		else
			vi.bitrate_nominal = new Float( setting_to_approx_bitrate() ).intValue();
		
		vi.bitrate_lower = hi.bitrate_min;
		vi.bitrate_upper = hi.bitrate_max;
		if ( hi.bitrate_av > 0 )
			vi.bitrate_window = hi.bitrate_reservoir / hi.bitrate_av;
		else
			vi.bitrate_window = 0;

		if ( hi.managed > 0 ) {
			
			ci.bi = new bitrate_manager_info(  
					hi.bitrate_av,
					hi.bitrate_min,
					hi.bitrate_max,
					hi.bitrate_reservoir,
					hi.bitrate_reservoir_bias, 
					hi.bitrate_av_damp );
		}
		
		return true;
	}

	private void vorbis_encode_blocksize_setup( float s, int[] shortb, int[] longb ) {

		codec_setup_info ci = vi.codec_setup;
  		int is = new Float( s ).intValue();

		int blockshort = shortb[is];
		int blocklong = longb[is];
		ci.blocksizes[0] = blockshort;
		ci.blocksizes[1] = blocklong;
	}

	private void vorbis_encode_floor_setup( float s, int block, static_codebook[][] books, vorbis_info_floor1[] in, int[] x ) {

		int i, k;
		int is = new Float(s).intValue();

		codec_setup_info ci = vi.codec_setup;

		// _ogg_calloc(1,sizeof(*f))
		// memcpy(f,in+x[is],sizeof(*f));
		vorbis_info_floor1 f = new vorbis_info_floor1( in[ x[is] ] );

		// fill in the lowpass field, even if it's temporary
		f.n = ci.blocksizes[block] >> 1;

		// books
		{
			int partitions = f.partitions;
			int maxclass = -1;
			int maxbook = -1;

			for ( i=0; i < partitions; i++ )
				if ( f.partitionclass[i] > maxclass )
					maxclass = f.partitionclass[i];

			for ( i=0; i <= maxclass; i++ ) {
				if ( f.class_book[i] > maxbook )
					maxbook = f.class_book[i];

				f.class_book[i] += ci.books;

				for ( k=0; k < (1 << f.class_subs[i]); k++ ){
					if ( f.class_subbook[i][k] > maxbook )
						maxbook = f.class_subbook[i][k];
					if ( f.class_subbook[i][k] >= 0 )
						f.class_subbook[i][k] += ci.books;
				}
			}

			for ( i=0; i <= maxbook; i++ ) {
				ci.book_param[ci.books++] = books[x[is]][i];
			}
		}

		// for now, we're only using floor 1
		ci.floor_type[ci.floors] = 1;
		ci.floor_param[ci.floors] = f;
		ci.floors++;
	}

	private void vorbis_encode_global_psych_setup( float s, vorbis_info_psy_global[] in, float[] x ) {

		int i;
		int is = new Float(s).intValue();
		float ds = s-is;
		codec_setup_info ci = vi.codec_setup;

		// memcpy(g,in+(int)x[is],sizeof(*g));
		ci.psy_g_param = new vorbis_info_psy_global( in[ new Float( x[is] ).intValue() ] );
		vorbis_info_psy_global g = ci.psy_g_param;

		ds = x[is]*(1.0f-ds) + x[is+1]*ds;

		is = new Float(ds).intValue();
		ds -= is;
		if ( ds == 0 && is > 0 ) {
			is--;
			ds = 1.0f;
		}

		// interpolate the trigger threshholds
		for ( i = 0; i < 4; i++ ) {
			g.preecho_thresh[i] = new Double( in[is].preecho_thresh[i]*(1.-ds)+in[is+1].preecho_thresh[i]*ds ).floatValue();
			g.postecho_thresh[i] = new Double( in[is].postecho_thresh[i]*(1.-ds)+in[is+1].postecho_thresh[i]*ds ).floatValue();
		}
		g.ampmax_att_per_sec = ci.hi.amplitude_track_dBpersec;
	}

	private void vorbis_encode_global_stereo( highlevel_encode_setup hi, adj_stereo[] p ) {

		float s = hi.stereo_point_setting;
		int i;
		int is = new Float(s).intValue();
		float ds = s-is;
		codec_setup_info ci = vi.codec_setup;
		vorbis_info_psy_global g = ci.psy_g_param;

		if ( p != null ) {

			// memcpy(g.coupling_prepointamp, p[is].pre, sizeof(*p[is].pre)*PACKETBLOBS);
			// memcpy(g.coupling_postpointamp, p[is].post, sizeof(*p[is].post)*PACKETBLOBS);

			g.coupling_prepointamp = (int[])p[is].pre.clone();
			g.coupling_postpointamp = (int[])p[is].post.clone();

			if ( hi.managed != 0 ) {
				// interpolate the kHz threshholds
				for ( i=0; i<PACKETBLOBS; i++ ) {
					float kHz = new Double( p[is].kHz[i]*(1.-ds)+p[is+1].kHz[i]*ds ).floatValue();
					g.coupling_pointlimit[0][i] = new Double( kHz*1000./vi.rate*ci.blocksizes[0] ).intValue();
					g.coupling_pointlimit[1][i] = new Double( kHz*1000./vi.rate*ci.blocksizes[1] ).intValue();
					g.coupling_pkHz[i] = new Float( kHz ).intValue();
	
					kHz = new Double( p[is].lowpasskHz[i]*(1.-ds)+p[is+1].lowpasskHz[i]*ds ).floatValue();
					g.sliding_lowpass[0][i] = new Double( kHz*1000./vi.rate*ci.blocksizes[0] ).intValue();
					g.sliding_lowpass[1][i] = new Double( kHz*1000./vi.rate*ci.blocksizes[1] ).intValue();
 				}
			} else {
				float kHz = new Double( p[is].kHz[PACKETBLOBS/2]*(1.-ds)+p[is+1].kHz[PACKETBLOBS/2]*ds ).floatValue();
				for ( i=0; i<PACKETBLOBS; i++ ) {
					g.coupling_pointlimit[0][i] = new Double( kHz*1000./vi.rate*ci.blocksizes[0] ).intValue();
					g.coupling_pointlimit[1][i] = new Double( kHz*1000./vi.rate*ci.blocksizes[1] ).intValue();
					g.coupling_pkHz[i] = new Float( kHz ).intValue();
				}
      
				kHz = new Double( p[is].lowpasskHz[PACKETBLOBS/2]*(1.-ds)+p[is+1].lowpasskHz[PACKETBLOBS/2]*ds ).floatValue();
				for ( i=0; i<PACKETBLOBS; i++ ) {
					g.sliding_lowpass[0][i] = new Double( kHz*1000./vi.rate*ci.blocksizes[0] ).intValue();
					g.sliding_lowpass[1][i] = new Double( kHz*1000./vi.rate*ci.blocksizes[1] ).intValue();
				}
			}
		} else {
			for ( i=0; i<PACKETBLOBS; i++ ) {
				g.sliding_lowpass[0][i] = ci.blocksizes[0];
				g.sliding_lowpass[1][i] = ci.blocksizes[1];
			}
		}
	}

	private void vorbis_encode_psyset_setup( float s, int[] nn_start, int[] nn_partition, float[] nn_thresh, int block ) {

		codec_setup_info ci = vi.codec_setup;
		highlevel_encode_setup hi = ci.hi;
		int is = new Float(s).intValue();

		if ( block >= ci.psys )
			ci.psys = block+1;

		ci.psy_param[block] = new vorbis_info_psy( 

			// static vorbis_info_psy _psy_info_template = {
			-1,
			-140.f, -140.f,

			// tonemask att boost/decay,suppr,curves
			new float[] {0.f,0.f,0.f},     0.f, 0.f,    -40.f, new float[] { 0.f},

			// noisemaskp,supp, low/high window, low/hi guard, minimum
			1,          -0.f,         .5f, .5f,         0, 0, 0,
			new float[][] {{-1}, {-1}, {-1}}, new float[] {-1}, 105.f,
			0, 0, -1, -1, 0.0f );

		vorbis_info_psy p = ci.psy_param[block];


		p.blockflag = block >> 1;

		if ( hi.noise_normalize_p != 0 ) {
			p.normal_channel_p = 1;
			p.normal_point_p = 1;
			p.normal_start = nn_start[is];
			p.normal_partition = nn_partition[is];
			p.normal_thresh = nn_thresh[is];
		}
	}

	private void vorbis_encode_tonemask_setup( float s, int block, att3[] att, int[] max, vp_adjblock[] in ) {

		int i;
		int is = new Float(s).intValue();
		float ds = s-is;
		codec_setup_info ci = vi.codec_setup;
		vorbis_info_psy p = ci.psy_param[block];

		// 0 and 2 are only used by bitmanagement, but there's no harm to always filling the values in here
		p.tone_masteratt[0] = new Double( att[is].att[0]*(1.-ds)+att[is+1].att[0]*ds ).floatValue();
		p.tone_masteratt[1] = new Double( att[is].att[1]*(1.-ds)+att[is+1].att[1]*ds ).floatValue();
		p.tone_masteratt[2] = new Double( att[is].att[2]*(1.-ds)+att[is+1].att[2]*ds ).floatValue();
		p.tone_centerboost = new Double( att[is].boost*(1.-ds)+att[is+1].boost*ds ).floatValue();
		p.tone_decay = new Double( att[is].decay*(1.-ds)+att[is+1].decay*ds ).floatValue();

		p.max_curve_dB = new Double( max[is]*(1.-ds)+max[is+1]*ds ).floatValue();

		for ( i=0; i < P_BANDS; i++ )
			p.toneatt[i] = new Double( in[is].block[i]*(1.-ds)+in[is+1].block[i]*ds ).floatValue();
	}

	private void vorbis_encode_compand_setup( float s, int block, compandblock[] in, float[] x ) {

		int i;
		int is = new Float(s).intValue();
		float ds = s-is;
		codec_setup_info ci = vi.codec_setup;
		vorbis_info_psy p = ci.psy_param[block];

		ds = x[is]*(1.0f-ds)+x[is+1]*ds;
		is = new Float( ds ).intValue();
		ds -= is;
		if ( ds==0 && is>0 ) {
			is--;
			ds=1.0f;
		}

		// interpolate the compander settings
		for ( i=0; i<NOISE_COMPAND_LEVELS; i++ )
			p.noisecompand[i] = new Double( in[is].data[i]*(1.-ds)+in[is+1].data[i]*ds ).floatValue();
	}

	private void vorbis_encode_peak_setup( float s, int block, int[] suppress ) {

		int is = new Float(s).intValue();
		float ds = s-is;
		codec_setup_info ci = vi.codec_setup;
		vorbis_info_psy p = ci.psy_param[block];

		p.tone_abs_limit = new Double( suppress[is]*(1.-ds)+suppress[is+1]*ds ).floatValue();
	}

	private void vorbis_encode_noisebias_setup( float s, int block, int[] suppress, noise3[] in, noiseguard[] guard, float userbias ) {

		int i, j;
		int is = new Float(s).intValue();
		float ds = s- is;
		codec_setup_info ci = vi.codec_setup;
		vorbis_info_psy p = ci.psy_param[block];

		p.noisemaxsupp = new Double( suppress[is]*(1.-ds)+suppress[is+1]*ds ).floatValue();
		p.noisewindowlomin = guard[block].lo;
		p.noisewindowhimin = guard[block].hi;
		p.noisewindowfixed = guard[block].fixed;

		for ( j=0; j<P_NOISECURVES; j++ )
			for ( i=0; i<P_BANDS; i++ )
				p.noiseoff[j][i] = new Double( in[is].data[j][i]*(1.-ds)+in[is+1].data[j][i]*ds ).floatValue();

  		// impulse blocks may take a user specified bias to boost the nominal/high noise encoding depth
		for ( j = 0; j < P_NOISECURVES; j++ ) {
			float min = p.noiseoff[j][0]+6; // the lowest it can go
			for ( i = 0; i < P_BANDS; i++ ) {
				p.noiseoff[j][i] += userbias;
				if ( p.noiseoff[j][i] < min )
					p.noiseoff[j][i] = min;
			}
		}
	}

	private void vorbis_encode_ath_setup( int block ) {

		codec_setup_info ci = vi.codec_setup;
		vorbis_info_psy p = ci.psy_param[block];

		p.ath_adjatt = ci.hi.ath_floating_dB;
		p.ath_maxatt = ci.hi.ath_absolute_dB;
	}

	private int book_dup_or_new( codec_setup_info ci, static_codebook book ) {

		int i;

		for ( i=0; i<ci.books; i++ )
			if ( ci.book_param[i] == book )
				return(i);

		return(ci.books++);
	}

	private void vorbis_encode_residue_setup( int number, int block, vorbis_residue_template res ) {

		codec_setup_info ci = vi.codec_setup;
		int i;

		// vorbis_info_residue0 *r = ci.residue_param[number] = _ogg_malloc(sizeof(*r));
		// memcpy(r,res.res,sizeof(*r));

		ci.residue_param[number] = new vorbis_info_residue0( res.res );
		vorbis_info_residue0 r = ci.residue_param[number];

		if (ci.residues <= number)
			ci.residues = number+1;

		switch ( ci.blocksizes[block] ) {

			case 64:case 128:case 256:
				r.grouping=16;
				break;

			default:
				r.grouping=32;
				break;
		}

		ci.residue_type[number] = res.res_type;

		// to be adjusted by lowpass/pointlimit later
		r.end = ci.blocksizes[block]>>1;
		if ( res.res_type == 2 ) {
			r.end *= vi.channels;
		}
  
		// fill in all the books
		{
			int booklist=0,k;
    
			if ( ci.hi.managed != 0 ) {
				for ( i=0; i<r.partitions; i++ )
					for ( k=0; k<3; k++ )
						if ( res.books_base_managed.books[i][k] != null )
							r.secondstages[i] |= (1<<k);

				r.groupbook = book_dup_or_new(ci,res.book_aux_managed);
				ci.book_param[r.groupbook] = res.book_aux_managed;      
    
				for ( i=0; i<r.partitions; i++ ){
					for ( k=0; k<3; k++ ){
						if ( res.books_base_managed.books[i][k] != null ) {
							int bookid = book_dup_or_new( ci, res.books_base_managed.books[i][k] );
							r.booklist[booklist++] = bookid;
							ci.book_param[bookid] = res.books_base_managed.books[i][k];
						}
					}
				}

			} else {

				for ( i=0; i<r.partitions; i++ )
					for ( k=0; k<3; k++ )
						if ( res.books_base.books[i][k] != null )
							r.secondstages[i] |= (1<<k);
  
				r.groupbook = book_dup_or_new(ci,res.book_aux);
				ci.book_param[r.groupbook] = res.book_aux;
      
				for ( i=0;i<r.partitions;i++ ) {
					for ( k=0; k<3; k++ ) {
						if(res.books_base.books[i][k] != null ) {
							int bookid = book_dup_or_new( ci, res.books_base.books[i][k] );
							r.booklist[booklist++] = bookid;
							ci.book_param[bookid] = res.books_base.books[i][k];
						}
					}
				}
			}
		}
  
		// lowpass setup/pointlimit
		{
			float freq = ci.hi.lowpass_kHz*1000.0f;
			vorbis_info_floor1 f = ci.floor_param[block]; // by convention
			float nyq = vi.rate / 2.0f;
			int blocksize = ci.blocksizes[block]>>1;

			// lowpass needs to be set in the floor and the residue.
			if (freq>nyq)
				freq=nyq;

			// in the floor, the granularity can be very fine; it doesn't alter the 
			// encoding structure, only the samples used to fit the floor approximation
			f.n = new Double( freq/nyq*blocksize ).intValue(); 

			// this res may by limited by the maximum pointlimit of the mode,
			// not the lowpass. the floor is always lowpass limited.
			if ( res.limit_type != 0 ) {
				if ( ci.hi.managed != 0 )
					freq=ci.psy_g_param.coupling_pkHz[PACKETBLOBS-1]*1000.0f;
				else
					freq=ci.psy_g_param.coupling_pkHz[PACKETBLOBS/2]*1000.0f;
				if (freq > nyq)
					freq=nyq;
			}
    
			// in the residue, we're constrained, physically, by partition
			// boundaries.  We still lowpass 'wherever', but we have to round up
			// here to next boundary, or the vorbis spec will round it *down* to
			// previous boundary in encode/decode

			if (ci.residue_type[block]==2)
				r.end = new Double((freq/nyq*blocksize*2)/r.grouping+.9).intValue() * r.grouping; // round up only if we're well past
			else
				r.end = new Double((freq/nyq*blocksize)/r.grouping+.9).intValue() * r.grouping; // round up only if we're well past
		}
	}      

	// we assume two maps in this encoder
	private void vorbis_encode_map_n_res_setup( float s, vorbis_mapping_template[] maps ) {

		codec_setup_info ci = vi.codec_setup;
		int i, j;
		int is = new Float(s).intValue();
		int modes = 2;
		vorbis_info_mapping0[] map = maps[is].map;
		vorbis_info_mode[] mode = { new vorbis_info_mode( 0, 0, 0, 0), new vorbis_info_mode( 1, 0, 0, 1 ) }; // _mode_template
		vorbis_residue_template[] res = maps[is].res;

		if ( ci.blocksizes[0] == ci.blocksizes[1] )
			modes=1;

		for ( i = 0; i < modes; i++ ) {

			// ci.map_param[i] = _ogg_calloc(1,sizeof(*map));
			// ci.mode_param[i] = _ogg_calloc(1,sizeof(*mode));
			// memcpy(ci.mode_param[i],mode+i,sizeof(*_mode_template));
			// memcpy(ci.map_param[i],map+i,sizeof(*map));

			ci.mode_param[i] = new vorbis_info_mode( mode[i] );
			if ( i >= ci.modes )
				ci.modes = i+1;

			ci.map_type[i] = 0;

			ci.map_param[i] = new vorbis_info_mapping0( map[i] );

			if ( i >= ci.maps )
				ci.maps = i+1;

			for ( j=0; j < map[i].submaps; j++ )
				vorbis_encode_residue_setup( map[i].residuesubmap[j], i, res[ map[i].residuesubmap[j] ] );
		}
	}

	private float setting_to_approx_bitrate() {

		codec_setup_info ci = vi.codec_setup;
		highlevel_encode_setup hi= ci.hi;
		ve_setup_data_template setup = hi.setup;

		int is = new Float( hi.base_setting ).intValue();
		float ds = hi.base_setting - is;
		int ch = vi.channels;
		float[] r = setup.rate_mapping;

		if ( r == null )
			return (-1);
  
		return ( (r[is]*(1.0f-ds)+r[is+1]*ds)*ch );  
	}

}
