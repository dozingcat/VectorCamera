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

import static org.xiph.libvorbis.vorbis_constants.integer_constants.*;

class envelope_lookup {

	int ch;
	int winlength;
	int searchstep;
	float minenergy;

	mdct_lookup mdct;
	float[] mdct_win;

	envelope_band[] band;		// VE_BANDS;
	envelope_filter_state[] filter;	// *filter;
	int stretch;

	int[] mark;

	int storage;
	int current;
	int curmark;
	int cursor;


	public envelope_lookup( int _ch, int _winlength, int _searchstep, float _minenergy, mdct_lookup _mdct, float[] _mdct_win,
				envelope_band[] _band, envelope_filter_state[] _filter, int _stretch, int[] _mark, int _storage, int _current, int _curmark, int _cursor ) {

		ch = _ch;
		winlength = _winlength;
		searchstep = _searchstep;
		minenergy = _minenergy;

		mdct = _mdct;

		mdct_win = new float[ _mdct_win.length ];
		System.arraycopy( _mdct_win, 0, mdct_win, 0, _mdct_win.length );

		band = new envelope_band[ VE_BANDS ];
		System.arraycopy( _band, 0, band, 0, _band.length );

		filter = _filter;
		stretch = _stretch;

		mark = new int[ _mark.length ];
		System.arraycopy( _mark, 0, mark, 0, _mark.length );

		storage = _storage;
		current = _current;
		curmark = _curmark;
		cursor = _cursor;
	}

	public envelope_lookup( envelope_lookup src ) {

		this( src.ch, src.winlength, src.searchstep, src.minenergy, src.mdct, src.mdct_win, src.band, src.filter, src.stretch, src.mark, src.storage, src.current, src.curmark, src.cursor );
	}

	public envelope_lookup( vorbis_info _vi ) {

		ch = _vi.channels;
		winlength = 128;
		searchstep = 64; // not random
		minenergy = _vi.codec_setup.psy_g_param.preecho_minenergy;

		int n = winlength;

		storage = 128;
		cursor = _vi.codec_setup.blocksizes[1]/2;

		// mdct_win = _ogg_calloc(n,sizeof(*mdct_win));
		mdct_win = new float[ n ];
		mdct = new mdct_lookup();
		mdct.mdct_init( n );

		for( int i=0; i<n; i++ ) {
			mdct_win[i] = new Double( Math.sin( i/(n-1.)*M_PI ) ).floatValue();
			mdct_win[i] *= mdct_win[i];
		}

		band = new envelope_band[ VE_BANDS ];

		for (int i=0; i<VE_BANDS; i++)
			band[i] = new envelope_band();

		band[0].begin=2;  band[0].end=4;
		band[1].begin=4;  band[1].end=5;
		band[2].begin=6;  band[2].end=6;
		band[3].begin=9;  band[3].end=8;
		band[4].begin=13;  band[4].end=8;
		band[5].begin=17;  band[5].end=8;
		band[6].begin=22;  band[6].end=8;

		for( int j=0; j<VE_BANDS; j++) {

			n = band[j].end;

			// band[j].window = _ogg_malloc(n*sizeof(*band[0].window));
			band[j].window = new float[ n ];

			for ( int i=0; i<n; i++ ) {
				band[j].window[i] = new Double( Math.sin( (i+.5)/n*M_PI ) ).floatValue();
				band[j].total += band[j].window[i];
			}
			band[j].total = 1.0f / band[j].total;
		}
  
		// filter=_ogg_calloc(VE_BANDS*ch,sizeof(*filter));
		// mark=_ogg_calloc(storage,sizeof(*mark));

		filter = new envelope_filter_state[ VE_BANDS*ch ];
		for ( int i=0; i < VE_BANDS*ch; i++ )
			filter[i] = new envelope_filter_state();
		
		mark = new int[ storage ];
	}
	
	public int _ve_amp( vorbis_info_psy_global gi, float[] data, int offset1, envelope_band[] bands, envelope_filter_state[] filters, int offset2, int pos ) {
		
		int n = winlength;
		int ret=0;
		int i,j;
		float decay;
		
		/* we want to have a 'minimum bar' for energy, else we're just
		 * basing blocks on quantization noise that outweighs the signal
		 * itself (for low power signals) */
		
		float minV = minenergy;
		// float *vec=alloca(n*sizeof(*vec));
		float[] vec = new float[ n ];
		
		/* stretch is used to gradually lengthen the number of windows
		 * considered prevoius-to-potential-trigger */
// local OOP variable rename stretch
		int stretch_local = Math.max( VE_MINSTRETCH, stretch/2 );
		float penalty = gi.stretch_penalty-(stretch/2-VE_MINSTRETCH);
		if ( penalty < 0.f )
			penalty = 0.f;
		if (penalty > gi.stretch_penalty )
			penalty = gi.stretch_penalty;
		
		/*_analysis_output_always("lpcm",seq2,data,n,0,0,totalshift+pos*ve->searchstep);*/
		
		// window and transform
		for ( i=0; i < n; i++ )
			vec[i] = data[offset1+i]*mdct_win[i];
		
		mdct.mdct_forward( vec, vec );
//		mdct.out( "", null );
		
		/*_analysis_output_always("mdct",seq2,vec,n/2,0,1,0); */
		
		/* near-DC spreading function; this has nothing to do with
		 * psychoacoustics, just sidelobe leakage and window size */
		float temp = vec[0]*vec[0]+.7f*vec[1]*vec[1]+.2f*vec[2]*vec[2];
		int ptr = filters[offset2].nearptr;

	    // the accumulation is regularly refreshed from scratch to avoid floating point creep
		if ( ptr == 0 ) {
			decay = filters[offset2].nearDC_acc = filters[offset2].nearDC_partialacc+temp;
			filters[offset2].nearDC_partialacc = temp;
		} else {
			decay = filters[offset2].nearDC_acc += temp;
			filters[offset2].nearDC_partialacc += temp;
		}
		filters[offset2].nearDC_acc -= filters[offset2].nearDC[ptr];
		filters[offset2].nearDC[ptr] = temp;
		
		decay *= (1./(VE_NEARDC+1));
		filters[offset2].nearptr++;
		if ( filters[offset2].nearptr >= VE_NEARDC )
			filters[offset2].nearptr = 0;
		decay = todB(decay)*.5f-15.f;
		
		/* perform spreading and limiting, also smooth the spectrum.  yes, the MDCT results
		 * in all real coefficients, but it still *behaves* like real/imaginary pairs */
		for ( i=0; i < n/2; i+=2 ) {
			float val = vec[i]*vec[i]+vec[i+1]*vec[i+1];
			val = todB(val)*.5f;
			if ( val < decay )
				val=decay;
			if (val < minV)
				val=minV;
			vec[i>>1] = val;
			decay-=8.;
		}
		
		/*_analysis_output_always("spread",seq2++,vec,n/4,0,0,0);*/
		
		// perform preecho/postecho triggering by band
		for ( j=0; j < VE_BANDS; j++ ) {
			
			float acc = 0.f;
			float valmax;
			float valmin;
			
			// accumulate amplitude
			for ( i=0; i < bands[j].end; i++ )
				acc += vec[i+bands[j].begin]*bands[j].window[i];
			
			acc *= bands[j].total;
			
			// convert amplitude to delta

// local OOP variable rename
// protected variable name this
			int p,_this = filters[offset2+j].ampptr;
			float postmax,postmin,premax = -99999.f, premin = 99999.f;
			
			p=_this;
			p--;
			if ( p < 0 )
				p += VE_AMP;
			postmax = Math.max( acc, filters[offset2+j].ampbuf[p] );
			postmin = Math.min( acc, filters[offset2+j].ampbuf[p] );
			
			for ( i=0; i < stretch_local; i++ ) {
				
				p--;
				if ( p < 0)
					p += VE_AMP;
				premax = Math.max( premax, filters[offset2+j].ampbuf[p] );
				premin = Math.min( premin, filters[offset2+j].ampbuf[p] );
			}
			
			valmin = postmin-premin;
			valmax = postmax-premax;
			
			/*filters[j].markers[pos]=valmax;*/
			filters[offset2+j].ampbuf[_this] = acc;
			filters[offset2+j].ampptr++;
			if (filters[offset2+j].ampptr >= VE_AMP )
				filters[offset2+j].ampptr = 0;
			
			// look at min/max, decide trigger
			if ( valmax > gi.preecho_thresh[j]+penalty ) {
				ret|=1;
				ret|=4;
			}
			if ( valmin < gi.postecho_thresh[j]-penalty )
				ret|=2;
		}
		
		return ret;
	}
	
	public void _ve_envelope_shift( int shift ) {
		
		int smallsize = current/searchstep+VE_POST; // adjust for placing marks ahead of ve->current
		int smallshift = shift/searchstep;
		
		// memmove(e->mark,e->mark+smallshift,(smallsize-smallshift)*sizeof(*e->mark));
		System.arraycopy( mark, smallshift, mark, 0, smallsize - smallshift );
		
		current -= shift;
		if ( curmark >= 0 )
			curmark -= shift;
		cursor -= shift;
	}
}