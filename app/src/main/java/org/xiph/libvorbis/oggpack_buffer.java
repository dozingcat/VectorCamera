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

import static org.xiph.libvorbis.vorbis_constants.integer_constants.*;

class oggpack_buffer {

	int endbyte;	// long
	int endbit;
	
	byte[] buffer;	// unsigned char *buffer;
	int ptr;		// unsigned char *ptr;	// offset
	int storage;	// long
	
	static int[] mask = new int[]{
        0x00000000, 
        0x00000001, 
        0x00000003, 
        0x00000007, 
        0x0000000f, 
        0x0000001f, 
        0x0000003f, 
        0x0000007f, 
        0x000000ff, 
        0x000001ff, 
        0x000003ff, 
        0x000007ff, 
        0x00000fff, 
        0x00001fff, 
        0x00003fff, 
        0x00007fff, 
        0x0000ffff, 
        0x0001ffff, 
        0x0003ffff, 
        0x0007ffff, 
        0x000fffff, 
        0x001fffff, 
        0x003fffff, 
        0x007fffff, 
        0x00ffffff, 
        0x01ffffff, 
        0x03ffffff, 
        0x07ffffff, 
        0x0fffffff, 
        0x1fffffff, 
        0x3fffffff, 
        0x7fffffff, 
        0xffffffff}
    ;

	public oggpack_buffer() {	// void oggpack_writeinit(oggpack_buffer *b)

		ptr = 0;
		buffer = new byte[ BUFFER_INCREMENT ];
		buffer[0] = (byte)'\0';
		storage = BUFFER_INCREMENT;
	}
	
	public void oggpack_reset() {
		
		ptr = 0;
		buffer[0] = (byte)'\0';
		endbit = 0;
		endbyte = 0;
	}
	
	public int oggpack_bytes() {
		return (endbyte+(endbit+7)/8);
	}
	
	public int oggpack_bits(){
		return(endbyte*8+endbit);
	}

    public void oggpack_write( int value8, int bits ) {
    	
        if ( endbyte + 4 >= storage ) {
        	storage += BUFFER_INCREMENT;
//        	 b->buffer=_ogg_realloc(b->buffer,b->storage+BUFFER_INCREMENT);
        	byte[] temp = new byte[ storage ];
    		System.arraycopy( buffer, 0, temp, 0, buffer.length );
    		buffer = temp;
            ptr = endbyte;
        }
        value8 &= mask[ bits ];
        bits += endbit;
        buffer[ ptr ] |= value8 << endbit;
        if ( bits >= 8 ) {
        	buffer[ ptr+1 ] = (byte)(value8 >>> (8 - endbit));
            if ( bits >= 16 ) {
            	buffer[ ptr+2 ] = (byte)(value8 >>> (16 - endbit));
                if ( bits >= 24 ) {
                	buffer[ ptr+3 ] = (byte)(value8 >>> (24 - endbit));
                    if ( bits >= 32 ) {
                        if ( endbit != 0 ) {
                        	buffer[ ptr+4 ] = (byte)(value8 >>> (32 - endbit));
                        }
                        else {
                        	buffer[ ptr+4 ] = 0;
                        }
                    }
                }
            }
        }
        endbyte += bits / 8;
        ptr += bits / 8;
        endbit = bits & 7;
    }
    
    public void oggpack_writetrunc( int bits ) {
    	
    	int bytes = bits>>3;
    	bits -= bytes*8;
    	// b->ptr=b->buffer+bytes;
    	ptr = bytes;
    	endbit = bits;
    	endbyte = bytes;
    	// *b->ptr&=mask[bits];
    	buffer[ptr] = (byte)mask[bits];
    }
	
	public void _v_writestring ( String s, int bytes ) {
		
		for ( int i=0; i < bytes; i++ ) {
			oggpack_write( s.charAt( i ), 8 );
		}
	}
	
	public void _v_writestring ( byte[] s, int bytes ) {
		
		for ( int i=0; i < bytes; i++ ) {
			oggpack_write( s[i], 8 );
		}
	}
	
	public boolean _vorbis_pack_info( vorbis_info vi ) {
		
		codec_setup_info ci = vi.codec_setup;
		
		if ( ci == null )
			return false;
		
		// preamble
		oggpack_write( 0x01, 8 );
		_v_writestring( "vorbis", 6 );
		
		// basic information about the stream
		oggpack_write( 0x00, 32 );
		oggpack_write( vi.channels, 8 );
		oggpack_write( vi.rate, 32 );
		
		oggpack_write( vi.bitrate_upper, 32 );
		oggpack_write( vi.bitrate_nominal, 32 );
		oggpack_write( vi.bitrate_lower, 32 );

		oggpack_write( ilog2( ci.blocksizes[0] ), 4 );
		oggpack_write( ilog2( ci.blocksizes[1] ), 4 );
		oggpack_write( 1, 1 );
		
		return true;
	}
	
	public boolean _vorbis_pack_comment( vorbis_comment vc ) {	
		
		String temp = "Xiph.Org libVorbis I 20030909";
		int bytes = temp.length();

		// preamble
		oggpack_write( 0x03, 8 );
		_v_writestring( "vorbis", 6 );

		// vendor
		oggpack_write( bytes, 32 );
		_v_writestring( temp, bytes );
				
		// comments		
		oggpack_write( vc.comments, 32 );
		if ( vc.comments > 0 ) {
			for ( int i=0; i < vc.comments; i++ ) {
				if ( vc.user_comments[i] != null ) {
					oggpack_write( vc.comment_lengths[i], 32 );
					_v_writestring( vc.user_comments[i], vc.comment_lengths[i] );
				} else {
					oggpack_write( 0, 32 );
				}
			}
		}
		
		oggpack_write( 1, 1 );
		
		return true;
	}

	public boolean _vorbis_pack_books( vorbis_info vi ) {
		
		codec_setup_info ci = vi.codec_setup;
		int i;
		
		if ( ci == null )
			return false;
		
		// preamble
		oggpack_write( 0x05, 8 );
		_v_writestring( "vorbis", 6 );
		
		// books
		oggpack_write( ci.books-1, 8 );
		for ( i=0; i < ci.books; i++ ) {
			if ( !vorbis_staticbook_pack( ci.book_param[i] ) )
				return false;
		}
		
		// times; hook placeholders
		oggpack_write( 0, 6 );
		oggpack_write( 0, 16 );
		
		// floors
		oggpack_write( ci.floors-1, 6 );
		for ( i=0; i <ci.floors; i++ ) {
			oggpack_write( ci.floor_type[i], 16 );
			// _floor_P[ci->floor_type[i]]->pack(ci->floor_param[i],opb);
			floor1_pack( ci.floor_param[i] );
		}
		
		// residues 
		oggpack_write( ci.residues-1, 6 );
		for ( i=0; i < ci.residues; i++ ) {
			oggpack_write( ci.residue_type[i], 16 );
			// _residue_P[ci->residue_type[i]]->pack(ci->residue_param[i],opb);
			res0_pack( ci.residue_param[i] );
		}
		
		// maps
		oggpack_write( ci.maps-1, 6 );
		for ( i=0; i < ci.maps; i++ ) {
		    oggpack_write( ci.map_type[i], 16 );
		    // _mapping_P[ci->map_type[i]]->pack(vi,ci->map_param[i],opb);
		    mapping0_pack( vi, ci.map_param[i] );
		}
		
		// modes
		oggpack_write( ci.modes-1, 6 );
		for ( i=0; i < ci.modes; i++ ) {
			oggpack_write( ci.mode_param[i].blockflag, 1 );
			oggpack_write( ci.mode_param[i].windowtype, 16 );
			oggpack_write( ci.mode_param[i].transformtype, 16 );
			oggpack_write( ci.mode_param[i].mapping, 8 );
		}
		
		oggpack_write( 1, 1 );
		
		return true;
	}
	
	public boolean vorbis_staticbook_pack( static_codebook c ) {
		
		int i,j;
		int ordered = 0;
		
		// first the basic parameters
		oggpack_write( 0x564342, 24 );
		oggpack_write( c.dim, 16 );
		oggpack_write( c.entries, 24 );
		
		// pack the codewords.  There are two packings; length ordered and length random.  Decide between the two now.
		for ( i=1; i < c.entries; i++ )
		    if ( c.lengthlist[i-1] == 0 || c.lengthlist[i] < c.lengthlist[i-1] )
		    	break;
		if ( i == c.entries)
			ordered = 1;
		
		if ( ordered != 0 ) {
			// length ordered.  We only need to say how many codewords of each length.
			// The actual codewords are generated deterministically
			
			int count=0;
		    oggpack_write( 1, 1 );  // ordered
		    oggpack_write( c.lengthlist[0]-1, 5 ); // 1 to 32
		    
		    for ( i=1; i < c.entries; i++ ) {
		    	int current = c.lengthlist[i];
		    	int last = c.lengthlist[i-1];
		    	if ( current > last ) {
		    		for ( j=last; j < current; j++ ) {
		    			oggpack_write( i-count, ilog( c.entries-count ) );
		    			count=i;
		    		}
		    	}
		    }
		    
		    oggpack_write( i-count, ilog( c.entries-count ) );
		    
		} else {
			// length random.  Again, we don't code the codeword itself, just the length.
			// This time, though, we have to encode each length
			
			oggpack_write( 0, 1 );   // unordered
		    
		    // algortihmic mapping has use for 'unused entries', which we tag here.  
		    // The algorithmic mapping happens as usual, but the unused entry has no codeword.
			
			for ( i=0; i < c.entries; i++ )
				if ( c.lengthlist[i] == 0 )
					break;
			if ( i == c.entries ) {
				oggpack_write( 0, 1 ); // no unused entries
				for ( i=0; i < c.entries; i++ )
					oggpack_write( c.lengthlist[i]-1, 5 );
			} else {
				oggpack_write( 1, 1 ); // we have unused entries; thus we tag
				for ( i=0; i < c.entries; i++ ) {
					if ( c.lengthlist[i] == 0 ) {
						oggpack_write( 0, 1 );
					} else {
						oggpack_write( 1, 1 );
						oggpack_write( c.lengthlist[i]-1, 5 );
					}
				}
			}
		}
		
		// is the entry number the desired return value, or do we have a mapping? If we have a mapping, what type?
		oggpack_write( c.maptype, 4 );
		
		switch ( c.maptype ) {
		
			case 0:
				// no mapping
			break;
			
			case 1:
			case 2:
				// implicitly populated value mapping
				// explicitly populated value mapping
				if ( c.quantlist == null ) {
					// no quantlist?  error
					System.out.println( "no quantlist exists");
					return false;
				}
				
				// values that define the dequantization
				oggpack_write( c.q_min, 32 );
				oggpack_write( c.q_delta, 32 );
				oggpack_write( c.q_quant-1, 4 );
				oggpack_write( c.q_sequencep, 1 );
				
				{
					int quantvals;
					switch ( c.maptype ) {
					
						case 1:
							// a single column of (c->entries/c->dim) quantized values for
							// building a full value list algorithmically (square lattice)
							quantvals = c._book_maptype1_quantvals();
						break;
						
						case 2:
							// every value (c->entries*c->dim total) specified explicitly
							quantvals = c.entries * c.dim;
						break;
						
						default: // NOT_REACHABLE
							quantvals = -1;
					}
					
					// quantized values
					for ( i=0; i < quantvals; i++ )
						oggpack_write( Math.abs( c.quantlist[i] ), c.q_quant );
				}
			break;
			
			default:
				// error case; we don't have any other map types now
				System.out.println( "error case; we don't have any other map types now" );
				return false;
		}
		
		return true;
	}
	
	public int vorbis_book_encode( codebook book, int a ) {
		
		oggpack_write( book.codelist[a], book.c.lengthlist[a] );
		return book.c.lengthlist[a];
		
	}
	
	public void floor1_pack( vorbis_info_floor1 info ) {
		
		int j,k;
		int count=0;
		int rangebits;
		int maxposit = info.postlist[1];
		int maxclass = -1;
		
		// save out partitions
		oggpack_write( info.partitions, 5 ); // only 0 to 31 legal
		for ( j=0; j < info.partitions; j++ ) {
			
			oggpack_write( info.partitionclass[j], 4 ); // only 0 to 15 legal
			if ( maxclass < info.partitionclass[j] )
				maxclass = info.partitionclass[j];
		}
		
		// save out partition classes
		for ( j=0; j < maxclass+1; j++ ) {
			
			oggpack_write( info.class_dim[j]-1, 3 ); // 1 to 8
			oggpack_write( info.class_subs[j], 2 ); // 0 to 3
			if ( info.class_subs[j] > 0 )
				oggpack_write( info.class_book[j], 8 );
			
			for ( k=0; k < (1<<info.class_subs[j]); k++ )
				oggpack_write( info.class_subbook[j][k]+1, 8 );
		}
		
		// save out the post list
		oggpack_write( info.mult - 1, 2 );     // only 1,2,3,4 legal now
		oggpack_write( ilog2( maxposit ), 4 );
		rangebits = ilog2( maxposit );
		
		for ( j=0,k=0; j < info.partitions; j++ ) {
			
			count += info.class_dim[info.partitionclass[j]]; 
			
			for ( ; k<count; k++)
		      oggpack_write( info.postlist[k+2], rangebits );
		}
	}
	
	static int seq = 0; 
	
	public int floor1_encode( vorbis_block vb, vorbis_look_floor1 look, int[] post, int[] ilogmask ) {
		
		int i,j;
		vorbis_info_floor1 info = look.vi;
		// int n = look.n;
		int posts = look.posts;
		codec_setup_info ci = vb.vd.vi.codec_setup;
		int[] out = new int[VIF_POSIT+2];
		static_codebook[] sbooks = ci.book_param;
		codebook[] books = ci.fullbooks;
		
		// quantize values to multiplier spec
		if ( post != null ) {
			
			for ( i=0; i < posts; i++ ) {
				
				int val = post[i]&0x7fff;
				
				switch ( info.mult ) {
				
					case 1: /* 1024 -> 256 */
						val>>=2;
					break;
					
					case 2: /* 1024 -> 128 */
						val>>=3;
					break;

					case 3: /* 1024 -> 86 */
						val/=12;
					break;
					
					case 4: /* 1024 -> 64 */
						val>>=4;
					break;
				}
				post[i] = val | (post[i]&0x8000);
			}
			
			out[0] = post[0];
			out[1] = post[1];
			
			// find prediction values for each post and subtract them
			for ( i=2; i < posts; i++ ) {
				
				int ln = look.loneighbor[i-2];
				int hn = look.hineighbor[i-2];
				int x0 = info.postlist[ln];
				int x1 = info.postlist[hn];
				int y0 = post[ln];
				int y1 = post[hn];
				
				int predicted = render_point( x0, x1, y0, y1, info.postlist[i] );
				
				if ( ( (post[i]&0x8000) > 0 ) || (predicted==post[i]) ) {
					
					post[i] = predicted|0x8000; // in case there was roundoff jitter in interpolation
					out[i] = 0;
					
				} else {
					
					int headroom = (look.quant_q-predicted < predicted?look.quant_q-predicted:predicted);
					
					int val = post[i]-predicted;
					
					// at this point the 'deviation' value is in the range +/- max
					// range, but the real, unique range can always be mapped to
					// only [0-maxrange).  So we want to wrap the deviation into
					// this limited range, but do it in the way that least screws
					// an essentially gaussian probability distribution.
					
					if ( val < 0)
						if( val < -headroom )
							val = headroom-val-1;
						else
							val = -1-(val<<1);
					else
						if ( val >= headroom)
							val = val+headroom;
						else
							val<<=1;
					
					out[i]=val;
					post[ln]&=0x7fff;
					post[hn]&=0x7fff;
				}
			}
			
			// we have everything we need. pack it out
			// mark nontrivial floor
			oggpack_write( 1, 1 );
			
			// beginning/end post
			look.frames++;
			look.postbits += ilog(look.quant_q-1)*2;
			oggpack_write( out[0], ilog(look.quant_q-1) );
			oggpack_write( out[1], ilog(look.quant_q-1) );
			
			// partition by partition
			for ( i=0,j=2; i < info.partitions; i++ ) {
				
				int class_local = info.partitionclass[i];
				int cdim = info.class_dim[class_local];
				int csubbits = info.class_subs[class_local];
				int csub = 1<<csubbits;
				// int bookas[8]={0,0,0,0,0,0,0,0};
				int[] bookas = new int [ 8 ];
				int cval=0;
				int cshift=0;
				int k,l;
				
				// generate the partition's first stage cascade value
				if ( csubbits > 0 ) {
					
					// int maxval[8];
					int[] maxval = new int[ 8 ];
					
					for ( k=0; k < csub; k++ ) {
						
						int booknum = info.class_subbook[class_local][k];
						if ( booknum < 0 ){
							maxval[k] = 1;
						}else{
							maxval[k] = sbooks[info.class_subbook[class_local][k]].entries;
						}
					}
					
					for ( k=0; k < cdim; k++ ) {
						for ( l=0; l < csub; l++ ) {
							int val = out[j+k];
							if ( val < maxval[l] ) {
								bookas[k] = l;
								break;
							}
						}
						cval|= bookas[k]<<cshift;
						cshift+=csubbits;
					}
					// write it
					look.phrasebits += vorbis_book_encode( books[info.class_book[class_local]], cval );
				}
				
				// write post values 
				for ( k=0; k < cdim; k++ ) {
					
					int book = info.class_subbook[class_local][bookas[k]];
					if ( book >= 0 ) {
						// hack to allow training with 'bad' books
						if ( out[j+k] < books[book].entries )
							look.postbits += vorbis_book_encode( books[book], out[j+k] );
						/*else
						 * fprintf(stderr,"+!");*/
					}
				}
				j+=cdim;
			}
			
			// generate quantized floor equivalent to what we'd unpack in decode
			// render the lines
			int hx=0;
			int lx=0;
			int ly=post[0]*info.mult;
			
			for ( j=1; j < look.posts; j++ ) {
				
				int current = look.forward_index[j];
				int hy = post[current]&0x7fff;
				
				if ( hy == post[current] ) {
					
					hy *= info.mult;
					hx = info.postlist[current];
					
					render_line0(lx,hx,ly,hy,ilogmask);
					
					lx=hx;
					ly=hy;
				}
			}
			
			for ( j=hx; j < vb.pcmend/2; j++ )
				ilogmask[j] = ly; // be certain
			seq++;
			return(1);
			
		} else {
			
			oggpack_write( 0, 1 );
			// memset(ilogmask,0,vb.pcmend/2*sizeof(*ilogmask));
			// for ( i=0; i < vb.pcmend/2; i++ )
			//	ilogmask[i] = 0;
			Arrays.fill( ilogmask, 0, vb.pcmend/2, 0 );
			seq++;
			return(0);
		}
	}
	
	public void res0_pack( vorbis_info_residue0 info ) {
		
		int j,acc=0;
		oggpack_write( info.begin, 24 );
		oggpack_write( info.end, 24 );
		
		oggpack_write( info.grouping-1, 24 );  // residue vectors to group and code with a partitioned book
		
		oggpack_write( info.partitions-1, 6 ); // possible partition choices
		oggpack_write( info.groupbook, 8 );  // group huffman book
		
		// secondstages is a bitmask; as encoding progresses pass by pass, a bitmask
		// of one indicates this partition class has bits to write this pass
		
		for ( j=0; j < info.partitions; j++ ) {
			
			if ( ilog( info.secondstages[j] ) > 3 ) {
				// yes, this is a minor hack due to not thinking ahead
				oggpack_write( info.secondstages[j], 3 ); 
				oggpack_write( 1, 1 );
				oggpack_write( info.secondstages[j]>>3, 5 ); 
			} else {
				oggpack_write( info.secondstages[j], 4 ); // trailing zero
			}
			
		    acc += icount( info.secondstages[j] );
		}
		
		for ( j=0; j < acc; j++ )
			oggpack_write( info.booklist[j], 8 );
	}
	
	public void mapping0_pack( vorbis_info vi, vorbis_info_mapping0 info ) {
		
		int i;
		
		/* another 'we meant to do it this way' hack...  up to beta 4, we
		 * packed 4 binary zeros here to signify one submapping in use.  We
		 * now redefine that to mean four bitflags that indicate use of
		 * deeper features; bit0:submappings, bit1:coupling,
		 * bit2,3:reserved. This is backward compatable with all actual uses
		 * of the beta code. */
		
		if ( info.submaps > 1 ) {
			oggpack_write( 1, 1 );
			oggpack_write( info.submaps-1, 4 );
		} else 
			oggpack_write( 0, 1 );
		
		if ( info.coupling_steps > 0 ) {
			oggpack_write( 1, 1 );
			oggpack_write( info.coupling_steps-1, 8 );
			
			for ( i=0; i < info.coupling_steps; i++ ) {
				oggpack_write( info.coupling_mag[i], ilog2( vi.channels ) );
				oggpack_write( info.coupling_ang[i], ilog2( vi.channels ) );
			}
		} else
			oggpack_write( 0, 1 );
		
		oggpack_write( 0, 2 ); // 2,3:reserved
		
		// we don't write the channel submappings if we only have one...
		if ( info.submaps > 1 ) {
			for ( i=0; i < vi.channels; i++ )
				oggpack_write( info.chmuxlist[i],4 );
		}
		
		for ( i=0; i < info.submaps; i++ ) {
			oggpack_write( 0,8 ); // time submap unused
			oggpack_write( info.floorsubmap[i], 8 );
			oggpack_write( info.residuesubmap[i], 8 );
		}
	}
}