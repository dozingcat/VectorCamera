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

import java.util.*;

import org.xiph.libogg.*;
import static org.xiph.libvorbis.vorbis_constants.integer_constants.*;

public class vorbis_dsp_state {

	int analysisp;
	
	public vorbis_info vi;

	float[][] pcm;		// float **pcm // float **pcmret

	int pcm_storage;
	public int pcm_current;
	int pcm_returned;

	int preextrapolate;
	int eofflag;

	int lW;
	int W;
	int nW;
	int centerW;

	int granulepos;
	int sequence;

	int glue_bits;
	int time_bits;
	int floor_bits;
	int res_bits;

	private_state backend_state;


	public vorbis_dsp_state() {}


	private vorbis_look_floor1 floor1_look( vorbis_info_floor1 info ) {

		int sortpointer[] = new int[ VIF_POSIT+2 ];
		vorbis_look_floor1 look = new vorbis_look_floor1();

		int i,j,n=0;

		look.vi = info;
		look.n = info.postlist[1];
 
		// we drop each position value in-between already decoded values,
		// and use linear interpolation to predict each new value past the
		// edges.  The positions are read in the order of the position
		// list... we precompute the bounding positions in the lookup.  Of
		// course, the neighbors can change (if a position is declined), but
		// this is an initial mapping

		for ( i=0; i<info.partitions; i++ ) 
			n += info.class_dim[ info.partitionclass[i] ];
		n+=2;
		look.posts=n;

		// also store a sorted position index
		for ( i=0; i<n; i++ )
			sortpointer[i] = info.postlist[i];
			// sortpointer[i] = info.postlist+i;

		// static int icomp(const void *a,const void *b) { return(**(int **)a-**(int **)b); }
		// qsort( sortpointer, n, sizeof(*sortpointer), icomp );

		Arrays.sort( sortpointer, 0, n );

		// points from sort order back to range number
		for ( i=0; i<n; i++ )
			for ( j=0; j<n; j++)
				if ( sortpointer[i] == info.postlist[j] )
					look.forward_index[i] = j;
		// look.forward_index[i] = sortpointer[i]-info.postlist;

		// points from range order to sorted position
		for ( i=0; i<n; i++ )
			look.reverse_index[ look.forward_index[i] ] = i;

		// we actually need the post values too
		for ( i=0; i<n; i++ )
			look.sorted_index[i] = info.postlist[ look.forward_index[i] ];

		switch ( info.mult ) {	// quantize values to multiplier spec

			case 1:
				look.quant_q=256;
				break;
			case 2:
				look.quant_q=128;
				break;
			case 3:
				look.quant_q=86;
				break;
			case 4:
				look.quant_q=64;
				break;
		}

		// discover our neighbors for decode where we don't use fit flags (that would push the neighbors outward)
		for ( i=0; i<n-2; i++ ) {
			int lo=0;
			int hi=1;
			int lx=0;
			int hx=look.n;
			int currentx=info.postlist[i+2];
			for ( j=0; j<i+2; j++ ) {
				int x=info.postlist[j];
				if (x>lx && x<currentx) {
					lo=j;
					lx=x;
				}
				if (x<hx && x>currentx) {
					hi=j;
					hx=x;
				}
			}
			look.loneighbor[i]=lo;
			look.hineighbor[i]=hi;
		}

		return look;
	}


	private vorbis_look_residue0 res0_look( vorbis_info_residue0 info ) {

		vorbis_look_residue0 look = new vorbis_look_residue0();
		codec_setup_info ci = vi.codec_setup;

		int j,k,acc=0;
		int dim;
		int maxstage = 0;

		look.info = info;

		look.parts = info.partitions;
		look.fullbooks = ci.fullbooks;
// PTR CITY

		// look.phrasebook = ci.fullbooks+info.groupbook;
		look.phrasebook = ci.fullbooks[ info.groupbook ];
		dim = look.phrasebook.dim;

		// look.partbooks = _ogg_calloc(look.parts,sizeof(*look.partbooks));
		look.partbooks = new codebook[ look.parts ][];
		

		for ( j=0; j<look.parts; j++ ) {

			int stages = ilog( info.secondstages[j] );
			if ( stages > 0 ) {
				if ( stages > maxstage )
					maxstage = stages;

				// look.partbooks[j]=_ogg_calloc(stages,sizeof(*look.partbooks[j]));
				look.partbooks[j] = new codebook[ stages ];

				for ( k=0; k<stages; k++)

					if ( (info.secondstages[j] & (1<<k) ) > 0 ) {

						// look.partbooks[j][k] = ci.fullbooks + info.booklist[acc++];

						look.partbooks[j][k] = ci.fullbooks[ info.booklist[acc++] ];

						// #ifdef TRAIN_RES
						// 	look.training_data[k][j]=calloc(look.partbooks[j][k].entries,sizeof(***look.training_data));
						// #endif
					}
			}
		}

		look.partvals = new Double( Math.rint( Math.pow( look.parts, dim) ) ).intValue();
		look.stages = maxstage;

		// look.decodemap=_ogg_malloc(look.partvals*sizeof(*look.decodemap));
		look.decodemap = new int[ look.partvals ][ dim ];

		for ( j=0; j<look.partvals; j++) {
			int val = j;
			int mult = look.partvals / look.parts;

			// look.decodemap[j]=_ogg_malloc(dim*sizeof(*look.decodemap[j]));

			for ( k=0; k<dim; k++ ){
				int deco = val/mult;
				val -= deco*mult;
				mult /= look.parts;
				look.decodemap[j][k] = deco;
			}
		}

		// #ifdef TRAIN_RES
		//	static int train_seq=0;
		//	look.train_seq=train_seq++;
		// #endif

  		return look;
	}


	private boolean _vds_shared_init( boolean encp ) {

		int i;
		codec_setup_info ci = vi.codec_setup;
		int hs;

		if ( ci == null )
			return false;

		hs = ci.halfrate_flag;

		backend_state = new private_state();

		backend_state.modebits = ilog2( ci.modes );

		backend_state.transform[0][0].mdct_init( ci.blocksizes[0]>>>hs );
		backend_state.transform[1][0].mdct_init( ci.blocksizes[1]>>>hs );

		// Vorbis I uses only window type 0
		backend_state.window[0] = ilog2(ci.blocksizes[0])-6;
		backend_state.window[1] = ilog2(ci.blocksizes[1])-6;

		if (encp) { // encode/decode differ here

			// analysis always needs an fft
			backend_state.fft_look[0].drft_init( ci.blocksizes[0] );
			backend_state.fft_look[1].drft_init( ci.blocksizes[1] );

			// finish the codebooks
			if ( ci.fullbooks == null ) {

				// ci.fullbooks=_ogg_calloc(ci.books,sizeof(*ci.fullbooks));
				ci.fullbooks = new codebook[ ci.books ];

				for ( i=0; i<ci.books; i++ ) {
					ci.fullbooks[i] = new codebook();
					ci.fullbooks[i].vorbis_book_init_encode( ci.book_param[i] );
				}
			}

			// backend_state.psy=_ogg_calloc(ci.psys,sizeof(*backend_state.psy));
			backend_state.psy = new vorbis_look_psy[ ci.psys ];

			for ( i=0; i<ci.psys; i++ ) {
				backend_state.psy[i] = new vorbis_look_psy();
				backend_state.psy[i]._vp_psy_init( ci.psy_param[i], ci.psy_g_param, ci.blocksizes[ci.psy_param[i].blockflag]/2, vi.rate );
			 }

			analysisp = 1;
		}
		else {
			// decode the codebooks - not yet implemented
			System.out.println( "_vds_shared_init DECODE not yet implemented");
			return false;
		}

		pcm_storage = ci.blocksizes[1];

		pcm = new float[ vi.channels ][ pcm_storage ];
		// pcmret = new float[ vi.channels ];

		lW = 0; // previous window size
		W = 0;  // current window size

		// all vector indexes
		centerW = ci.blocksizes[1]/2;

		pcm_current = centerW;

// flr - will always be floor1
// residue - look data will always fit in res0

		// initialize all the backend lookups
		// backend_state.flr = _ogg_calloc(ci.floors,sizeof(*backend_state.flr));
		// backend_state.residue = _ogg_calloc(ci.residues,sizeof(*backend_state.residue));

		backend_state.flr = new vorbis_look_floor1[ ci.floors ];
		backend_state.residue = new vorbis_look_residue0[ ci.residues ];

		// for ( i=0; i < ci.floors; i++ )
		//	backend_state.flr[i] = _floor_P[ci.floor_type[i]].look( v, ci.floor_param[i] );

		for ( i=0; i < ci.floors; i++ ) {
			backend_state.flr[i] = floor1_look( ci.floor_param[i] );
		}

		// for ( i=0; i < ci.residues; i++ )
		//	backend_state.residue[i] = _residue_P[ci.residue_type[i]].look( v, ci.residue_param[i] );
		
		for ( i=0; i < ci.residues; i++ ) {
			backend_state.residue[i] = res0_look( ci.residue_param[i] );
		}
		
		return true;
	}

	public boolean vorbis_analysis_init( vorbis_info _vi ) {

		vi = _vi;

  		if ( !_vds_shared_init( true ) )
			return false;

		backend_state.psy_g_look = new vorbis_look_psy_global( vi );

		backend_state.ve = new envelope_lookup( vi );

  		backend_state.bms = new bitrate_manager_state( vi );
  		
  		// compressed audio packets start after the headers with sequence number 3
  		sequence = 3;

  		return true;
	}

	public boolean vorbis_analysis_headerout( vorbis_comment vc, ogg_packet op, ogg_packet op_comm, ogg_packet op_code ) {

		oggpack_buffer opb;

		// first header packet **********************************************
		opb = new oggpack_buffer();
		if ( !opb._vorbis_pack_info( vi ) )
			return false;

		// build the packet
		backend_state.header = new byte[ opb.oggpack_bytes() ];
		// memcpy(b->header,opb.buffer,oggpack_bytes(&opb));
		System.arraycopy( opb.buffer, 0, backend_state.header, 0, opb.oggpack_bytes() );
		op.packet = backend_state.header;
		op.bytes = opb.oggpack_bytes();
		op.b_o_s = 1;
		op.e_o_s = 0;
		op.granulepos = 0;
		op.packetno = 0;

		// second header packet (comments) **********************************
		opb = new oggpack_buffer();
		if ( !opb._vorbis_pack_comment( vc ) )
			return false;
		
		// build the packet
		backend_state.header1 = new byte[ opb.oggpack_bytes() ];
		// memcpy(b->header1,opb.buffer,oggpack_bytes(&opb));
		System.arraycopy( opb.buffer, 0, backend_state.header1, 0, opb.oggpack_bytes() );
		op_comm.packet = backend_state.header1;
		op_comm.bytes = opb.oggpack_bytes();
		op_comm.b_o_s = 0;
		op_comm.e_o_s = 0;
		op_comm.granulepos = 0;
		op_comm.packetno = 1;

		// third header packet (modes/codebooks) ****************************
		opb = new oggpack_buffer();
		if( !opb._vorbis_pack_books( vi ) )
			return false;

		// build the packet
		backend_state.header2 = new byte[ opb.oggpack_bytes() ];
		// memcpy(b->header2,opb.buffer,oggpack_bytes(&opb));
		System.arraycopy( opb.buffer, 0, backend_state.header2, 0, opb.oggpack_bytes() );
		op_code.packet = backend_state.header2;
		op_code.bytes = opb.oggpack_bytes();
		op_code.b_o_s = 0;
		op_code.e_o_s = 0;
		op_code.granulepos = 0;
		op_code.packetno = 2;

		return true;
	}
	
	public float[][] vorbis_analysis_buffer( int vals ) {
		
		int i;
		
		// free header, header1, header2
		backend_state.header = null;
		backend_state.header1 = null;
		backend_state.header2 = null;
		
		// Do we have enough storage space for the requested buffer? If not, expand the PCM (and envelope) storage
		
		if ( pcm_current + vals >= pcm_storage  ) {

			pcm_storage = pcm_current + vals*2;

// TODO - multiple channels
// temp work around for loop - need hash, etc
// for unique array name for array reference
// this is workaround for only 2 channels

			for ( i=0; i < vi.channels; i+=2 ) {
				// pcm[i] = _ogg_realloc( v->pcm[i], v->pcm_storage*sizeof(*v->pcm[i]) );
				float[] temp = new float[ pcm_storage ];
				System.arraycopy( pcm[i], 0, temp, 0, pcm_current );
				pcm[i] = temp;
				
				float[] temp2 = new float[ pcm_storage ];
				System.arraycopy( pcm[i+1], 0, temp2, 0, pcm_current );
				pcm[i+1] = temp2;
			}
		}
		
// for now I just return pcm and offset
// by pcm_current to alter the .wav buffer

		// for ( i=0; i < vi.channels; i++ )
			// v->pcmret[i] = v->pcm[i] + v->pcm_current;

		return pcm;
	}
	
	public boolean vorbis_analysis_wrote( int vals ) {

		codec_setup_info ci = vi.codec_setup;

		if ( vals <= 0 ) {
			
			int order=32;
		    int i;
		    float[] lpc = new float[ order ];
		    
		    // if it wasn't done earlier (very short sample)
		    if( preextrapolate <= 0 )
		    	_preextrapolate_helper();
		    
		    // We're encoding the end of the stream.  Just make sure we have
		    // [at least] a few full blocks of zeroes at the end.
		    
		    // Actually, we don't want zeroes; that could drop a large
		    // amplitude off a cliff, creating spread spectrum noise that will
		    // suck to encode.  Extrapolate for the sake of cleanliness.
		    
		    vorbis_analysis_buffer( ci.blocksizes[1]*3 ); 
		    eofflag = pcm_current;
		    pcm_current += ci.blocksizes[1]*3;
		    
		    for ( i=0; i < vi.channels; i++ ) {
		    	
		    	if ( eofflag > order*2 ) {
		    		// extrapolate with LPC to fill in
		    		int n;
		    		
		    		// make a predictor filter
		    		n = eofflag;
		    		if ( n > ci.blocksizes[1] )
		    			n = ci.blocksizes[1];

		    		// vorbis_lpc_from_data(v->pcm[i]+v->eofflag-n,lpc,n,order);
		    		vorbis_lpc_from_data( pcm[i], eofflag-n, lpc, n, order );

		    		// run the predictor filter
		    		// vorbis_lpc_predict(lpc,v->pcm[i]+v->eofflag-order,order, v->pcm[i]+v->eofflag,v->pcm_current-v->eofflag);
		    		vorbis_lpc_predict( lpc, pcm[i], eofflag-order, order, eofflag, pcm_current - eofflag );
		    		
		    	} else {
		    		
		    		// not enough data to extrapolate (unlikely to happen due to
		    		// guarding the overlap, but bulletproof in case that
		    		// assumtion goes away). zeroes will do.

		    		// memset(v->pcm[i]+v->eofflag,0,(v->pcm_current-v->eofflag)*sizeof(*v->pcm[i]));
		    		
		    		// for ( int j=eofflag; j < pcm_current; j++ ) {
		    		//	pcm[i][j] = 0;
		    		// }
		    		
		    		Arrays.fill( pcm[i], eofflag, pcm_current, 0 );
		    	}
		    }
		} else {
			
			if ( pcm_current+vals > pcm_storage )
				return false;
			
			pcm_current += vals;
			
			// we may want to reverse extrapolate the beginning of a stream
			// too... in case we're beginning on a cliff!
			// clumsy, but simple.  It only runs once, so simple is good.
			
			if ( (preextrapolate <= 0) && pcm_current-centerW > ci.blocksizes[1] )
				_preextrapolate_helper();
		}
		return true;
	}
	
	public boolean vorbis_bitrate_flushpacket( ogg_packet op ) {
		
		private_state b = backend_state;
		bitrate_manager_state bm = b.bms;
		vorbis_block vb = bm.vb;
		int choice = PACKETBLOBS/2;
		
		if ( vb == null )
			return false;
		
		if ( op != null ) {
			
			vorbis_block_internal vbi = vb.internal;
			
			if ( vb.vorbis_bitrate_managed() )
				choice = bm.choice;

		    op.packet = vbi.packetblob[choice].buffer; 	// oggpack_get_buffer();
		    op.bytes = vbi.packetblob[choice].oggpack_bytes();
		    op.b_o_s = 0;
		    op.e_o_s = vb.eofflag;
		    op.granulepos = vb.granulepos;
		    op.packetno = vb.sequence; 	// for sake of completeness    
		}
		
		// bm.vb = 0;
		bm.vb = null;
		return true;	  
	}
	
	public void _preextrapolate_helper() {
		
		int i;
		int order = 32;
		float[] lpc = new float[ order ];
		float[] work = new float[ pcm_current ];
		int j;
		preextrapolate = 1;
		
		if ( pcm_current-centerW > order*2 ) { // safety
			
			for ( i=0; i< vi.channels; i++ ) {
				
				// need to run the extrapolation in reverse!
				
				for (j=0; j < pcm_current; j++)
					work[j] = pcm[i][pcm_current-j-1];
				
				// prime as above
				vorbis_lpc_from_data( work, 0, lpc, pcm_current-centerW, order );
				
				// run the predictor filter
				vorbis_lpc_predict( lpc, work, pcm_current-centerW-order, order, pcm_current-centerW, centerW );
				
				for ( j=0; j < pcm_current; j++ )
					pcm[i][pcm_current-j-1] = work[j];
			}
		}
	}
	
	public float vorbis_lpc_from_data( float[] data, int offset1, float[] lpci, int n, int m ) {
		
		// double *aut=alloca(sizeof(*aut)*(m+1));
		double[] aut = new double[ m+1 ];
		// double *lpc=alloca(sizeof(*lpc)*(m));
		double[] lpc = new double[ m ];
		double error;
		int i,j;
		
		// autocorrelation, p+1 lag coefficients
		j = m+1;
		while ( j-- > 0 ) {
			double d = 0; // double needed for accumulator depth
			for ( i=j; i < n; i++ )
				d += (double)data[offset1+i] * data[offset1+i-j];
			aut[j] = d;
		}
		
		// Generate lpc coefficients from autocorr values
		
		error = aut[0];
		
		for ( i=0; i < m; i++ ) {
			
			double r = -aut[i+1];
			
			if ( error==0 ) {
				lpci = new float[ lpci.length ];
				return 0;
			}
			
			// Sum up this iteration's reflection coefficient; note that in Vorbis
			// we don't save it.  If anyone wants to recycle this code and needs 
			// reflection coefficients, save the results of 'r' from each iteration.
			
			for ( j=0; j < i; j++)
				r -= lpc[j]*aut[i-j];
			r /= error;
			
			// Update LPC coefficients and total error
			
			lpc[i] = r;
			for ( j=0; j < i/2; j++ ) {
				
				double tmp = lpc[j];
				
				lpc[j] += r*lpc[i-1-j];
				lpc[i-1-j] += r*tmp;
			}
			if ( i%2 > 0 )
				lpc[j] += lpc[j]*r;
			
			error *= 1.f-r*r;
		}
		
		for ( j=0; j <m ; j++ )
			lpci[j] = (float)lpc[j];
		
		// we need the error value to know how big an impulse to hit the filter with later
		
		return new Double( error ).floatValue();
	}
	
	public void vorbis_lpc_predict( float[] coeff, float[] prime, int offset1, int m, int offset2, int n ) {
		
		// in: coeff[0...m-1] LPC coefficients 
		// 	   prime[0...m-1] initial values (allocated size of n+m-1)
		// out: data[0...n-1] data samples
		
		int i,j,o,p;
		float y;
		float[] work = new float[ m+n ];
		
		if ( prime == null )
			for ( i=0; i < m; i++ )
				work[i] = 0.f;
		else
			for ( i=0; i < m; i++)
				work[i] = prime[ offset1+i ];
		
		for ( i=0; i < n; i++ ) {
			y=0;
			o=i;
			p=m;
			for ( j=0; j < m; j++ )
				y -= work[o++]*coeff[--p];
		    
		    prime[ offset2+i ] = work[o] = y;
		}
	}
	
	public int _ve_envelope_search() {
		
		codec_setup_info ci = vi.codec_setup;
		vorbis_info_psy_global gi = ci.psy_g_param;
		envelope_lookup ve = backend_state.ve;
		int i, j;
		
		int first = ve.current/ve.searchstep;
		int last = pcm_current/ve.searchstep-VE_WIN;
		if ( first < 0 )
			first=0;
		
		// make sure we have enough storage to match the PCM
		if ( last+VE_WIN+VE_POST > ve.storage ) {
			ve.storage=last+VE_WIN+VE_POST; // be sure
			
			// ve.mark = _ogg_realloc(ve.mark,ve.storage*sizeof(*ve.mark));
			int[] temp = new int[ ve.storage ];
			System.arraycopy( ve.mark, 0, temp, 0, ve.mark.length );
			ve.mark = temp;
		}
		
		for ( j=first; j < last; j++ ) {
			
			int ret=0;
			
			ve.stretch++;
			if ( ve.stretch > VE_MAXSTRETCH*2 )
				ve.stretch = VE_MAXSTRETCH*2;
			
			for ( i=0; i < ve.ch; i++ ) {
				// float *pcm = pcm[i] + ve.searchstep*(j);
				// ret |= _ve_amp( ve, gi, _pcm, ve.band, ve.filter+i*VE_BANDS, j );
				ret |= ve._ve_amp( gi, pcm[i], ve.searchstep*(j), ve.band, ve.filter, i*VE_BANDS, j );
			}
			
			ve.mark[j+VE_POST] = 0;
			if ( (ret&1) > 0 ) {
				ve.mark[j] = 1;
				ve.mark[j+1] = 1;
			}
			
			if ( (ret&2) > 0 ) {
				ve.mark[j] = 1;
				if ( j > 0 )
					ve.mark[j-1] = 1;
			}
			
			if ( (ret&4) > 0 )
				ve.stretch = -1;
		}
		
//		ve.out( "", null );
		
		ve.current = last*ve.searchstep;

// OOP local variable rename
		int centerW_local = centerW;
		int testW = centerW_local + ci.blocksizes[W]/4 + ci.blocksizes[1]/2 + ci.blocksizes[0]/4;
		
		j = ve.cursor;
		
		while ( j < ve.current-(ve.searchstep) ) { // account for postecho working back one window
			
			if ( j >= testW )
				return 1;
			
			ve.cursor=j;
			
			if ( ve.mark[ j/ve.searchstep ] > 0 ) {
				
				if ( j > centerW_local ) {
					ve.curmark = j;
					if ( j >= testW )
						return 1;
					return 0;
				}
			}
			
			j += ve.searchstep;
		}
		
		return -1;
	}
		
	public boolean _ve_envelope_mark() {
		
		envelope_lookup ve = backend_state.ve;
		codec_setup_info ci = vi.codec_setup;
	
// local OOP variable rename centerW
		int centerW_local = centerW;
		int beginW = centerW_local-ci.blocksizes[W]/4;
		int endW = centerW_local+ci.blocksizes[W]/4;
		
		if ( W > 0 ) {
			beginW -= ci.blocksizes[lW]/4;
			endW += ci.blocksizes[nW]/4;
		} else {
			beginW -= ci.blocksizes[0]/4;
			endW += ci.blocksizes[0]/4;
		}
		
		if ( ve.curmark >= beginW && ve.curmark<endW)
			return true;
		  {
		    int first = beginW/ve.searchstep;
		    int last = endW/ve.searchstep;
		    int i;
		    for ( i=first; i < last; i++ )
		    	if ( ve.mark[i] > 0 )
		    		return true;
		  }
		  
		  return false;
	}
	
	public float _vp_ampmax_decay( float amp ) {
		
		codec_setup_info ci = vi.codec_setup;
		vorbis_info_psy_global gi = ci.psy_g_param;
		
		int n = ci.blocksizes[W]/2;
		float secs = (float)n/vi.rate;
		
		amp += secs*gi.ampmax_att_per_sec;
		if ( amp < -9999 )
			amp=-9999;
		
		return amp;
	}
}