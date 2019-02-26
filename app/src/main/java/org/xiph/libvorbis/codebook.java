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

public class codebook {

	int dim;				// long dim // codebook dimensions (elements per vector)
	int entries;			// long entries // codebook entries
	int used_entries;		// long used_entries // populated codebook entries
	static_codebook c;

	// for encode, the below are entry-ordered, fully populated
	// for decode, the below are ordered by bitreversed codeword and only used entries are populated

	float[] valuelist;		// list of dim*entries actual entry values
	int[] codelist;			// ogg_uint32_t *codelist // list of bitstream codewords for each entry

	int[] dec_index;		// only used if sparseness collapsed
	char[] dec_codelengths;	// chat *dec_codelengths
	int[] dec_firsttable;	// ogg_uint32_t *dec_firsttable
	int dec_firsttablen;
	int dec_maxlength;
	
	public codebook() {}

	public void vorbis_book_init_encode( static_codebook s ) {

		dim = s.dim;
		entries = s.entries;
		used_entries = s.entries;
		c = s;

		codelist = _make_words( s.lengthlist, s.entries, 0 );
		valuelist = _book_unquantize( s, s.entries, null );
	}

	private int[] _make_words( int[] l, int n, int sparsecount ) {

		int i,j,count=0;
		int[] marker;		// ogg_uint32_t marker[33];

		// ogg_uint32_t *r=_ogg_malloc((sparsecount?sparsecount:n)*sizeof(*r));
		int[] r;

		if ( sparsecount != 0 )
			r = new int[ sparsecount ];
		else
			r = new int[ n ];
  
		// memset(marker,0,sizeof(marker));
		marker = new int[ 33 ];	

		for ( i=0; i<n; i++ ) {

			int length=l[i];
			if ( length>0 ) {

				// ogg_uint32_t entry=marker[length];
				int entry = marker[length];
      
				// when we claim a node for an entry, we also claim the nodes below 
				// it (pruning off the imagined tree that may have dangled from it) 
				// as well as blocking the use of any nodes directly above for leaves
      
				// update ourself
				if ( length<32 && ((entry>>>length) != 0) ) {
					// error condition; the lengths must specify an overpopulated tree
					// _ogg_free(r);
					return null;
				}

				r[count++]=entry;
    
				// Look to see if the next shorter marker points to the node above. if so, update it and repeat

				for ( j=length; j>0; j-- ) {
	  
					if ( (marker[j]&1) != 0 ) {
						// have to jump branches
						if (j==1)
							marker[1]++;
						else
							marker[j]=marker[j-1]<<1;
						break;	// invariant says next upper marker would already
							// have been moved if it was on the same path
					}
					marker[j]++;
				}
    
      
				// prune the tree; the implicit invariant says all the longer markers were
				// dangling from our just-taken node.  Dangle them from our *new* node.

				for ( j=length+1; j<33; j++ ) {

					if ((marker[j]>>>1) == entry){
						entry=marker[j];
						marker[j]=marker[j-1]<<1;
					} else
						break;
				}
			} else
				if (sparsecount==0)
					count++;
		}
    
		// bitreverse the words because our bitwise packer/unpacker is LSb endian

		for ( i=0,count=0; i<n; i++ ) {

			// ogg_uint32_t temp=0;
			int temp = 0;
			for ( j=0; j<l[i]; j++ ) {
				temp<<=1;
				temp|=(r[count]>>>j)&1;
			}

			if (sparsecount != 0) {
				if (l[i] != 0)
					r[count++]=temp;
			} else
				r[count++]=temp;
		}

		return r;
	}

	private float ldexp( double value, long exp ) {
  
		return new Double( value * Math.pow( 2, new Long( exp ).intValue() ) ).floatValue();
	}

	private float _float32_unpack( int val ) {

		double mant=val&0x1fffff;
		int sign=val&0x80000000;
		long exp =(val&0x7fe00000L)>>>VQ_FMAN;

		if (sign != 0)
			mant= -mant;

		return ldexp( mant, exp-(VQ_FMAN-1)-VQ_FEXP_BIAS );
	}

	private float[] _book_unquantize( static_codebook b, int n, int[] sparsemap ) {

  		int j,k,count=0;

		if (b.maptype==1 || b.maptype==2) {

			int quantvals;
			float mindel = _float32_unpack(b.q_min);
			float delta = _float32_unpack(b.q_delta);
			// float *r=_ogg_calloc(n*b->dim,sizeof(*r));
			float[] r = new float[ n*b.dim ];

			// maptype 1 and 2 both use a quantized value vector, but different sizes

			switch ( b.maptype ) {

				case 1: {

					quantvals= b._book_maptype1_quantvals();
					for ( j=0; j<b.entries; j++ ) {
						if ( ( (sparsemap != null) && (b.lengthlist[j] != 0) ) || sparsemap == null ) {
							float last=0.f;
							int indexdiv=1;
							for ( k=0; k<b.dim; k++ ) {
								int index= (j/indexdiv)%quantvals;
								float val=b.quantlist[index];
								val = Math.abs(val)*delta+mindel+last;
								if (b.q_sequencep != 0)
									last=val;	  
								if (sparsemap != null)
									r[sparsemap[count]*b.dim+k]=val;
								else
									r[count*b.dim+k]=val;
								indexdiv*=quantvals;
							}
							count++;
						}

					}
					break;
				}

				case 2: {

					for ( j=0; j<b.entries; j++ ) {
						if ( ( (sparsemap != null) && (b.lengthlist[j] != 0) ) || sparsemap == null ) {
							float last=0.f;
							for ( k=0; k<b.dim; k++ ) {
								float val=b.quantlist[j*b.dim+k];
								val = Math.abs(val)*delta+mindel+last;
								if (b.q_sequencep != 0)
									last=val;	  
								if (sparsemap != null)
									r[sparsemap[count]*b.dim+k]=val;
								else
									r[count*b.dim+k]=val;
							}
							count++;
						}
					}
					break;
				}
			}

			return r;
  		}

  		return null;
	}
}