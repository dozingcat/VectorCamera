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

class mdct_lookup {

	int n;
	int log2n;
  
	float[] trig;
	int[] bitrev;

	float scale;


	public mdct_lookup() {}

	public mdct_lookup( int _n, int _log2n, float[] _trig, int[] _bitrev, float _scale ) {

		n = _n;
		log2n = _log2n;

		trig = (float[])_trig.clone();
		bitrev = (int[])_bitrev.clone();

		scale = _scale;
	}

	public mdct_lookup( mdct_lookup src ) {

		this( src.n, src.log2n, src.trig, src.bitrev, src.scale );
	}

	public void mdct_init( int _n ) {

		n = _n;
		log2n = new Double( Math.rint( Math.log( new Integer( n ).floatValue() ) / Math.log( 2.f ) ) ).intValue();

		// DATA_TYPE *T=_ogg_malloc(sizeof(*T)*(n+n/4));
		trig = new float[ n+n/4 ];

		// int   *bitrev=_ogg_malloc(sizeof(*bitrev)*(n/4));
		bitrev = new int[ n/4 ];

		int i, j;
		int n2 = n >> 1;

		// trig lookups...
		for ( i=0; i < n/4; i++ ) {
			trig[i*2] = new Double( Math.cos( (M_PI/n)*(4*i) ) ).floatValue();
			trig[i*2+1] = new Double( -Math.sin( (M_PI/n)*(4*i) ) ).floatValue();
			trig[n2+i*2] = new Double( Math.cos( (M_PI/(2*n))*(2*i+1) ) ).floatValue();
			trig[n2+i*2+1] = new Double( Math.sin( (M_PI/(2*n))*(2*i+1) ) ).floatValue();
		}

		for ( i=0; i < n/8; i++ ) {
			trig[n+i*2] = new Double( Math.cos( (M_PI/n)*(4*i+2) )*.5 ).floatValue();
			trig[n+i*2+1] = new Double( -Math.sin( (M_PI/n)*(4*i+2) )*.5 ).floatValue();
		}

		// bitreverse lookup...
		{
			int mask = (1<<(log2n-1))-1;
			int msb = 1<<(log2n-2);
			for ( i=0; i < n/8; i++ ) {
				int acc=0;
				for ( j=0; (msb>>j) != 0; j++)
					if ( ((msb>>j)&i) != 0 )
						acc|=1<<j;
				bitrev[i*2]=((~acc)&mask)-1;
				bitrev[i*2+1]=acc;

			}
		}
		scale = 4.f/n;
	}

	// 8 point butterfly (in place, 4 register)
	private void mdct_butterfly_8( float[] x, int off) {

		float r0   = x[off+6] + x[off+2];
		float r1   = x[off+6] - x[off+2];
		float r2   = x[off+4] + x[off+0];
		float r3   = x[off+4] - x[off+0];

		x[off+6] = r0   + r2;
		x[off+4] = r0   - r2;

		r0   = x[off+5] - x[off+1];
		r2   = x[off+7] - x[off+3];
		x[off+0] = r1   + r0;
		x[off+2] = r1   - r0;

		r0   = x[off+5] + x[off+1];
		r1   = x[off+7] + x[off+3];
		x[off+3] = r2   + r3;
		x[off+1] = r2   - r3;
		x[off+7] = r1   + r0;
		x[off+5] = r1   - r0;
	}

	// 16 point butterfly (in place, 4 register)
	private void mdct_butterfly_16( float[] x, int off ) {

		float r0     = x[off+1]  - x[off+9];
		float r1     = x[off+0]  - x[off+8];

		x[off+8]  += x[off+0];
		x[off+9]  += x[off+1];
		x[off+0]   = ((r0   + r1) * cPI2_8);
		x[off+1]   = ((r0   - r1) * cPI2_8);

		r0     = x[off+3]  - x[off+11];
		r1     = x[off+10] - x[off+2];
		x[off+10] += x[off+2];
		x[off+11] += x[off+3];
		x[off+2]   = r0;
		x[off+3]   = r1;

		r0     = x[off+12] - x[off+4];
		r1     = x[off+13] - x[off+5];
		x[off+12] += x[off+4];
		x[off+13] += x[off+5];
		x[off+4]   = ((r0   - r1) * cPI2_8);
		x[off+5]   = ((r0   + r1) * cPI2_8);

		r0     = x[off+14] - x[off+6];
		r1     = x[off+15] - x[off+7];
		x[off+14] += x[off+6];
		x[off+15] += x[off+7];
		x[off+6]  = r0;
		x[off+7]  = r1;

		mdct_butterfly_8( x, 0 + off );
		mdct_butterfly_8( x, 8 + off );
	}

	// 32 point butterfly (in place, 4 register)
	private void mdct_butterfly_32( float[] x, int off ) {

		float r0     = x[off+30] - x[off+14];
		float r1     = x[off+31] - x[off+15];

		x[off+30] +=         x[off+14];           
		x[off+31] +=         x[off+15];
		x[off+14]  =         r0;              
		x[off+15]  =         r1;

		r0     = x[off+28] - x[off+12];   
		r1     = x[off+29] - x[off+13];
		x[off+28] +=         x[off+12];           
		x[off+29] +=         x[off+13];
		x[off+12]  = ( r0 * cPI1_8  -  r1 * cPI3_8 );
		x[off+13]  = ( r0 * cPI3_8  +  r1 * cPI1_8 );

		r0     = x[off+26] - x[off+10];
		r1     = x[off+27] - x[off+11];
		x[off+26] +=         x[off+10];
		x[off+27] +=         x[off+11];
		x[off+10]  = (( r0  - r1 ) * cPI2_8);
		x[off+11]  = (( r0  + r1 ) * cPI2_8);

		r0     = x[off+24] - x[off+8];
		r1     = x[off+25] - x[off+9];
		x[off+24] += x[off+8];
		x[off+25] += x[off+9];
		x[off+8]   = ( r0 * cPI3_8  -  r1 * cPI1_8 );
		x[off+9]   = ( r1 * cPI3_8  +  r0 * cPI1_8 );

		r0     = x[off+22] - x[off+6];
		r1     = x[off+7]  - x[off+23];
		x[off+22] += x[off+6];
		x[off+23] += x[off+7];
		x[off+6]   = r1;
		x[off+7]   = r0;

		r0     = x[off+4]  - x[off+20];
		r1     = x[off+5]  - x[off+21];
		x[off+20] += x[off+4];
		x[off+21] += x[off+5];
		x[off+4]   = ( r1 * cPI1_8  +  r0 * cPI3_8 );
		x[off+5]   = ( r1 * cPI3_8  -  r0 * cPI1_8 );

		r0     = x[off+2]  - x[off+18];
		r1     = x[off+3]  - x[off+19];
		x[off+18] += x[off+2];
		x[off+19] += x[off+3];
		x[off+2]   = (( r1  + r0 ) * cPI2_8);
		x[off+3]   = (( r1  - r0 ) * cPI2_8);

		r0     = x[off+0]  - x[off+16];
		r1     = x[off+1]  - x[off+17];
		x[off+16] += x[off+0];
		x[off+17] += x[off+1];
		x[off+0]   = ( r1 * cPI3_8  +  r0 * cPI1_8 );
		x[off+1]   = ( r1 * cPI1_8  -  r0 * cPI3_8 );

		mdct_butterfly_16( x, 0 + off );
		mdct_butterfly_16( x, 16 + off );
	}

	// N point first stage butterfly (in place, 2 register)
	private void mdct_butterfly_first( float[] T, float[] x, int off, int points ) {

		// float *x1        = x          + points      - 8;
		int x1 = off + points      - 8;

		// float *x2        = x          + (points>>1) - 8;
		int x2 = off + (points>>1) - 8;

		int t = 0;

		float   r0;
		float   r1;

		do {

			r0      = x[x1+6]      -  x[x2+6];
			r1      = x[x1+7]      -  x[x2+7];
			x[x1+6]  += x[x2+6];
			x[x1+7]  += x[x2+7];
			x[x2+6]   = (r1 * T[t+1]  +  r0 * T[t+0]);
			x[x2+7]   = (r1 * T[t+0]  -  r0 * T[t+1]);

			r0      = x[x1+4]      -  x[x2+4];
			r1      = x[x1+5]      -  x[x2+5];
			x[x1+4]  += x[x2+4];
			x[x1+5]  += x[x2+5];
			x[x2+4]   = (r1 * T[t+5]  +  r0 * T[t+4]);
			x[x2+5]   = (r1 * T[t+4]  -  r0 * T[t+5]);

			r0      = x[x1+2]      -  x[x2+2];
			r1      = x[x1+3]      -  x[x2+3];
			x[x1+2]  += x[x2+2];
			x[x1+3]  += x[x2+3];
			x[x2+2]   = (r1 * T[t+9]  +  r0 * T[t+8]);
			x[x2+3]   = (r1 * T[t+8]  -  r0 * T[t+9]);

			r0      = x[x1+0]      -  x[x2+0];
			r1      = x[x1+1]      -  x[x2+1];
			x[x1+0]  += x[x2+0];
			x[x1+1]  += x[x2+1];
			x[x2+0]   = (r1 * T[t+13] +  r0 * T[t+12]);
			x[x2+1]   = (r1 * T[t+12] -  r0 * T[t+13]);

			x1 -= 8;
			x2 -= 8;
			t += 16;

		} while ( x2 >= off );
		// } while ( x2 >= x );
	}

	// N/stage point generic N stage butterfly (in place, 2 register)
	private void mdct_butterfly_generic( float[] T, float[] x, int off, int points, int trigint ) {

		// float *x1        = x          + points      - 8;
		int x1 = off + points      - 8;

		// float *x2        = x          + (points>>1) - 8;
		int x2 = off + (points>>1) - 8;

		int t = 0;

		float   r0;
		float   r1;

		do {

			r0      = x[x1+6]      -  x[x2+6];
			r1      = x[x1+7]      -  x[x2+7];
			x[x1+6]  += x[x2+6];
			x[x1+7]  += x[x2+7];
			x[x2+6]   = (r1 * T[t+1]  +  r0 * T[t+0]);
			x[x2+7]   = (r1 * T[t+0]  -  r0 * T[t+1]);

			t += trigint;

			r0      = x[x1+4]      -  x[x2+4];
			r1      = x[x1+5]      -  x[x2+5];
			x[x1+4]  += x[x2+4];
			x[x1+5]  += x[x2+5];
			x[x2+4]   = (r1 * T[t+1]  +  r0 * T[t+0]);
			x[x2+5]   = (r1 * T[t+0]  -  r0 * T[t+1]);

			t += trigint;

			r0      = x[x1+2]      -  x[x2+2];
			r1      = x[x1+3]      -  x[x2+3];
			x[x1+2]  += x[x2+2];
			x[x1+3]  += x[x2+3];
			x[x2+2]   = (r1 * T[t+1]  +  r0 * T[t+0]);
			x[x2+3]   = (r1 * T[t+0]  -  r0 * T[t+1]);

			t += trigint;

			r0      = x[x1+0]      -  x[x2+0];
			r1      = x[x1+1]      -  x[x2+1];
			x[x1+0]  += x[x2+0];
			x[x1+1]  += x[x2+1];
			x[x2+0]   = (r1 * T[t+1]  +  r0 * T[t+0]);
			x[x2+1]   = (r1 * T[t+0]  -  r0 * T[t+1]);

			t += trigint;
			x1 -= 8;
			x2 -= 8;

		} while ( x2 >= off );
		// } while ( x2 >= x );
	}

	private void mdct_butterflies( float[] x, int off, int points ) {

		float[] T = trig;
		int stages = log2n-5;
		int i,j;

		if ( --stages > 0 ) {
			mdct_butterfly_first( T, x, off, points );
		}

		for ( i=1; --stages > 0; i++ ) {
			for ( j=0; j < (1<<i); j++ )
				mdct_butterfly_generic( T, x, off+((points>>i)*j), points>>i, 4<<i );
		}

		for ( j=0; j < points; j+=32 )
			mdct_butterfly_32( x, off+j );
	}

	private void mdct_bitreverse( float[] x ) {

		// float *w0      = x;
		// float *w1      = x = w0+(n>>1);
		// float *T       = init->trig+n;

		int bit = 0;
		int w0 = 0;
		int w1 =  (n>>1);
		int xoff = (n>>1);

		int t = n;
		float[] T = trig;

		do{
			// float *x0    = x+bit[0];
			// float *x1    = x+bit[1];
			int x0 = xoff + bitrev[ bit+0 ];
			int x1 = xoff + bitrev[ bit+1 ];

			float  r0     = x[x0+1]  - x[x1+1];
			float  r1     = x[x0+0]  + x[x1+0];
			float  r2     = (r1     * T[t+0]   + r0 * T[t+1]);
			float  r3     = (r1     * T[t+1]   - r0 * T[t+0]);

			w1    -= 4;

			r0     = (x[x0+1] + x[x1+1]) * .5f;
			r1     = (x[x0+0] - x[x1+0]) * .5f;

			x[w0+0]  = r0     + r2;
			x[w1+2]  = r0     - r2;
			x[w0+1]  = r1     + r3;
			x[w1+3]  = r3     - r1;

			x0 = xoff + bitrev[ bit+2 ];
			x1 = xoff + bitrev[ bit+3 ];

			r0     = x[x0+1]  - x[x1+1];
			r1     = x[x0+0]  + x[x1+0];
			r2     = (r1     * T[t+2]   + r0 * T[t+3]);
			r3     = (r1     * T[t+3]   - r0 * T[t+2]);

			r0     = (x[x0+1] + x[x1+1]) * .5f;
			r1     = (x[x0+0] - x[x1+0]) * .5f;
      
			x[w0+2]  = r0     + r2;
			x[w1+0]  = r0     - r2;
			x[w0+3]  = r1     + r3;
			x[w1+1]  = r3     - r1;

			t += 4;
			bit += 4;
			w0 += 4;

		} while ( w0 < w1 );
	}

	public void mdct_backward( float[] in, float[] out ) {

		int n2=n>>1;
		int n4=n>>2;

		// rotate

		// float *iX = in+n2-7;
		// float *oX = out+n2+n4;

		float[] T  = trig;
		int iX = n2-7;
		int oX = n2+n4;
		int t = n4;

		do {
			oX         -= 4;
			out[oX+0]       = (-in[iX+2] * T[t+3] - in[iX+0]  * T[t+2]);
			out[oX+1]       =  (in[iX+0] * T[t+3] - in[iX+2]  * T[t+2]);
			out[oX+2]       = (-in[iX+6] * T[t+1] - in[iX+4]  * T[t+0]);
			out[oX+3]       =  (in[iX+4] * T[t+1] - in[iX+6]  * T[t+0]);
			iX         -= 8;
			t          += 4;
		} while( iX >= 0 );

		// float *iX = in+n2-8;
		// float *oX = out+n2+n4;

		iX = n2-8;
		oX = n2+n4;
		t = n4;

		do {
			t          -= 4;
			out[oX+0]       =   (in[iX+4] * T[t+3] + in[iX+6] * T[t+2]);
			out[oX+1]       =   (in[iX+4] * T[t+2] - in[iX+6] * T[t+3]);
			out[oX+2]       =   (in[iX+0] * T[t+1] + in[iX+2] * T[t+0]);
			out[oX+3]       =   (in[iX+0] * T[t+0] - in[iX+2] * T[t+1]);
			iX         -= 8;
			oX         += 4;
		} while( iX >= 0 );

		mdct_butterflies(out, n2, n2);
		mdct_bitreverse(out);

		// roatate + window

		// float *oX1=out+n2+n4;
		// float *oX2=out+n2+n4;
		// float *iX =out;
		// T =init->trig+n2;

		int oX1 = n2+n4;
		int oX2 = n2+n4;
		iX = 0;
		t = n2;
    
		do {
			oX1-=4;

			out[oX1+3]  =   (out[iX+0] * T[t+1] - out[iX+1] * T[t+0]);
			out[oX2+0]  = - (out[iX+0] * T[t+0] + out[iX+1] * T[t+1]);

			out[oX1+2]  =   (out[iX+2] * T[t+3] - out[iX+3] * T[t+2]);
			out[oX2+1]  = - (out[iX+2] * T[t+2] + out[iX+3] * T[t+3]);

			out[oX1+1]  =   (out[iX+4] * T[t+5] - out[iX+5] * T[t+4]);
			out[oX2+2]  = - (out[iX+4] * T[t+4] + out[iX+5] * T[t+5]);

			out[oX1+0]  =   (out[iX+6] * T[t+7] - out[iX+7] * T[t+6]);
			out[oX2+3]  = - (out[iX+6] * T[t+6] + out[iX+7] * T[t+7]);

			oX2 += 4;
			iX += 8;
			t += 8;

		} while( iX < oX1 );

		// float *oX1=out+n4;
		// float *oX2=oX1;
		// float *iX =out+n2+n4;

		iX = n2+n4;
		oX1 = n4;
		oX2 = oX1;

		do {
			oX1-=4;
			iX-=4;

			out[oX2+0] = -(out[oX1+3] = out[iX+3]);
			out[oX2+1] = -(out[oX1+2] = out[iX+2]);
			out[oX2+2] = -(out[oX1+1] = out[iX+1]);
			out[oX2+3] = -(out[oX1+0] = out[iX+0]);

			oX2+=4;

		} while( oX2 < iX );

		// float *oX1=out+n2+n4;
		// float *oX2=out+n2;
		// float *iX =out+n2+n4;

		iX=n2+n4;
		oX1=n2+n4;
		oX2=n2;

		do {
			oX1-=4;
			out[oX1+0]= out[iX+3];
			out[oX1+1]= out[iX+2];
			out[oX1+2]= out[iX+1];
			out[oX1+3]= out[iX+0];
			iX+=4;

		} while( oX1 > oX2 );
	}

	public void mdct_forward( float[] in, float[] out ) {

		int n2=n>>1;
		int n4=n>>2;
		int n8=n>>3;

		float[] T  = trig;

		// float *w=alloca(n*sizeof(*w)); // forward needs working space
		float[] w = new float[ n ];
		// float *w2=w+n2;
		int w2 = n2;

		// rotate

		// window + rotate + step 1
  
		float r0;
		float r1;

		// float *x0=in+n2+n4;
		// float *x1=x0+1;
		// float *T=init->trig+n2;

		int x0 = n2+n4;
		int x1 = x0+1;
		int t = n2;
  
		int i=0;
  
		for( i=0; i < n8; i+=2 ) {

			x0 -=4;
			t-=2;
			r0= in[x0+2] + in[x1+0];
			r1= in[x0+0] + in[x1+2];       
			w[w2+i]=   (r1*T[t+1] + r0*T[t+0]);
			w[w2+i+1]= (r1*T[t+0] - r0*T[t+1]);
			x1 +=4;
		}

		// x1=in+1;
		x1 = 1;
  
		for( ; i < n2-n8; i+=2 ) {

			t-=2;
			x0 -=4;
			r0= in[x0+2] - in[x1+0];
			r1= in[x0+0] - in[x1+2];       
			w[w2+i]=   (r1*T[t+1] + r0*T[t+0]);
			w[w2+i+1]= (r1*T[t+0] - r0*T[t+1]);
			x1 +=4;
		}
    
		// x0=in+n;
		x0 = n;

		for( ; i < n2; i+=2 ) {

			t-=2;
			x0 -=4;
			r0= -in[x0+2] - in[x1+0];
			r1= -in[x0+0] - in[x1+2];       
			w[w2+i]=   (r1*T[t+1] + r0*T[t+0]);
			w[w2+i+1]= (r1*T[t+0] - r0*T[t+1]);
			x1 +=4;
		}

		mdct_butterflies( w, n2,n2);
		mdct_bitreverse(w);

		// roatate + window

		// T=init->trig+n2;
		// x0=out+n2;
		t = n2;
		x0 = n2;
		int w1 = 0;

		for( i=0; i < n4; i++) {

			x0--;
			out[i] =((w[w1+0]*T[t+0]+w[w1+1]*T[t+1])*scale);
			out[x0+0]  =((w[w1+0]*T[t+1]-w[w1+1]*T[t+0])*scale);
			w1+=2;
			t+=2;
		}
	}
	
	public void mdct_forward_offset( float[] in, int offset, float[] out, int out_offset ) {

		int n2=n>>1;
		int n4=n>>2;
		int n8=n>>3;

		float[] T  = trig;

		// float *w=alloca(n*sizeof(*w)); // forward needs working space
		float[] w = new float[ n ];
		// float *w2=w+n2;
		int w2 = n2;

		// rotate

		// window + rotate + step 1
  
		float r0;
		float r1;

		// float *x0=in+n2+n4;
		// float *x1=x0+1;
		// float *T=init->trig+n2;

		int x0 = n2+n4;
		int x1 = x0+1;
		int t = n2;
  
		int i=0;
  
		for( i=0; i < n8; i+=2 ) {

			x0 -=4;
			t-=2;
			r0= in[offset+x0+2] + in[offset+x1+0];
			r1= in[offset+x0+0] + in[offset+x1+2];       
			w[w2+i]=   (r1*T[t+1] + r0*T[t+0]);
			w[w2+i+1]= (r1*T[t+0] - r0*T[t+1]);
			x1 +=4;
		}

		// x1=in+1;
		x1 = 1;
  
		for( ; i < n2-n8; i+=2 ) {

			t-=2;
			x0 -=4;
			r0= in[offset+x0+2] - in[offset+x1+0];
			r1= in[offset+x0+0] - in[offset+x1+2];       
			w[w2+i]=   (r1*T[t+1] + r0*T[t+0]);
			w[w2+i+1]= (r1*T[t+0] - r0*T[t+1]);
			x1 +=4;
		}
    
		// x0=in+n;
		x0 = n;

		for( ; i < n2; i+=2 ) {

			t-=2;
			x0 -=4;
			r0= -in[offset+x0+2] - in[offset+x1+0];
			r1= -in[offset+x0+0] - in[offset+x1+2];       
			w[w2+i]=   (r1*T[t+1] + r0*T[t+0]);
			w[w2+i+1]= (r1*T[t+0] - r0*T[t+1]);
			x1 +=4;
		}

		mdct_butterflies( w, n2,n2);
		mdct_bitreverse(w);

		// roatate + window

		// T=init->trig+n2;
		// x0=out+n2;
		t = n2;
		x0 = n2;
		int w1 = 0;

		for( i=0; i < n4; i++) {

			x0--;
			out[out_offset+i] =((w[w1+0]*T[t+0]+w[w1+1]*T[t+1])*scale);
			out[out_offset+x0+0]  =((w[w1+0]*T[t+1]-w[w1+1]*T[t+0])*scale);
			w1+=2;
			t+=2;
		}
	}
}