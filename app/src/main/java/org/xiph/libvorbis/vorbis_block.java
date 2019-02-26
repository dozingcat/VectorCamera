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

import org.xiph.libogg.*;
import static org.xiph.libvorbis.vorbis_constants.integer_constants.*;


public class vorbis_block {

	// necessary stream state for linking to the framing abstraction

	float[][] pcm;		 // float **pcm // this is a pointer into local storage
	int pcm_offset;
	oggpack_buffer opb;
  
	int lW;			// long  
	int W;			// long  
	int nW;			// long
	int pcmend;
	int mode;

	int eofflag;
	int granulepos;		// ogg_int64_t 
	int sequence;		// ogg_int64_t
	vorbis_dsp_state vd; 	// *vd // For read-only access of configuration 

	// local storage to avoid remallocing; it's up to the mapping to structure it
	
	Object[] localstore;
	int localtop;		// long
	int localalloc;		// long
	int totaluse;		// long

	alloc_chain reap;

	// bitmetrics for the frame
	int glue_bits;		// long
	int time_bits;		// long
	int floor_bits;		// long
	int res_bits;		// long

	vorbis_block_internal internal;	// void *internal;
	
	// vorbis_window used to hold window arrays and functions
	vorbis_window window;

	
	public vorbis_block( vorbis_dsp_state v ) {

		vd = v;
		localalloc = 0;
		opb = new oggpack_buffer();

		if ( v.analysisp > 0 ) {

			internal = new vorbis_block_internal();
			internal.ampmax = -9999.0f;
			
			for ( int i=0; i < PACKETBLOBS; i++ ) {
				if ( i == PACKETBLOBS/2 ) {
					internal.packetblob[i] = opb;
				}
				else {
					internal.packetblob[i] = new oggpack_buffer();
				}
			}
		}
		
		window = new vorbis_window();
	}
	
	public int _vorbis_block_alloc( int bytes ) {
		
		bytes = (bytes+(WORD_ALIGN-1)) & ~(WORD_ALIGN-1);
		
		if ( bytes+localtop > localalloc ) {
			
		    // can't just _ogg_realloc... there are outstanding pointers 
			if ( localstore != null ) {
				alloc_chain link = new alloc_chain();
				totaluse += localtop;
				link.next = reap;
				// link.ptr = localstore;
				link.ptr = new Object[ localtop ];
				System.arraycopy( localstore, 0, link.ptr, 0, localtop );
				reap = link;
			}
		    // highly conservative
			localalloc = bytes;
			// localstore = _ogg_malloc(vb->localalloc);
			localstore = new Object[ localalloc ];
			localtop = 0;
		}
			
		// void *ret=(void *)(((char *)vb->localstore)+vb->localtop);
		int ret = localtop;
		localtop += bytes;
	
		return ret;
	}
	
	public void _vorbis_block_ripcord() {
		
		// reap the chain
		while ( reap != null ) {
			
			alloc_chain next = reap.next;
			// _ogg_free(reap->ptr);
			// memset(reap,0,sizeof(*reap));
			// _ogg_free(reap);
			reap = null;
		    reap = next;
		}

		// consolidate storage
		if ( totaluse > 0 ) {
			// localstore =_ogg_realloc(vb->localstore,vb->totaluse+vb->localalloc);
			Object[] temp = new Object[ totaluse + localalloc ];
			System.arraycopy( localstore, 0, temp, 0, localalloc );
			localstore = temp;
			localalloc += totaluse;
			totaluse = 0;
		}
		
		// pull the ripcord
		localtop = 0;
		reap = null;
	}
	
	public boolean vorbis_analysis_blockout( vorbis_dsp_state v ) {
		
		int i;
		codec_setup_info ci = v.vi.codec_setup;
		private_state b = v.backend_state;
		vorbis_look_psy_global g = b.psy_g_look;
		int beginW = v.centerW-ci.blocksizes[v.W]/2;
		int centerNext;
		vorbis_block_internal vbi = internal;
		
		// check to see if we're started...
		if ( v.preextrapolate <= 0 )
			return false;
		
		// check to see if we're done...
		if ( v.eofflag == -1 )
			return false;
		
		// By our invariant, we have lW, W and centerW set.  Search for
		// the next boundary so we can determine nW (the next window size)
		// which lets us compute the shape of the current block's window
		
		// we do an envelope search even on a single blocksize; we may still be throwing 
		// more bits at impulses, and envelope search handles  marking impulses too
		
		int bp = v._ve_envelope_search();
		if ( bp == -1 ) {
			
			if ( v.eofflag == 0 )
		    	  return false; // not enough data currently to search for a full long block
			v.nW=0;
		} else {
			
			if ( ci.blocksizes[0] == ci.blocksizes[1] )
				v.nW = 0;
			else
				v.nW = bp;
		}
		
		centerNext = v.centerW+ci.blocksizes[v.W]/4+ci.blocksizes[v.nW]/4;
		
		// center of next block + next block maximum right side.
		
		int blockbound = centerNext+ci.blocksizes[v.nW]/2;
		
		// not enough data yet; although this check is
		// less strict that the _ve_envelope_search, the 
		// search is not run if we only use one block size
		
		if ( v.pcm_current < blockbound )
			return false;
		
		// fill in the block.  Note that for a short window, lW and nW are *short* regardless of actual settings in the stream
		
		_vorbis_block_ripcord();
		lW = v.lW;
		W = v.W;
		nW = v.nW;
		
		if ( v.W > 0 ){
			if ( (v.lW <= 0) || (v.nW <= 0) ) {
				vbi.blocktype = BLOCKTYPE_TRANSITION;
			} else {
				vbi.blocktype = BLOCKTYPE_LONG;
			}
		} else {
			if ( v._ve_envelope_mark() ) {
				vbi.blocktype = BLOCKTYPE_IMPULSE;
			} else {
				vbi.blocktype = BLOCKTYPE_PADDING;
			}
		}
		
		vd = v;
		sequence = v.sequence++;
		granulepos = v.granulepos;
		pcmend = ci.blocksizes[v.W];
		
		// copy the vectors; this uses the local storage in vb
		
		// this tracks 'strongest peak' for later psychoacoustics
		// moved to the global psy state; clean this mess up
		if ( vbi.ampmax > g.ampmax )
			g.ampmax = vbi.ampmax;
		g.ampmax = v._vp_ampmax_decay( g.ampmax );
		vbi.ampmax = g.ampmax;
		
// TODO - CRITICAL - memory arrays
// we have some duplicate memory arrays probably increasing memory footprint
// would need to work on offset variables for pcmdelay
		
//		pcm = _vorbis_block_alloc(vb,sizeof(*pcm)*v.vi.channels);
		pcm = new float[ v.vi.channels ][];

//		vbi.pcmdelay = _vorbis_block_alloc(vb,sizeof(*vbi.pcmdelay)*v.vi.channels);
		vbi.pcmdelay = new float[ v.vi.channels ][];
		
		for ( i=0; i < v.vi.channels; i++ ) {
			
//			vbi.pcmdelay[i] = _vorbis_block_alloc(vb,(pcmend+beginW)*sizeof(*vbi.pcmdelay[i]));
			vbi.pcmdelay[i] = new float[ pcmend+beginW ];
			
//			memcpy( vbi.pcmdelay[i], v.pcm[i], (pcmend+beginW)*sizeof(*vbi.pcmdelay[i]) );
			System.arraycopy( v.pcm[i], 0, vbi.pcmdelay[i], 0, pcmend+beginW );
			
//			pcm[i] = vbi.pcmdelay[i]+beginW;
			pcm[i] = new float[ vbi.pcmdelay[i].length - beginW ];
			System.arraycopy( vbi.pcmdelay[i], beginW, pcm[i], 0, vbi.pcmdelay[i].length - beginW );
		}
		
		// handle eof detection: 
		//     eof==0 means that we've not yet received EOF
		//     eof>0  marks the last 'real' sample in pcm[]
		//     eof<0  'no more to do'; doesn't get here */
		
		if ( v.eofflag > 0) {
			if ( v.centerW >= v.eofflag ) {
				v.eofflag=-1;
				eofflag=1;
				return true;
			}
		}
		
		// advance storage vectors and clean up
		
		int new_centerNext = ci.blocksizes[1]/2;
		int movementW = centerNext-new_centerNext;
		
		if ( movementW > 0 ) {
			
			b.ve._ve_envelope_shift( movementW );
			v.pcm_current -= movementW;
			
			for (i=0; i < v.vi.channels; i++ ) {
				// memmove(v.pcm[i],v.pcm[i]+movementW,v.pcm_current*sizeof(*v.pcm[i]));
				System.arraycopy( v.pcm[i], movementW, v.pcm[i], 0, v.pcm_current );
			}
			
			v.lW = v.W;
			v.W = v.nW;
			v.centerW = new_centerNext;
			
			if ( v.eofflag > 0 ) {
				v.eofflag -= movementW;
				if ( v.eofflag <= 0 )
					v.eofflag = -1;
				// do not add padding to end of stream!
				if ( v.centerW >= v.eofflag ) {
					v.granulepos += movementW-(v.centerW-v.eofflag);
				} else {
					v.granulepos += movementW;
				}
			} else {
				v.granulepos += movementW;
			}
		}
		
		return true;
	}
	
	public boolean vorbis_bitrate_managed() {
		
		// if(bm->queue_binned)return(1);
		
		if ( vd.backend_state.bms.managed > 0 )
			return true;
		
		return false;
	}
	
	public boolean vorbis_analysis( ogg_packet op ) {
						
		glue_bits = 0;
		time_bits = 0;
		floor_bits = 0;
		res_bits = 0;
		
		// first things first.  Make sure encode is ready
		 for ( int i=0; i < PACKETBLOBS; i++ )
			 internal.packetblob[i].oggpack_reset();		  
		
		// we only have one mapping type (0), and we let the mapping code itself
		// figure out what soft mode to use.  This allows easier bitrate management

		mapping0_forward();
		
		if ( op != null ) {
			
			if ( vorbis_bitrate_managed() )
			      return false;	// The app is using a bitmanaged mode... but not using the bitrate management interface.

			op.packet = opb.buffer;
			op.bytes = opb.oggpack_bytes();
			op.b_o_s = 0;
			op.e_o_s = eofflag;
			op.granulepos = granulepos;
			op.packetno = sequence; // for sake of completeness
		}
		
		return true;
	}

	// the floor has already been filtered to only include relevant sections
	static int accumulate_fit( float[] flr, int mdct, int x0, int x1, lsfit_acc a, int n, vorbis_info_floor1 info ) {
		
		int i;
		// int quantized = vorbis_dBquant(flr[x0]);
		
		int xa=0, ya=0, x2a=0, y2a=0, xya=0, na=0, xb=0, yb=0, x2b=0, y2b=0, xyb=0, nb=0;

		a.x0=x0;
		a.x1=x1;
		if ( x1 >= n )
			x1 = n-1;
		
		for ( i=x0; i <= x1; i++ ) {
			
			int quantized = vorbis_dBquant(flr[i]);
			if (quantized > 0 ) {
				
				if ( flr[mdct+i]+info.twofitatten >= flr[i] ) {
					
					xa  += i;
					ya  += quantized;
					x2a += i*i;
					y2a += quantized*quantized;
					xya += i*quantized;
					na++;
					
				} else {
					
					xb  += i;
					yb  += quantized;
					x2b += i*i;
					y2b += quantized*quantized;
					xyb += i*quantized;
					nb++;
				}
			}
		}
		
		xb += xa;
		yb += ya;
		x2b += x2a;
		y2b += y2a;
		xyb += xya;
		nb += na;
		
		// weight toward the actually used frequencies if we meet the threshhold
		
		int weight = new Float( nb*info.twofitweight/(na+1) ).intValue();
		
		a.xa = xa*weight+xb;
		a.ya = ya*weight+yb;
		a.x2a = x2a*weight+x2b;
		a.y2a = y2a*weight+y2b;
		a.xya = xya*weight+xyb;
		a.an = na*weight+nb;
		
		return na;
	}
	
	static int[] fit_line( lsfit_acc[] a, int offset, int fits, int y0, int y1 ) {
		
		int x=0, y=0, x2=0, y2=0, xy=0, an=0, i;
		int x0 = a[offset].x0;
		int x1 = a[offset+fits-1].x1;
		
		for ( i=0; i < fits; i++ ) {
			
			x += a[offset+i].xa;
			y += a[offset+i].ya;
			x2 += a[offset+i].x2a;
			y2 += a[offset+i].y2a;
			xy += a[offset+i].xya;
			an += a[offset+i].an;
		}
		
		// if (*y0 >= 0 ) {
		if ( y0 >= 0 ) {
			
			x += x0;
		    y +=  y0;			// y +=  *y0;
		    x2 +=  x0 *  x0;
		    y2 += y0 * y0;	// y2 += *y0 * *y0;
		    xy += y0 *  x0;		// xy += *y0 *  x0;
		    an++;
		}

		// if(*y1>=0){
		if ( y1 >= 0 ) {
			x += x1;
			y +=  y1;			// y+=  *y1;
			x2 +=  x1 *  x1;
			y2 += y1 * y1;		// y2+= *y1 * *y1;
			xy += y1 *  x1;		// xy+= *y1 *  x1;
			an++;
		}
		
		if ( an > 0) {
			
			// need 64 bit multiplies, which C doesn't give portably as int
			double fx = x;
			double fy = y;
			double fx2 = x2;
			double fxy = xy;
			double denom = 1./(an*fx2-fx*fx);
			double a_local = (fy*fx2-fxy*fx)*denom;
			double b = (an*fxy-fx*fy)*denom;
		    y0 = (int)Math.rint(a_local+b*x0);		// *y0=rint(a_local+b*x0);
		    y1 = (int)Math.rint(a_local+b*x1);		// *y1=rint(a_local+b*x1);
		    
		    // limit to our range!
		    if ( y0 > 1023 )		// if(*y0>1023)*y0=1023;
		    	y0=1023;
		    if ( y1 > 1023 )		// if(*y1>1023)*y1=1023;
		    	y1=1023;
		    if ( y0 < 0 )		// if(*y0<0)*y0=0;
		    	y0=0;
		    if ( y1 < 0 )		// if(*y1<0)*y1=0;
		    	y1=0;
		} else {
		    y0 = 0;		// *y0=0;
		    y1 = 0;		// *y1=0;
		}
		
		int[] return_buffer = { y0, y1 };
		return return_buffer;
	}
	
	static int inspect_error( int x0, int x1, int y0, int y1, float[] mask, int mdct, vorbis_info_floor1 info ) {
		
		int dy = y1-y0;
		int adx = x1-x0;
		int ady = Math.abs(dy);
		int base = dy/adx;
		int sy = (dy<0?base-1:base+1);
		int x = x0;
		int y = y0;
		int err = 0;
		int val = vorbis_dBquant(mask[x]);
		int mse = 0;
		int n = 0;
		
		ady -= Math.abs(base*adx);
		
		mse = (y-val);
		mse *= mse;
		n++;
		
		if ( mask[mdct+x]+info.twofitatten >= mask[x] ) {
			if ( y+info.maxover < val )
		    	return(1);
			if ( y-info.maxunder > val )
				return(1);
		}
		
		while ( ++x < x1 ) {
			
			err = err+ady;
			if ( err >= adx ) {
				err-=adx;
				y+=sy;
			} else {
				y += base;
			}
			
			val = vorbis_dBquant(mask[x]);
			mse += ((y-val)*(y-val));
			n++;
			if ( mask[mdct+x]+info.twofitatten >= mask[x] ) {
				if ( val > 0 ) {
					if (y+info.maxover<val )
						return(1);
					if (y-info.maxunder>val )
						return(1);
				}
			}
		}
		
		if ( info.maxover*info.maxover/n > info.maxerr)
			return(0);
		if ( info.maxunder*info.maxunder/n > info.maxerr)
			return(0);
		if ( mse/n > info.maxerr )
			return(1);
		return(0);
	}
	
	static int post_Y( int[] A, int[] B, int pos ) {
		
		if ( A[pos] < 0 )
			return B[pos];
		if ( B[pos] < 0 )
			return A[pos];
		
		return (A[pos]+B[pos])>>>1;
	}

	public int[] floor1_fit( vorbis_look_floor1 look, int logmdct, float[] logmask) {
		
		  int i,j;
		  
		  vorbis_info_floor1 info = look.vi;
		  int n = look.n;
		  int posts = look.posts;
		  int nonzero = 0;
		  
		  // lsfit_acc fits[VIF_POSIT+1];
		  // int fit_valueA[VIF_POSIT+2]; // index by range list position
		  // int fit_valueB[VIF_POSIT+2]; // index by range list position
		  // int loneighbor[VIF_POSIT+2]; // sorted index of range list position (+2)
		  // int hineighbor[VIF_POSIT+2]; 
		  // int *output=NULL;
		  // int memo[VIF_POSIT+2];

		  lsfit_acc[] fits = new lsfit_acc[VIF_POSIT+1];
		  for ( i=0; i < VIF_POSIT+1; i++ )
			  fits[i] = new lsfit_acc();
		  
		  int[] fit_valueA = new int[VIF_POSIT+2]; // index by range list position
		  int[] fit_valueB = new int[VIF_POSIT+2]; // index by range list position

		  int[] loneighbor = new int[VIF_POSIT+2]; // sorted index of range list position (+2)
		  int[] hineighbor = new int[VIF_POSIT+2]; 
		  int[] output = null;
		  int[] memo= new int[VIF_POSIT+2];
		  
		  int[] return_buffer = new int[2];

		  for (i=0;i<posts;i++)
			  fit_valueA[i]=-200; // mark all unused
		  for (i=0;i<posts;i++)
			  fit_valueB[i]=-200; // mark all unused
		  for (i=0;i<posts;i++)
			  loneighbor[i]=0; // 0 for the implicit 0 post
		  for (i=0;i<posts;i++)
			  hineighbor[i]=1; // 1 for the implicit post at n
		  for (i=0;i<posts;i++)
			  memo[i]=-1;      // no neighbor yet
		  
		  // quantize the relevant floor points and collect them into line fit
		  // structures (one per minimal division) at the same time
		  
		  if ( posts == 0 ) {
			  nonzero += accumulate_fit( logmask, logmdct, 0, n, fits[0], n, info );
		  } else {
			  for ( i=0; i < posts-1; i++ )
				  nonzero += accumulate_fit( logmask, logmdct, look.sorted_index[i], look.sorted_index[i+1], fits[i], n, info );
		  }
		  
		  if ( nonzero > 0 ) {
			  
			  // start by fitting the implicit base case....
			  int y0 = -200;
			  int y1 = -200;
			  return_buffer = fit_line( fits, 0, posts-1, y0, y1 );
			  y0 = return_buffer[0];
			  y1 = return_buffer[1];
			  
			  fit_valueA[0] = y0;
			  fit_valueB[0] = y0;
			  fit_valueB[1] = y1;
			  fit_valueA[1] = y1;
			  
			  // Non degenerate case
			  // start progressive splitting.  This is a greedy, non-optimal
		      // algorithm, but simple and close enough to the best answer.
			  for ( i=2; i<posts; i++ ) {
				  int sortpos = look.reverse_index[i];
				  int ln = loneighbor[sortpos];
				  int hn = hineighbor[sortpos];
				  
				  // eliminate repeat searches of a particular range with a memo
				  if ( memo[ln]!= hn ) {
					  // haven't performed this error search yet
					  int lsortpos = look.reverse_index[ln];
					  int hsortpos = look.reverse_index[hn];
					  memo[ln]=hn;
					  
					  // A note: we want to bound/minimize *local*, not global, error
					  int lx = info.postlist[ln];
					  int hx = info.postlist[hn];	  
					  int ly = post_Y( fit_valueA, fit_valueB, ln );
					  int hy = post_Y( fit_valueA, fit_valueB, hn );
					  
					  if ( ly == -1 || hy == -1 ) {
						  System.out.println( "ERROR: We want to bound/minimize *local*, not global" );
						  System.exit(1);
					  }
					  
					  if ( inspect_error( lx, hx, ly, hy, logmask, logmdct, info ) > 0 ) {
						  
						  // outside error bounds/begin search area.  Split it.
						  int ly0 = -200;
						  int ly1 = -200;
						  int hy0 = -200;
						  int hy1 = -200;
						  return_buffer = fit_line( fits, lsortpos, sortpos-lsortpos, ly0, ly1 );
						  ly0 = return_buffer[0];
						  ly1 = return_buffer[1];
						  return_buffer = fit_line( fits, sortpos, hsortpos-sortpos, hy0, hy1 );
						  hy0 = return_buffer[0];
						  hy1 = return_buffer[1];
						  
						  // store new edge values
						  fit_valueB[ln] = ly0;
						  if ( ln == 0 )
							  fit_valueA[ln] = ly0;
						  fit_valueA[i] = ly1;
						  
						  fit_valueB[i] = hy0;
						  fit_valueA[hn] = hy1;
						  if ( hn == 1 )
							  fit_valueB[hn] = hy1;
						  
						  if ( ly1 >= 0 || hy0 >= 0 ) {
							  // store new neighbor values
							  for ( j=sortpos-1; j >= 0; j-- )
								  if ( hineighbor[j] == hn )
									  hineighbor[j] = i;
								  else
									  break;
							  for ( j=sortpos+1; j < posts; j++ )
								  if ( loneighbor[j] == ln )
									  loneighbor[j] = i;
								  else
									  break;
						  }
					  } else {
						  fit_valueA[i] = -200;
						  fit_valueB[i] = -200;
					  }
				  }
			  }
			  
			  // output = _vorbis_block_alloc(vb,sizeof(*output)*posts);
			  output = new int[ posts ];
			  
			  output[0] = post_Y( fit_valueA, fit_valueB, 0 );
			  output[1] = post_Y( fit_valueA, fit_valueB, 1 );
			  
			  // fill in posts marked as not using a fit; we will zero back out to 'unused' when encoding
			  // them so long as curve interpolation doesn't force them into use
			  
			  for ( i=2; i < posts; i++ ) {
				  
				  int ln = look.loneighbor[i-2];
				  int hn = look.hineighbor[i-2];
				  int x0 = info.postlist[ln];
				  int x1 = info.postlist[hn];
				  y0 = output[ln];
				  y1 = output[hn];
				  
				  int predicted = render_point( x0, x1, y0, y1, info.postlist[i] );
				  int vx = post_Y( fit_valueA, fit_valueB, i );
				  
				  if ( vx >= 0 && predicted != vx ) { 
					  output[i] = vx;
				  } else {
					  output[i] = predicted|0x8000;
				  }
			  }
		  }

		  return output;
	}
	
	static int[] floor1_interpolate_fit( vorbis_look_floor1 look, int[] A,int[] B, int del ) {
		
		int i;
		int posts = look.posts;
		int[] output = null;
		
		if ( A != null && B != null ) {
			
			// output=_vorbis_block_alloc(vb,sizeof(*output)*posts);
			output = new int[ posts ];
			
			for ( i=0; i < posts; i++ ) {
				output[i] = ((65536-del)*(A[i]&0x7fff)+del*(B[i]&0x7fff)+32768)>>16;
			
			boolean aTrue = (A[i]&0x8000) > 0;
			boolean bTrue = (B[i]&0x8000) > 0;
			if ( aTrue && bTrue )
				output[i] |= 0x8000;
			}
		}
		return(output);	
	}
	
	static float dipole_hypot( float a, float b ) {
		
		if ( a > 0. ) {
			if( b > 0. )return new Double( Math.sqrt(a*a+b*b) ).floatValue();
			if( a > -b )return new Double( Math.sqrt(a*a-b*b) ).floatValue();
			return new Double( -Math.sqrt(b*b-a*a) ).floatValue();
		}
		
		if( b < 0. )return new Double( -Math.sqrt(a*a+b*b) ).floatValue();
		if( -a > b )return new Double( -Math.sqrt(a*a-b*b) ).floatValue();
		
		return new Double( Math.sqrt(b*b-a*a) ).floatValue();
	}
	
	static float round_hypot( float a, float b ) {
		
		if ( a > 0. ) {
			if ( b > 0. )return new Double( Math.sqrt(a*a+b*b) ).floatValue();
			if ( a > -b )return new Double( Math.sqrt(a*a+b*b) ).floatValue();
			return new Double( -Math.sqrt(b*b+a*a) ).floatValue();
		}
		
		if ( b < 0. )return new Double( -Math.sqrt(a*a+b*b) ).floatValue();
		
		if ( -a > b )return new Double( -Math.sqrt(a*a+b*b) ).floatValue();
		
		return new Double( Math.sqrt(b*b+a*a) ).floatValue();
	}
	
	private float[][] _vp_quantize_couple_memo( vorbis_info_psy_global g, vorbis_look_psy p, vorbis_info_mapping0 vi, float[][] mdct) {
		
		int i, j;
		int n = p.n;
		
		// float **ret=_vorbis_block_alloc(vb,vi->coupling_steps*sizeof(*ret));
		float[][] ret = new float[ vi.coupling_steps ][ n ];
		int limit = g.coupling_pointlimit[p.vi.blockflag][PACKETBLOBS/2];
		
		for ( i=0; i < vi.coupling_steps; i++ ) {
			
			float[] mdctM = mdct[vi.coupling_mag[i]];
			float[] mdctA = mdct[vi.coupling_ang[i]];
			// ret[i] = _vorbis_block_alloc(vb,n*sizeof(**ret));
			for ( j=0; j < limit; j++ )
				ret[i][j] = dipole_hypot( mdctM[j], mdctA[j] );
			for ( ; j < n; j++ )
				ret[i][j] = round_hypot( mdctM[j], mdctA[j] );
		}
		
		return ret;
	}

	private static void SORT4( int o, float[] data, int offset, int[] n ) {
		
		if ( Math.abs(data[offset+o+2]) >= Math.abs(data[offset+o+3]) )
			if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+1]) )
				if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+2]) )
					if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+2]) )
					{ n[o]=o+0; n[o+1]=o+1; n[o+2]=o+2; n[o+3]=o+3; }
					else
						if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+3]) )
						{ n[o]=o+0; n[o+1]=o+2; n[o+2]=o+1; n[o+3]=o+3; }
						else
						{ n[o]=o+0; n[o+1]=o+2; n[o+2]=o+3; n[o+3]=o+1; }
				else
					if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+3]) )
						if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+3]) )
						{ n[o]=o+2; n[o+1]=o+0; n[o+2]=o+1; n[o+3]=o+3; }
						else
						{ n[o]=o+2; n[o+1]=o+0; n[o+2]=o+3; n[o+3]=o+1; }
					else
					{ n[o]=o+2; n[o+1]=o+3; n[o+2]=o+0; n[o+3]=o+1; }
			else
				if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+2]) )
					if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+2]) )
					{ n[o]=o+1; n[o+1]=o+0; n[o+2]=o+2; n[o+3]=o+3; }
					else
						if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+3]) )
						{ n[o]=o+1; n[o+1]=o+2; n[o+2]=o+0; n[o+3]=o+3; }
						else
						{ n[o]=o+1; n[o+1]=o+2; n[o+2]=o+3; n[o+3]=o+0; }
				else
					if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+3]) )
						if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+3]) )
						{ n[o]=o+2; n[o+1]=o+1; n[o+2]=o+0; n[o+3]=o+3; }
						else
						{ n[o]=o+2; n[o+1]=o+1; n[o+2]=o+3; n[o+3]=o+0; }
					else
					{ n[o]=o+2; n[o+1]=o+3; n[o+2]=o+1; n[o+3]=o+0; }
		else
			if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+1]) )
				if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+3]) )
					if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+3]) )
					{ n[o]=o+0; n[o+1]=o+1; n[o+2]=o+3; n[o+3]=o+2; }
					else
						if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+2]) )
						{ n[o]=o+0; n[o+1]=o+3; n[o+2]=o+1; n[o+3]=o+2; }
						else
						{ n[o]=o+0; n[o+1]=o+3; n[o+2]=o+2; n[o+3]=o+1; }
				else
					if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+2]) )
						if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+2]) )
						{ n[o]=o+3; n[o+1]=o+0; n[o+2]=o+1; n[o+3]=o+2; }
						else
						{ n[o]=o+3; n[o+1]=o+0; n[o+2]=o+2; n[o+3]=o+1; }
					else
					{ n[o]=o+3; n[o+1]=o+2; n[o+2]=o+0; n[o+3]=o+1; }
			else
				if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+3]) )
					if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+3]) )
					{ n[o]=o+1; n[o+1]=o+0; n[o+2]=o+3; n[o+3]=o+2; }
					else
						if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+2]) )
						{ n[o]=o+1; n[o+1]=o+3; n[o+2]=o+0; n[o+3]=o+2; }
						else
						{ n[o]=o+1; n[o+1]=o+3; n[o+2]=o+2; n[o+3]=o+0; }
				else
					if ( Math.abs(data[offset+o+1]) >= Math.abs(data[offset+o+2]) )
						if ( Math.abs(data[offset+o+0]) >= Math.abs(data[offset+o+2]) )
						{ n[o]=o+3; n[o+1]=o+1; n[o+2]=o+0; n[o+3]=o+2; }
						else
						{ n[o]=o+3; n[o+1]=o+1; n[o+2]=o+2; n[o+3]=o+0; }
					else
					{ n[o]=o+3; n[o+1]=o+2; n[o+2]=o+1; n[o+3]=o+0; }
	}
	
	static void sortindex_fix8( int[] index, int ioff, float[] data, int offset ) {
		
		int i,j,k;
		int[] n = new int[ 8 ];
		
		// index+=offset;
		// data+=offset;
		
		SORT4( 0, data, offset, n );
		SORT4( 4, data, offset, n );
		
		j=0;k=4;
		for ( i=0; i < 8; i++ )
			index[ioff+offset+i] = n[ ((k>=8) || (j<4) && (Math.abs(data[offset+0+n[j]]) >= Math.abs(data[offset+0+n[k]]))?j++:k++) ] + offset;
	}
	
	static void sortindex_fix32( int[] index, int ioff, float[] data, int offset ) {
		
		int i,j,k;
		int[] n = new int[ 32 ];
		
		for ( i=0; i < 32; i+=8 )
			sortindex_fix8( index, ioff, data, offset+i );
		
		ioff += offset;
		
		for ( i=j=0,k=8; i < 16; i++ )
			n[i]=index[ ioff+ ((k>=16)||(j<8) && (Math.abs(data[0+index[ioff+j]]) >= Math.abs(data[0+index[ioff+k]]))?j++:k++ ) ];
		
		for ( i=j=16,k=24; i < 32; i++ )
			n[i]=index[ ioff+ ((k>=32)||(j<24) && (Math.abs(data[0+index[ioff+j]]) >= Math.abs(data[0+index[ioff+k]]))?j++:k++ ) ];
		
		for ( i=j=0,k=16; i < 32; i++ )
			index[ioff+i] = n[ ((k>=32)||(j<16) && (Math.abs(data[0+n[j]]) >= Math.abs(data[0+n[k]]))?j++:k++ ) ];
	}
	
	static void sortindex_shellsort( int[] index, int ioff, float[] data, int offset, int count ) {
		
		int gap,pos,left;
		// int right;
		int i,j;
		
		ioff += offset;
		
		for ( i=0; i < count; i++ )
			index[ioff+i] = i+offset;
		gap=1;
		while ( gap <= count )
			gap=gap*3+1;
		gap/=3;
		if ( gap >= 4 )
			gap/=3;
		
		while ( gap > 0 ){
			for ( pos=gap; pos < count; pos++ ) {
				for ( left=pos-gap; left >= 0; left-=gap ) {
					i=index[ioff+left];
					j=index[ioff+left+gap];
					if ( !(Math.abs(data[0+i]) >= Math.abs(data[0+j])) ) {
						index[ioff+left]=j;
						index[ioff+left+gap]=i;
						}
					else
						break;
					}
				}
			gap/=3;
			}
		}
	
	static void sortindex( int[] index, int ioff, float[] data, int offset, int count ) {
		
		if ( count == 8 )
			sortindex_fix8( index, ioff, data, offset );
		else
			if ( count == 32 )
				sortindex_fix32( index, ioff, data, offset );
			else
				sortindex_shellsort( index, ioff, data, offset, count );
	}
	
	private int[][] _vp_quantize_couple_sort( vorbis_look_psy p, vorbis_info_mapping0 vi, float[][] mags ) {
		
		if ( p.vi.normal_point_p > 0 ) {
			
			int i,j;
			int n = p.n;
			
			// int **ret=_vorbis_block_alloc(vb,vi->coupling_steps*sizeof(*ret));
			int[][] ret = new int[ vi.coupling_steps ][ n ];
			int partition = p.vi.normal_partition;
			// float **work=alloca(sizeof(*work)*partition);
			// float[][] work = new float[ partition ][];
			
			for ( i=0; i < vi.coupling_steps; i++ ) {
				
				// ret[i]=_vorbis_block_alloc(vb,n*sizeof(**ret));
				
				for ( j=0; j < n; j+=partition ) {
					/*
					for ( k=0; k < partition; k++ ) {
						// work[k] = mags[i]+k+j;
						work[k] = new float[ mags[i].length - k+j ];
						System.arraycopy(mags[i], k+j, work[k], 0, mags[i].length - k+j );
					}
					
					// qsort(work,partition,sizeof(*work),apsort);
					Arrays.sort( work );
					
					// for( k=0; k < partition; k++ )
					// 	ret[i][k+j] = work[k]-mags[i];
					for ( k=0; k < partition; k++)
						ret[i][k+j] = work[k] - mags[i];
					*/
					sortindex( ret[i], 0, mags[i], j, partition );
				}
			}
			return(ret);
		}
		return null;
	}
	
	private void _vp_noise_normalize_sort( vorbis_look_psy p, float[] magnitudes,int[] sortedindex ) {
		
		int j;
		int n = p.n;
		
		vorbis_info_psy vi = p.vi;
		int partition = vi.normal_partition;
		// float **work=alloca(sizeof(*work)*partition);
		int start = vi.normal_start;
		
		for ( j=start; j < n; j+=partition ) {
			
			if ( j+partition > n )
				partition = n-j;
			
			//    for(i=0;i<partition;i++)work[i]=magnitudes+i+j;
			//    qsort(work,partition,sizeof(*work),apsort);
			//    for(i=0;i<partition;i++){
			//      sortedindex[i+j-start]=work[i]-magnitudes;
			//    }
			sortindex( sortedindex, -start, magnitudes, j, partition );
		}
	}
	
	private void hf_reduction( vorbis_info_psy_global g, vorbis_look_psy p,  vorbis_info_mapping0 vi, float[][] mdct ) {
		
		int i,j;
		int n = p.n;
		int de = (int)(0.3*p.m_val);
		int limit = g.coupling_pointlimit[p.vi.blockflag][PACKETBLOBS/2];
		// int start = p.vi.normal_start;
		
		for ( i=0; i < vi.coupling_steps; i++ ) {
			// for(j=start; j<limit; j++){} // ???
			for ( j=limit; j < n; j++ ) 
				mdct[i][j] *= (1.0 - de*((float)(j-limit) / (float)(n-limit)));
		}
	}
	
	private void _vp_remove_floor( vorbis_look_psy p, float[] mdct, int[] codedflr, float[] residue, int sliding_lowpass ) { 
		
		int i;
		int n = p.n;
		
		if ( sliding_lowpass > n )
			sliding_lowpass = n;
		
		for ( i=0; i < sliding_lowpass; i++ )
			residue[i] = mdct[i]*FLOOR1_fromdB_INV_LOOKUP[codedflr[i]];
		
		for ( ; i < n; i++ )
			residue[i] = 0.f;
	}
	
	private void _vp_noise_normalize( vorbis_look_psy p, float[] in, int out, int[] sortedindex ) {
		
		// int flag = 0;
		int i;
		int j = 0;
		int n = p.n;
		
		vorbis_info_psy vi = p.vi;
		int partition = vi.normal_partition;
		int start = vi.normal_start;
		
		if ( start > n )
			start = n;
		
		if ( vi.normal_channel_p > 0 ) {
			
			for ( ; j < start; j++ )
				in[out+j] = (float)Math.rint(in[j]);
			
			for ( ; j+partition <= n; j+=partition ) {
				
				float acc = 0.f;
				int k;
				
				for ( i=j; i < j+partition; i++ )
					acc += in[i]*in[i];
				
				for ( i=0 ; i < partition; i++ ) {
					
					k = sortedindex[i+j-start];
					
					if ( in[k]*in[k] >= .25f ) {
						
						in[out+k] = (float)Math.rint(in[k]);
						acc-=in[k]*in[k];
						// flag=1;
					} else {
						
						if ( acc < vi.normal_thresh )
							break;
						in[out+k] = unitnorm( in[k] );
						acc -= 1.;
					}
				}
				
				for ( ; i < partition; i++ ) {
					
					k = sortedindex[i+j-start];
					in[out+k] = 0.f;
					
				}
			}
		}
		
		for ( ; j < n; j++ )
			in[out+j]=(float)Math.rint(in[j]);
	}
	
	static float[] couple_lossless( float A, float B, float qA, float qB ) {
		
		int test1 = ((Math.abs(qA) > Math.abs(qB))?1:0);
		test1 -= ((Math.abs(qA) < Math.abs(qB))?1:0);
		
		if( test1 <= 0 )
			test1 = ( (((Math.abs(A)>Math.abs(B)))?1:0) <<1)-1;
		
		if ( test1 == 1 ) {
			qB = (qA>0.f?qA-qB:qB-qA);
		} else {
			float temp = qB;  
			qB = (qB>0.f?qA-qB:qB-qA);
			qA = temp;
		}
		
		if ( qB > Math.abs(qA)*1.9999f ) {
			qB= -Math.abs(qA)*2.f;
			qA= -qA;
		}
		
		float[] ret = { qA, qB };
		return ret;
	}
	
	static float[] hypot_lookup = { // [32]
		-0.009935f, -0.011245f, -0.012726f, -0.014397f, 
		-0.016282f, -0.018407f, -0.020800f, -0.023494f, 
		-0.026522f, -0.029923f, -0.033737f, -0.038010f, 
		-0.042787f, -0.048121f, -0.054064f, -0.060671f, 
		-0.068000f, -0.076109f, -0.085054f, -0.094892f, 
		-0.105675f, -0.117451f, -0.130260f, -0.144134f, 
		-0.159093f, -0.175146f, -0.192286f, -0.210490f, 
		-0.229718f, -0.249913f, -0.271001f, -0.292893f };
	
	static float[] precomputed_couple_point( float premag, int floorA, int floorB, float mag, float ang ) {
		
		int test = ((floorA > floorB)?1:0)-1;
		int offset = 31-Math.abs(floorA-floorB);
		float floormag = hypot_lookup[(((offset<0)?1:0)-1)&offset]+1.f;
		
		floormag *= FLOOR1_fromdB_INV_LOOKUP[(floorB&test)|(floorA&(~test))];
		
		mag = premag*floormag;
		ang = 0.f;
		
		float[] ret = { mag, ang };
		return ret;
	}
	
	private void _vp_couple( int blobno, vorbis_info_psy_global g, vorbis_look_psy p, vorbis_info_mapping0 vi,
							float[][] res, float[][] mag_memo, int[][] mag_sort, int[][] ifloor, int[] nonzero, int sliding_lowpass ) {
		
		int i,j,k;
		int n = p.n;
		
		// perform any requested channel coupling
		// point stereo can only be used in a first stage (in this encoder) because of the dependency on floor lookups
		
		for ( i=0; i < vi.coupling_steps; i++ ) {
			
			// once we're doing multistage coupling in which a channel goes
			// through more than one coupling step, the floor vector
			// magnitudes will also have to be recalculated an propogated
			// along with PCM.  Right now, we're not (that will wait until 5.1
			// most likely), so the code isn't here yet. The memory management
			// here is all assuming single depth couplings anyway.
			
			// make sure coupling a zero and a nonzero channel results in two nonzero channels.
			
			if ( ( nonzero[vi.coupling_mag[i]] > 0 ) || ( nonzero[vi.coupling_ang[i]] > 0 ) ) {
				
				float[] rM = res[vi.coupling_mag[i]];
				float[] rA = res[vi.coupling_ang[i]];
				// float *qM = rM+n;
				int qM = n;
				// float *qA = rA+n;
				int qA = n;
				int[] floorM = ifloor[vi.coupling_mag[i]];
				int[] floorA = ifloor[vi.coupling_ang[i]];
				float prepoint = stereo_threshholds[g.coupling_prepointamp[blobno]];
				float postpoint = stereo_threshholds[g.coupling_postpointamp[blobno]];
				int partition = ( (p.vi.normal_point_p>0)?p.vi.normal_partition:p.n );
				int limit = g.coupling_pointlimit[p.vi.blockflag][blobno];
				int pointlimit = limit;
				
				nonzero[vi.coupling_mag[i]] = 1; 
				nonzero[vi.coupling_ang[i]] = 1; 
				
				// The threshold of a stereo is changed with the size of n
				if ( n > 1000 )
					postpoint = stereo_threshholds_limited[g.coupling_postpointamp[blobno]]; 
				
				for ( j=0; j < p.n; j+=partition ) {
					
					float acc = 0.f;
					
					for ( k=0; k < partition; k++ ) {
						
						int l=k+j;
						
						if ( l < sliding_lowpass ) {
							
							if ( ( l >= limit && Math.abs(rM[l]) < postpoint && Math.abs(rA[l]) < postpoint) || (Math.abs(rM[l]) < prepoint && Math.abs(rA[l]) < prepoint ) ) {
								
								float[] return_buffer = precomputed_couple_point( mag_memo[i][l], floorM[l], floorA[l], rM[qM+l], rA[qA+l] );
								rM[qM+l] = return_buffer[0];
								rA[qA+l] = return_buffer[1];
								
								if ( Math.rint(rM[qM+l]) == 0 )
									acc += rM[qM+l]*rM[qM+l];
								
							} else {
								float[] return_buffer = couple_lossless( rM[l], rA[l], rM[qM+l], rA[qA+l] );
								rM[qM+l] = return_buffer[0];
								rA[qA+l] = return_buffer[1];								
							}
						} else {
							rM[qM+l] = 0.f;
							rA[qA+l] = 0.f;
						}
					}
					
					if ( p.vi.normal_point_p > 0 ) {
						
						for ( k=0; k < partition && acc >= p.vi.normal_thresh; k++ ) {
							
							int l = mag_sort[i][j+k];
							
							if ( l < sliding_lowpass && l >= pointlimit && Math.rint(rM[qM+l]) == 0 ) {
								
								rM[qM+l] = unitnorm(rM[qM+l]);
								acc -= 1.f;
							}
						} 
					}
				}
			}
		}
	}
	
	// designed for stereo or other modes where the partition size is an
	// integer multiple of the number of channels encoded in the current submap
	private int[][] _2class( vorbis_look_residue0 look, float[][] in, int in_offset, int ch ) {
		
	  int i,j,k,l;
	  
	  vorbis_info_residue0 info = look.info;

	  // move all this setup out later
	  int samples_per_partition = info.grouping;
	  int possible_partitions = info.partitions;
	  int n = info.end - info.begin;

	  int partvals = n/samples_per_partition;
	  // int[][] partword=_vorbis_block_alloc(vb,sizeof(*partword));
	  // partword[0] = _vorbis_block_alloc(vb,n*ch/samples_per_partition*sizeof(*partword[0]));
	  // memset(partword[0],0,n*ch/samples_per_partition*sizeof(*partword[0]));
	  int[][] partword= new int[1][n*ch/samples_per_partition];
	  
	  for ( i=0,l=info.begin/ch; i < partvals; i++ ) {
		  
		  float magmax = 0.f;
		  float angmax = 0.f;
		  
		  for( j=0; j<samples_per_partition; j+=ch ) {
			  
			  if ( Math.abs(in[0][l+in_offset]) > magmax )
				  magmax = Math.abs(in[0][l+in_offset]);
			  
			  for ( k=1; k < ch; k++ )
				  if ( Math.abs(in[k][l+in_offset]) > angmax )
					  angmax = Math.abs(in[k][l+in_offset]);
			  l++;  
		  }
		  
		  for ( j=0; j < possible_partitions-1; j++ )
			  if ( magmax <= info.classmetric1[j] && angmax <= info.classmetric2[j] )
				  break;
		  
		  partword[0][i] = j;  
	  }
	  
	  look.frames++;

	  return partword;  
	}

	
	private int[][] res2_class( vorbis_look_residue0 vl, float[][] in, int in_offset, int[] nonzero, int ch ) {
		
		int i,used=0;
		for ( i=0; i < ch; i++ )
			if ( nonzero[i] > 0 )
				used++;
		if ( used > 0)
			return _2class( vl, in, in_offset, ch );
		else
			return new int[][] {{ 0 }};
	}
	
	// break an abstraction and copy some code for performance purposes
	private int local_book_besterror( codebook book, float[] a, int a_off ) {
		
		int dim=book.dim,i,k,o;
		int best = 0;
		
		encode_aux_threshmatch tt = book.c.thresh_tree;
		
		// find the quant val of each scalar
		for ( k=0, o=dim; k < dim; ++k ) {

			float val = a[a_off + --o];
			i = tt.threshvals >> 1;
		
			if ( val < tt.quantthresh[i] ) {
				
				if ( val < tt.quantthresh[i-1] ) {
					for ( --i; i > 0; --i )
						if ( val >= tt.quantthresh[i-1] )
							break;	
				}	
			}
			else {
				
				for ( ++i; i < tt.threshvals-1; ++i )
					if ( val < tt.quantthresh[i] )
						break;	
			}
			
			best = (best*tt.quantvals) + tt.quantmap[i];	
		}
		
		// regular lattices are easy :-)
		
		if ( book.c.lengthlist[best] <= 0 ) {
			
			final static_codebook c = book.c;
			int index,j;
			float bestf = 0.f;
			// float[] e = book.valuelist;
			int e_off = 0;
			best = -1;
			
			for ( index=0; index < book.entries; index++ ) {
				
				if ( c.lengthlist[index] > 0 ) {
					float this_local = 0.f;
					for ( j=0; j < dim; j++ ) {
						float val = (book.valuelist[e_off+j]-a[a_off+j]);
						this_local += val*val;
					}
					if ( best == -1 || this_local < bestf ) {
						bestf=this_local;
						best=index;
					}
				}
				e_off += dim;	
			}
		}
		
		{
			// float *ptr = book.valuelist + best*dim;
			int ptr = best * dim;
			for ( i=0; i < dim; i++ ) {
				// *a++ -= *ptr++;
				a[a_off++] -= book.valuelist[ptr++];
			}
		}
		
		return best;
	}

	private int _encodepart( oggpack_buffer opb_local, float[] vec, int vec_off, int n, codebook book, int[] acc ) {
		
		int i,bits = 0;
		int dim = book.dim;
		int step = n/dim;
		
		for ( i=0; i < step; i++ ) {
			
			int entry = local_book_besterror( book, vec, vec_off+i*dim );
			
			// #ifdef TRAIN_RES
			//    acc[entry]++;
			// #endif
			
			bits += opb_local.vorbis_book_encode( book, entry );
		}
		
		return bits;	
	}
	
	private boolean _01forward( oggpack_buffer opb_local, vorbis_look_residue0 look, float[] in, int ch, int[][] partword ) {
		
		int i,j,k,s;	// long
		vorbis_info_residue0 info = look.info;
		
		// move all this setup out later
		int samples_per_partition = info.grouping;
		int possible_partitions = info.partitions;
		int partitions_per_word = look.phrasebook.dim;
		int n = info.end - info.begin;
		
		int partvals = n/samples_per_partition;
		// long resbits[128];
		// long resvals[128];
		// memset(resbits,0,sizeof(resbits));
		// memset(resvals,0,sizeof(resvals));
		int[] resbits = new int[ 128 ];
		int[] resvals = new int[ 128 ];
		
		// we code the partition words for each channel, then the residual
		// words for a partition per channel until we've written all the
		// residual words for that partition word.  Then write the next
		// partition channel words...
		
		for ( s=0; s < look.stages; s++ ) {
			
			for ( i=0; i < partvals; ) {
				
				// first we encode a partition codeword for each channel
				if ( s==0 ) {
					
					for ( j=0; j < ch; j++ ) {
						int val = partword[j][i];	// long
						for ( k=1; k < partitions_per_word; k++ ) {
							val*=possible_partitions;
							if ( i+k < partvals )
								val += partword[j][i+k];	
						}
						
						// training hack
						if ( val < look.phrasebook.entries )
							look.phrasebits += opb_local.vorbis_book_encode( look.phrasebook, val );
						else
							System.out.println( "! - val < look.phrasebook.entries - !" );	
					}	
				}
				
				// now we encode interleaved residual values for the partitions
				for ( k=0; k < partitions_per_word && i < partvals; k++, i++ ) {
					
					int offset = i*samples_per_partition + info.begin;	// long
					
					for ( j=0; j < ch; j++ ) {
						
						if ( s==0 )
							resvals[partword[j][i]] += samples_per_partition;
						
						if ( ( info.secondstages[partword[j][i]]&(1<<s) ) > 0 ) {
							
							codebook statebook = look.partbooks[partword[j][i]][s];
							if ( statebook != null ) {
								int ret;
								// long *accumulator = NULL;
								int[] accumulator = null;	// long
								
								ret = _encodepart( opb_local, in, j+offset, samples_per_partition, statebook, accumulator );
								
								look.postbits += ret;
								resbits[partword[j][i]] += ret;
							}
						}
					}
				}
			}
		}
		return true;	
	}
	
	// res2 is slightly more different; all the channels are interleaved into a single vector and encoded.
	private boolean res2_forward( oggpack_buffer opb_local, vorbis_look_residue0 vl, float[][] in, int in_offset, float[][] out, int[] nonzero, int ch, int[][] partword) {
		
		int i,j,k;			// long
		int n = pcmend/2;	// long
		int used = 0;		// long
		
		// don't duplicate the code; use a working vector hack for now and reshape ourselves into a single channel res1
		// ugly; reallocs for each coupling pass :-(
		
		// float *work=_vorbis_block_alloc(vb,ch*n*sizeof(*work));
		float[] work = new float[ ch*n ];
		
		for ( i=0; i < ch; i++ ) {
			
			// float *pcm = in[i];
			
			if ( nonzero[i] > 0 )
				used++;
			
			for ( j=0, k=i; j < n; j++, k+=ch )
				work[k] = in[i][j+in_offset];	
		}
		
		if ( used > 0 ) {
			
			boolean ret = _01forward( opb_local, vl, work, 1, partword );
			
// TODO - offset for out
// out is currently always called as null
			
			// update the sofar vector
			if ( out != null ) {
				
				for ( i=0; i < ch; i++ ) {
					
					// float *pcm=in[i];
					
					// float *sofar=out[i];
					float[] sofar = out[i];
					
					for ( j=0, k=i; j < n; j++, k+=ch )
						sofar[j] += in[i][j+in_offset]-work[k];
				}
			}
			return ret;
		}
		else {
			return false;	
		}	
	}
	
	
	public void mapping0_forward() {

		vorbis_info           vi = vd.vi;
		codec_setup_info      ci = vi.codec_setup;
		private_state         b = vd.backend_state;
		vorbis_block_internal vbi = internal;
		int                    n = pcmend;
		int i,j,k;

		// int    *nonzero     = alloca(sizeof(*nonzero)*vi.channels);
		int[] nonzero = new int[ vi.channels ];
		
		// float  **gmdct      = _vorbis_block_alloc(vb,vi.channels*sizeof(*gmdct));
		float[][] gmdct = new float[ vi.channels ][];

		// int    **ilogmaskch = _vorbis_block_alloc(vb,vi.channels*sizeof(*ilogmaskch));
		int[][] ilogmaskch = new int[ vi.channels ][];
		
		// int ***floor_posts  = _vorbis_block_alloc(vb,vi.channels*sizeof(*floor_posts));
		int[][][] floor_posts = new int[ vi.channels ][][];
		
		float global_ampmax = vbi.ampmax;
		// float *local_ampmax = alloca(sizeof(*local_ampmax)*vi.channels);
		float[] local_ampmax = new float[ vi.channels ];
		int blocktype = vbi.blocktype;
	
		int modenumber = W;
		vorbis_info_mapping0 info = ci.map_param[modenumber];
		
		// vorbis_look_psy psy_look = b.psy+blocktype+(vb.W?2:0);
		int psy_look_offset;
		if ( W > 0 )
			psy_look_offset = blocktype+2;
		else
			psy_look_offset = blocktype;

		mode = modenumber;

		for ( i=0; i < vi.channels; i++ ) {
		
			float scale = 4.f/n;
			float scale_dB;
			
// TODO - trace where the logfft (pcm) data gets slightly thrown from C
// causes flr errors later call to accumulate_fit( logmask, logmdct, look.sorted_index[i], look.sorted_index[i+1], fits[i], n, info ); 
// sets off the lsfit_acc data when flr[mdct+i]+info.twofitatten >= flr[i]
			
			float[] logfft = pcm[i];
		
			// gmdct[i] = _vorbis_block_alloc(vb,n/2*sizeof(**gmdct));
			gmdct[i] = new float[ n/2 ];
			
			scale_dB = todB( scale ) + .345f; 
			// + .345 is a hack; the original todB estimation used on IEEE 754
			// compliant machines had a bug that returned dB values about a third
			// of a decibel too high.  The bug was harmless because tunings
			// implicitly took that into account.  However, fixing the bug
			// in the estimator requires changing all the tunings as well.
			// For now, it's easier to sync things back up here, and
			// recalibrate the tunings in the next major model upgrade.
			
			// window the PCM data

			window._vorbis_apply_window( pcm[i], b.window, ci.blocksizes, lW, W, nW );

			// transform the PCM data
			// only MDCT right now....

			b.transform[W][0].mdct_forward( pcm[i], gmdct[i] );
			
			// FFT yields more accurate tonal estimation (not phase sensitive)

			b.fft_look[W].drft_forward( pcm[i] );

			logfft[0] = scale_dB + todB(pcm[i][0]) + .345f;
			local_ampmax[i] = logfft[0];
			for ( j=1; j<n-1; j+=2 ) {
				float temp = pcm[i][j]*pcm[i][j]+pcm[i][j+1]*pcm[i][j+1];
				temp = logfft[(j+1)>>1] = scale_dB + .5f * todB(temp) + .345f;
				if ( temp > local_ampmax[i] )
					local_ampmax[i] = temp;
			}
			
			if ( local_ampmax[i] > 0.f )
				local_ampmax[i] = 0.f;
		    if ( local_ampmax[i] > global_ampmax)
		    	global_ampmax = local_ampmax[i];
		}
		
		// float   *noise        = _vorbis_block_alloc(vb,n/2*sizeof(*noise));
		float[] noise = new float[ n/2 ];
		
		// float   *tone         = _vorbis_block_alloc(vb,n/2*sizeof(*tone));
		float[] tone = new float[ n/2 ];
		
		for ( i=0; i < vi.channels; i++ ) {
		
			// the encoder setup assumes that all the modes used by any
			// specific bitrate tweaking use the same floor

			int submap = info.chmuxlist[i];
			
			// the following makes things clearer to *me* anyway

			float[] mdct    = gmdct[i];
			float[] logfft  = pcm[i];
			
			// float *logmdct = logfft+n/2;
			int logmdct = n/2;
			// float *logmask = logfft;
			
			mode = modenumber;
			
			// floor_posts[i] = _vorbis_block_alloc(vb,PACKETBLOBS*sizeof(**floor_posts));
			// memset(floor_posts[i],0,sizeof(**floor_posts)*PACKETBLOBS);
			floor_posts[i] = new int[ PACKETBLOBS ][];
			
			for ( j=0; j < n/2; j++ )
				logfft[ logmdct+j ] = todB( mdct[j] ) + .345f;
			//	logmdct[j] = todB(mdct+j) + .345f;

			// first step; noise masking.  Not only does 'noise masking'
			// give us curves from which we can decide how much resolution
			// to give noise parts of the spectrum, it also implicitly hands
			// us a tonality estimate (the larger the value in the
			// 'noise_depth' vector, the more tonal that area is)
			
			// b.psy[ psy_look_offset ]._vp_noisemask( logmdct, noise); // noise does not have by-frequency offset bias applied yet
			b.psy[ psy_look_offset ]._vp_noisemask( logfft, logmdct, noise); // noise does not have by-frequency offset bias applied yet

			// second step: 'all the other crap'; all the stuff that isn't
			// computed/fit for bitrate management goes in the second psy
			// vector.  This includes tone masking, peak limiting and ATH
			
			b.psy[ psy_look_offset ]._vp_tonemask( logfft, tone, global_ampmax, local_ampmax[i] );
				 
			// third step; we offset the noise vectors, overlay tone
			// masking.  We then do a floor1-specific line fit.  If we're
			// performing bitrate management, the line fit is performed
			// multiple times for up/down tweakage on demand.
			
			// b.psy[ psy_look_offset ]._vp_offset_and_mix( noise, tone, 1, logmask, mdct, logmdct );
			b.psy[ psy_look_offset ]._vp_offset_and_mix( noise, tone, 1, logfft, mdct, logmdct );

			// this algorithm is hardwired to floor 1 for now; abort out if
			// we're *not* floor1.  This won't happen unless someone has
			// broken the encode setup lib.  Guard it anyway.

			if ( ci.floor_type[info.floorsubmap[submap]] != 1 ) {
				System.out.println( "Error vorbis_block::mapping0_forward() - floor_type != floor1" );
				return;
			}
			
// TODO - logfft float data off
// trace of pcm before this function are dead on until here
// call to 
				
			// floor_posts[i][PACKETBLOBS/2] = floor1_fit( b.flr[info.floorsubmap[submap]], logmdct, logmask );
			floor_posts[i][PACKETBLOBS/2] = floor1_fit( b.flr[info.floorsubmap[submap]], logmdct, logfft );
			
			// are we managing bitrate?  If so, perform two more fits for later rate tweaking (fits represent hi/lo)
			if ( vorbis_bitrate_managed() && (floor_posts[i][PACKETBLOBS/2] != null ) ) {
				
				// higher rate by way of lower noise curve
				b.psy[ psy_look_offset ]._vp_offset_and_mix( noise, tone, 2, logfft, mdct, logmdct );
				
				// floor_posts[i][PACKETBLOBS-1] = floor1_fit( vb, b.flr[info.floorsubmap[submap]], logmdct, logmask );
				floor_posts[i][PACKETBLOBS-1] = floor1_fit( b.flr[info.floorsubmap[submap]], logmdct, logfft );
				
				// lower rate by way of higher noise curve
				b.psy[ psy_look_offset ]._vp_offset_and_mix( noise, tone, 0, logfft, mdct, logmdct );
					
				// floor_posts[i][0] = floor1_fit( vb, b.flr[info.floorsubmap[submap]], logmdct, logmask );
				floor_posts[i][0] = floor1_fit( b.flr[info.floorsubmap[submap]], logmdct, logfft );
					
				// we also interpolate a range of intermediate curves for intermediate rates
				
				for ( k=1; k < PACKETBLOBS/2; k++ ) {
					floor_posts[i][k] = floor1_interpolate_fit( b.flr[info.floorsubmap[submap]],
																floor_posts[i][0],
																floor_posts[i][PACKETBLOBS/2],
																k*65536/(PACKETBLOBS/2) );
				}
						
				for ( k=PACKETBLOBS/2+1; k < PACKETBLOBS-1; k++ ) {
					floor_posts[i][k] = floor1_interpolate_fit( b.flr[info.floorsubmap[submap]],
																floor_posts[i][PACKETBLOBS/2],
																floor_posts[i][PACKETBLOBS-1],
																(k-PACKETBLOBS/2)*65536/(PACKETBLOBS/2) );
				}
			}
		}

		vbi.ampmax = global_ampmax;
		
		//  the next phases are performed once for vbr-only and PACKETBLOB times for bitrate managed modes.
		//  1) encode actual mode being used
		//  2) encode the floor for each channel, compute coded mask curve/res
		//  3) normalize and couple.
		//  4) encode residue
		//  5) save packet bytes to the packetblob vector

		// iterate over the many masking curve fits we've created

		// float **res_bundle=alloca(sizeof(*res_bundle)*vi.channels);
		// float **couple_bundle=alloca(sizeof(*couple_bundle)*vi.channels);
		// int *zerobundle=alloca(sizeof(*zerobundle)*vi.channels);
		// int **sortindex=alloca(sizeof(*sortindex)*vi.channels);
		// float **mag_memo;
		// int **mag_sort;
		
		float[][] res_bundle = new float[ vi.channels ][];
		float[][] couple_bundle = new float[ vi.channels ][];
		int couple_bundle_offset = 0;
		int[] zerobundle = new int[ vi.channels ];
		int[][] sortindex = new int[ vi.channels ][];
		float[][] mag_memo = null;
		int[][] mag_sort = null;
		
		if ( info.coupling_steps > 0 ) {
		
			mag_memo = _vp_quantize_couple_memo( ci.psy_g_param, b.psy[ psy_look_offset ], info, gmdct );
			mag_sort = _vp_quantize_couple_sort( b.psy[ psy_look_offset ], info, mag_memo );
			hf_reduction( ci.psy_g_param, b.psy[ psy_look_offset ], info, mag_memo );
		}
		
		// memset( sortindex, 0, sizeof(*sortindex)*vi.channels);
		if ( b.psy[ psy_look_offset ].vi.normal_channel_p > 0 ) {
		
			for ( i=0; i < vi.channels; i++ ) {
			
				float[] mdct = gmdct[i];
				// sortindex[i] = alloca(sizeof(**sortindex)*n/2);
				sortindex[i] = new int[ n/2 ];
				_vp_noise_normalize_sort( b.psy[ psy_look_offset ], mdct, sortindex[i] );
			}
		}
		
		for ( k=(vorbis_bitrate_managed()?0:PACKETBLOBS/2); k <= (vorbis_bitrate_managed()?PACKETBLOBS-1:PACKETBLOBS/2); k++ ) {
			
			oggpack_buffer opb_local = vbi.packetblob[k];
		
			// start out our new packet blob with packet type and mode
			// Encode the packet type

			opb_local.oggpack_write( 0, 1 );

			// Encode the modenumber
			// Encode frame mode, pre,post windowsize, then dispatch

			opb_local.oggpack_write( modenumber, b.modebits );
			if ( W > 0 ) {
				opb_local.oggpack_write( lW, 1 );
				opb_local.oggpack_write( nW, 1 );
			}
			
			// encode floor, compute masking curve, sep out residue
			for ( i=0; i < vi.channels; i++ ) {
			
				int submap = info.chmuxlist[i];
				float[] mdct = gmdct[i];
				float[] res = pcm[i];

				ilogmaskch[i] = new int[ n/2 ];
				int[] ilogmask = ilogmaskch[i];
				// int   *ilogmask = ilogmaskch[i] = _vorbis_block_alloc(vb,n/2*sizeof(**gmdct));
				
				nonzero[i] = opb_local.floor1_encode( this, b.flr[info.floorsubmap[submap]], floor_posts[i][k], ilogmask );
				
				_vp_remove_floor( b.psy[ psy_look_offset ], mdct, ilogmask, res, ci.psy_g_param.sliding_lowpass[W][k] );
				
				_vp_noise_normalize( b.psy[ psy_look_offset ], res, n/2, sortindex[i] );
			}
			
			// our iteration is now based on masking curve, not prequant and coupling.  Only one prequant/coupling step

			// quantize/couple
			// incomplete implementation that assumes the tree is all depth one, or no tree at all

			if ( info.coupling_steps > 0 ) {
				_vp_couple( k, ci.psy_g_param, b.psy[ psy_look_offset ], info,
							pcm, mag_memo, mag_sort, ilogmaskch, 
							nonzero, ci.psy_g_param.sliding_lowpass[W][k] );
			}
			
			// classify and encode by submap

			for ( i=0; i < info.submaps; i++ ) {
			
				int ch_in_bundle = 0;
				int[][] classifications;
				int resnum = info.residuesubmap[i];
				
				for ( j=0; j < vi.channels; j++ ) {
				
					if ( info.chmuxlist[j] == i ) {

						zerobundle[ch_in_bundle] = 0;
						if ( nonzero[j] > 0 )
							zerobundle[ch_in_bundle] = 1;
						res_bundle[ch_in_bundle] = pcm[j];
						couple_bundle[ch_in_bundle++] = pcm[j];
						couple_bundle_offset = n/2;
					}
				}
				
				// classifications = _residue_P[ci.residue_type[resnum]].class( vb, b.residue[resnum], couple_bundle, zerobundle, ch_in_bundle );
				classifications = res2_class( b.residue[resnum], couple_bundle, couple_bundle_offset, zerobundle, ch_in_bundle );
				
				// _residue_P[ci.residue_type[resnum]].forward( vb, b.residue[resnum], couple_bundle, NULL, zerobundle, ch_in_bundle, classifications );
				res2_forward( opb_local, b.residue[resnum], couple_bundle, couple_bundle_offset, null, zerobundle, ch_in_bundle, classifications );
			}
			
			// ok, done encoding.  Mark this protopacket and prepare next.
		}
	}
	
	// finish taking in the block we just processed
	public boolean vorbis_bitrate_addblock() {
		
		vorbis_block_internal vbi = internal;
		private_state b = vd.backend_state; 
		bitrate_manager_state bm = b.bms;
		vorbis_info vi = vd.vi;
		codec_setup_info ci = vi.codec_setup;
		bitrate_manager_info bi = ci.bi;
		
		int choice = new Double( Math.rint(bm.avgfloat) ).intValue();
		int this_bits = vbi.packetblob[choice].oggpack_bytes() * 8;	// long
		
		int min_target_bits;		// long
		int max_target_bits;		// long
		int avg_target_bits;		// long
		
		if ( W > 0 ) {
			min_target_bits = bm.min_bitsper*bm.short_per_long;	
			max_target_bits = bm.max_bitsper*bm.short_per_long;
			avg_target_bits = bm.avg_bitsper*bm.short_per_long;
		}
		else {
			min_target_bits = bm.min_bitsper;
			max_target_bits = bm.max_bitsper;
			avg_target_bits = bm.avg_bitsper;
		}
		
		int  samples = ci.blocksizes[W]>>1;
		int desired_fill = new Float( bi.reservoir_bits*bi.reservoir_bias ).intValue();	// long
		
		if ( bm.managed <= 0 ) {
			
			// not a bitrate managed stream, but for API simplicity, we'll buffer the packet to keep the code path clean
			
			if ( bm.vb != null )
				return false;		// one has been submitted without being claimed 
			
			bm.vb = this;
			
			return true;	
		}
		
		bm.vb = this;
		
		// look ahead for avg floater
		if ( bm.avg_bitsper > 0 ) {
			
			float slew = 0.f;	// double
			// long avg_target_bits = (vb->W?bm.avg_bitsper*bm.short_per_long:bm.avg_bitsper);
			float slewlimit = 15.f/bi.slew_damp;	// double
			
			// choosing a new floater:
			// 		if we're over target, we slew down
			// 		if we're under target, we slew up
			
			// choose slew as follows: look through packetblobs of this frame
			// and set slew as the first in the appropriate direction that
			// gives us the slew we want.  This may mean no slew if delta is already favorable.
			// Then limit slew to slew max
			
			if ( bm.avg_reservoir+(this_bits-avg_target_bits) > desired_fill ) {
				while ( choice>0 && this_bits>avg_target_bits && bm.avg_reservoir+(this_bits-avg_target_bits)>desired_fill ) {
					choice--;
					this_bits = vbi.packetblob[choice].oggpack_bytes()*8;
					
				}
			}
			else if (bm.avg_reservoir+(this_bits-avg_target_bits)<desired_fill){
				while ( choice+1<PACKETBLOBS && this_bits<avg_target_bits && bm.avg_reservoir+(this_bits-avg_target_bits)<desired_fill ) {
					choice++;
					this_bits = vbi.packetblob[choice].oggpack_bytes()*8;
					
				}
			}
			
			slew = new Double( Math.rint(choice-bm.avgfloat)/samples*vi.rate ).floatValue();
			if ( slew < -slewlimit )
				slew = -slewlimit;
			if ( slew > slewlimit )
				slew = slewlimit;
			choice = new Float( Math.rint(bm.avgfloat+= slew/vi.rate*samples) ).intValue();
			this_bits = vbi.packetblob[choice].oggpack_bytes()*8;		
		}
		
		// enforce min(if used) on the current floater (if used)
		if ( bm.min_bitsper > 0 ) {
			// do we need to force the bitrate up?
			if ( this_bits < min_target_bits ) {
				while ( bm.minmax_reservoir-(min_target_bits-this_bits) < 0 ) {
					choice++;
					if ( choice >= PACKETBLOBS )
						break;
					this_bits = vbi.packetblob[choice].oggpack_bytes()*8;
				}
			}
		}
		
		// enforce max (if used) on the current floater (if used)
		if ( bm.max_bitsper > 0 ) {
			// do we need to force the bitrate down?
			if ( this_bits > max_target_bits ) {
				while ( bm.minmax_reservoir+(this_bits-max_target_bits) > bi.reservoir_bits ) {
					choice--;
					if ( choice < 0 )
						break;
					this_bits = vbi.packetblob[choice].oggpack_bytes()*8;
				}
			}
		}
		
		// Choice of packetblobs now made based on floater, and min/max requirements. Now boundary check extreme choices
		
		if ( choice < 0 ) {
			// choosing a smaller packetblob is insufficient to trim bitrate. frame will need to be truncated
			int maxsize = (max_target_bits+(bi.reservoir_bits-bm.minmax_reservoir))/8;	// long
			bm.choice = choice = 0;
			
			if ( vbi.packetblob[choice].oggpack_bytes() > maxsize ) {
				
				vbi.packetblob[choice].oggpack_writetrunc( maxsize*8 );
				this_bits = vbi.packetblob[choice].oggpack_bytes()*8;
			}
		}
		else {
			
			int minsize=(min_target_bits-bm.minmax_reservoir+7)/8;	// long
			if ( choice >= PACKETBLOBS )
				choice = PACKETBLOBS-1;
			
			bm.choice = choice;
			
			// prop up bitrate according to demand. pad this frame out with zeroes
			minsize -= vbi.packetblob[choice].oggpack_bytes();
			while ( minsize-- > 0 )
				vbi.packetblob[choice].oggpack_write( 0, 8 );
			this_bits = vbi.packetblob[choice].oggpack_bytes()*8;
		}
		
		// now we have the final packet and the final packet size.  Update statistics
		// min and max reservoir
		if ( bm.min_bitsper > 0 || bm.max_bitsper > 0 ) {

			if ( max_target_bits > 0 && this_bits > max_target_bits ) {
				bm.minmax_reservoir += (this_bits-max_target_bits);
			} else if ( min_target_bits > 0 && this_bits < min_target_bits ) {
				bm.minmax_reservoir += (this_bits-min_target_bits);
			} else {
				// inbetween; we want to take reservoir toward but not past desired_fill
				if ( bm.minmax_reservoir > desired_fill){
					if ( max_target_bits > 0 ) { 	// logical bulletproofing against initialization state
						bm.minmax_reservoir += (this_bits-max_target_bits);
						if ( bm.minmax_reservoir < desired_fill)
							bm.minmax_reservoir = desired_fill;
					}
					else {
						bm.minmax_reservoir = desired_fill;
					}
				}
				else {
					if ( min_target_bits > 0 ) { 	// logical bulletproofing against initialization state
						bm.minmax_reservoir+=(this_bits-min_target_bits);
						
						if ( bm.minmax_reservoir > desired_fill )
							bm.minmax_reservoir = desired_fill;
					}
					else {
						bm.minmax_reservoir=desired_fill;
					}
				}
			}
		}
		
		// avg reservoir
		if ( bm.avg_bitsper > 0 ) {
			// int avg_target_bits = (vb->W?bm->avg_bitsper*bm->short_per_long:bm->avg_bitsper);    // long
			if ( W > 0 )
				avg_target_bits = bm.avg_bitsper*bm.short_per_long;
			else
				avg_target_bits = bm.avg_bitsper;

			bm.avg_reservoir += this_bits-avg_target_bits;
		}
		return true;
	}
	
}
