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

class drft_lookup {

	static final int L101 = 101;
	static final int L102 = 102;
	static final int L103 = 103;
	static final int L104 = 104;
	static final int L105 = 105;
	static final int L106 = 106;
	static final int L107 = 107;
	static final int L109 = 109;
	static final int L110 = 110;
	static final int L112 = 112;
	static final int L115 = 115;
	static final int L116 = 116;
	static final int L119 = 119;
	static final int L128 = 128;
	static final int L132 = 132;
	static final int L135 = 135;
	static final int L139 = 139;
	static final int L141 = 141;
	static final int LDONE = 9999;
	

	int n;
	float[] trigcache;
	int[] splitcache;


	public drft_lookup() {}

	public drft_lookup( int _n, float[] _trigcache, int[] _splitcache ) {

		n = _n;
		trigcache = (float[])_trigcache.clone();
		splitcache = (int[])_splitcache.clone();
	}

	public drft_lookup( drft_lookup src ) {

		this( src.n, src.trigcache, src.splitcache );
	}

	public void drft_forward( float[] data ) {

		if ( n == 1 )
			return;

		// drftf1( l->n, data, l->trigcache, l->trigcache+l->n, l->splitcache );
		drftf1( n, data, trigcache, splitcache );
	}

	public void drft_backward( float[] data ) {

		if ( n == 1 )
			return;

		// drftb1( l->n, data, l->trigcache, l->trigcache+l->n, l->splitcache );
		drftb1( n, data, trigcache, splitcache );
	}

	public void drft_init( int _n ) {

  		n = _n;

		// l->trigcache=_ogg_calloc(3*n,sizeof(*l->trigcache));
		trigcache = new float[ 3*n ];

		// l->splitcache=_ogg_calloc(32,sizeof(*l->splitcache));
		splitcache = new int[ 32 ];

		fdrffti( n, trigcache, splitcache );
	}

	static void fdrffti( int _n, float[] wsave, int[] ifac ) {

		if (_n == 1)
			return;

		// drfti1(n, wsave+n, ifac);
		drfti1( _n, wsave, ifac );
	}

	static void drfti1( int _n, float[] wa, int[] ifac ) {

		int ntryh[] = { 4,2,3,5 };
		float tpi = 6.28318530717958648f;
		float arg, argh, argld, fi;
		int ntry=0, i, j=-1;
		int k1, l1, l2, ib;
		int ld, ii, ip, is, nq, nr;
		int ido, ipm, nfm1;
		int nl = _n;
		int nf = 0;

		int state = L101;

		while ( state != LDONE ) {

			switch (state) {

 				case L101: {
					j++;
					if (j < 4)
						ntry=ntryh[j];
					else
						ntry+=2;
					state = L104;
					break;
				}

				case L104: {
					nq=nl/ntry;
					nr=nl-ntry*nq;
					if (nr!=0) {
						state = L101;
						break;
					}
					nf++;
					ifac[nf+1]=ntry;
					nl=nq;
					if (ntry!=2) {
						state = L107;
						break;
					}
					if (nf==1) {
						state = L107;
						break;
					}
					for (i=1;i<nf;i++) {
						ib=nf-i+1;
						ifac[ib+1]=ifac[ib];
					}
					ifac[2] = 2;
					state = L107;
					break;
				}

				case L107: {
					if (nl!=1) {
						state = L104;
						break;
					}
					ifac[0]=_n;
					ifac[1]=nf;
					argh=tpi/_n;
					is=0;
					nfm1=nf-1;
					l1=1;

					if (nfm1==0)
						return;

					for (k1=0;k1<nfm1;k1++) {
						ip=ifac[k1+2];
						ld=0;
						l2=l1*ip;
						ido=_n/l2;
						ipm=ip-1;

						for (j=0;j<ipm;j++) {
							ld+=l1;
							i=is;
							argld=ld*argh;
							fi=0.f;
							for (ii=2;ii<ido;ii+=2){
								fi+=1.f;
								arg=fi*argld;
								wa[_n+(i++)] = new Double( Math.cos(arg) ).floatValue();
								wa[_n+(i++)] = new Double( Math.sin(arg) ).floatValue();
							}
							is+=ido;
						}
						l1=l2;
					}
					state = LDONE;
					break;
				}
			}
		}
	}

	static void drftf1( int _n, float[] c, float[] ch, int[] ifac ) {

		float[] wa = ch;
		int waoff = _n;
		int i,k1,l1=0,l2 = 0;
		int na,kh,nf = 0;
		int ip=0,iw,ido=0,idl1=0,ix2,ix3;

		nf=ifac[1];
		na=1;
		l2=_n;
		iw=_n;

		for ( k1=0; k1<nf; k1++) {

			int state = L101;

			while ( state != LDONE ) {

				switch (state) {

					case L101: {
						kh = nf-k1;
						ip = ifac[kh+1];
						l1 = l2/ip;
						ido = _n/l2;
						idl1 = ido*l1;
						iw -= (ip-1)*ido;
						na = 1-na;

						if (ip!=4) {
							state = L102;
							break;
						}

						ix2=iw+ido;
						ix3=ix2+ido;

						if (na!=0)
							dradf4( ido, l1, ch, c, wa, waoff+iw-1, waoff+ix2-1, waoff+ix3-1 );
						else
							dradf4( ido, l1, c, ch, wa, waoff+iw-1, waoff+ix2-1, waoff+ix3-1 );
						state = L110;
						break;
					}

					case L102: {
						if (ip!=2) {
							state = L104;
							break;
						}
						if (na!=0) {
							state = L103;
							break;
						}

						dradf2( ido, l1, c, ch, wa, waoff+iw-1 );
						state = L110;
						break;
					}

					case L103: {
						dradf2( ido, l1, ch, c, wa, waoff+iw-1 );
						state = L110;
						break;
					}

					case L104: {
						if (ido==1)
							na=1-na;
						if (na!=0) {
							state = L109;
							break;
						}

						dradfg( ido, ip, l1, idl1, c, c, c, ch, ch, wa, waoff+iw-1 );
						na=1;
						state = L110;
						break;
					}

					case L109: {
						dradfg( ido, ip, l1, idl1, ch, ch, ch, c, c, wa, waoff+iw-1 );
						na=0;
						state = L110;
						break;
					}

					case L110: {
						l2=l1;
						state = LDONE;
						break;
					}
				}
			}
		}

		if (na==1)
			return;

		for ( i=0; i<_n; i++ )
			c[i]=ch[i];
	}

	static void dradf2( int ido, int l1, float[] cc, float[] ch, float[] wa1, int waoff ) {

		int i,k;
		float ti2,tr2;
		int t0,t1,t2,t3,t4,t5,t6;

		t1=0;
		t0=(t2=l1*ido);
		t3=ido<<1;
		for ( k=0; k<l1; k++ ) {
			ch[t1<<1]=cc[t1]+cc[t2];
			ch[(t1<<1)+t3-1]=cc[t1]-cc[t2];
			t1+=ido;
			t2+=ido;
		}
    
		if ( ido < 2 )
			return;

		if ( ido != 2 ) {

			t1=0;
			t2=t0;

			for ( k=0; k<l1; k++ ) {
				t3=t2;
				t4=(t1<<1)+(ido<<1);
				t5=t1;
				t6=t1+t1;
				for ( i=2; i<ido; i+=2 ) {
					t3+=2;
					t4-=2;
					t5+=2;
					t6+=2;
					tr2=wa1[waoff+i-2]*cc[t3-1]+wa1[waoff+i-1]*cc[t3];
					ti2=wa1[waoff+i-2]*cc[t3]-wa1[waoff+i-1]*cc[t3-1];
					ch[t6]=cc[t5]+ti2;
					ch[t4]=ti2-cc[t5];
					ch[t6-1]=cc[t5-1]+tr2;
					ch[t4-1]=cc[t5-1]-tr2;
				}
				t1+=ido;
				t2+=ido;
			}

			if (ido%2==1)
				return;
		}

		t3=(t2=(t1=ido)-1);
		t2+=t0;
		for ( k=0; k<l1; k++ ) {
			ch[t1]=-cc[t2];
			ch[t1-1]=cc[t3];
			t1+=ido<<1;
			t2+=ido;
			t3+=ido;
		}
	}

	static void dradf4( int ido, int l1, float[] cc, float[] ch, float[] wa, int wa1, int wa2, int wa3 ) {

		float hsqt2 = .70710678118654752f;
		int i,k,t0,t1,t2,t3,t4,t5,t6;
		float ci2,ci3,ci4,cr2,cr3,cr4,ti1,ti2,ti3,ti4,tr1,tr2,tr3,tr4;
		t0=l1*ido;
  
		t1=t0;
		t4=t1<<1;
		t2=t1+(t1<<1);
		t3=0;

		for ( k=0; k<l1; k++ ) {
			tr1=cc[t1]+cc[t2];
			tr2=cc[t3]+cc[t4];

			ch[t5=t3<<2]=tr1+tr2;
			ch[(ido<<2)+t5-1]=tr2-tr1;
			ch[(t5+=(ido<<1))-1]=cc[t3]-cc[t4];
			ch[t5]=cc[t2]-cc[t1];

			t1+=ido;
			t2+=ido;
			t3+=ido;
			t4+=ido;
		}

		if ( ido < 2 )
			return;

		if ( ido != 2 ) {

			t1=0;
			for ( k=0; k<l1; k++ ) {
				t2=t1;
				t4=t1<<2;
				t5=(t6=ido<<1)+t4;
				for ( i=2; i<ido; i+=2 ) {
					t3=(t2+=2);
					t4+=2;
					t5-=2;

					t3+=t0;
					cr2=wa[wa1+i-2]*cc[t3-1]+wa[wa1+i-1]*cc[t3];
					ci2=wa[wa1+i-2]*cc[t3]-wa[wa1+i-1]*cc[t3-1];
					t3+=t0;
					cr3=wa[wa2+i-2]*cc[t3-1]+wa[wa2+i-1]*cc[t3];
					ci3=wa[wa2+i-2]*cc[t3]-wa[wa2+i-1]*cc[t3-1];
					t3+=t0;
					cr4=wa[wa3+i-2]*cc[t3-1]+wa[wa3+i-1]*cc[t3];
					ci4=wa[wa3+i-2]*cc[t3]-wa[wa3+i-1]*cc[t3-1];

					tr1=cr2+cr4;
					tr4=cr4-cr2;
					ti1=ci2+ci4;
					ti4=ci2-ci4;

					ti2=cc[t2]+ci3;
					ti3=cc[t2]-ci3;
					tr2=cc[t2-1]+cr3;
					tr3=cc[t2-1]-cr3;

					ch[t4-1]=tr1+tr2;
					ch[t4]=ti1+ti2;

					ch[t5-1]=tr3-ti4;
					ch[t5]=tr4-ti3;

					ch[t4+t6-1]=ti4+tr3;
					ch[t4+t6]=tr4+ti3;

					ch[t5+t6-1]=tr2-tr1;
					ch[t5+t6]=ti1-ti2;
				}
				t1+=ido;
			}
  			if ((ido&1) == 1)
				return;
		}

		t2=(t1=t0+ido-1)+(t0<<1);
  		t3=ido<<2;
		t4=ido;
		t5=ido<<1;
		t6=ido;

		for (k=0; k<l1; k++ ) {
			ti1=-hsqt2*(cc[t1]+cc[t2]);
			tr1=hsqt2*(cc[t1]-cc[t2]);

			ch[t4-1]=tr1+cc[t6-1];
			ch[t4+t5-1]=cc[t6-1]-tr1;

			ch[t4]=ti1-cc[t1+t0];
			ch[t4+t5]=ti1+cc[t1+t0];

			t1+=ido;
			t2+=ido;
			t4+=t3;
			t6+=ido;
		}
	}

	static void dradfg( int ido, int ip, int l1, int idl1, float[] cc, float[] c1, float[] c2, float[] ch, float[] ch2, float[] wa, int waoff ) {

		float tpi=6.283185307179586f;
		int idij,ipph,i,j,k,l,ic,ik,is;
		int t0,t1,t2,t3,t4,t5,t6,t7,t8,t9,t10;
		float dc2,ai1,ai2,ar1,ar2,ds2;
		int nbd;
		float dcp,arg,dsp,ar1h,ar2h;
		int idp2,ipp2;
  
		arg=tpi/ new Integer( ip ).floatValue();
		dcp=new Double( Math.cos(arg) ).floatValue();
		dsp=new Double( Math.sin(arg) ).floatValue();
		ipph=(ip+1)>>1;
		ipp2=ip;
		idp2=ido;
		nbd=(ido-1)>>1;
		t0=l1*ido;
		t10=ip*ido;

		if ( ido != 1 ) {

			for(ik=0;ik<idl1;ik++)ch2[ik]=c2[ik];

			t1=0;
			for(j=1;j<ip;j++){
				t1+=t0;
				t2=t1;
				for(k=0;k<l1;k++){
					ch[t2]=c1[t2];
					t2+=ido;
				}
			}

			is=-ido;
			t1=0;
			if(nbd>l1){
				for(j=1;j<ip;j++){
					t1+=t0;
					is+=ido;
					t2= -ido+t1;
					for(k=0;k<l1;k++){
						idij=is-1;
 						t2+=ido;
						t3=t2;
						for(i=2;i<ido;i+=2){
							idij+=2;
							t3+=2;
							ch[t3-1]=wa[waoff+idij-1]*c1[t3-1]+wa[waoff+idij]*c1[t3];
							ch[t3]=wa[waoff+idij-1]*c1[t3]-wa[waoff+idij]*c1[t3-1];
						}
					}
				}
			}else{
				for(j=1;j<ip;j++){
					is+=ido;
					idij=is-1;
					t1+=t0;
					t2=t1;
					for(i=2;i<ido;i+=2){
						idij+=2;
						t2+=2;
						t3=t2;
						for(k=0;k<l1;k++){
							ch[t3-1]=wa[waoff+idij-1]*c1[t3-1]+wa[waoff+idij]*c1[t3];
							ch[t3]=wa[waoff+idij-1]*c1[t3]-wa[waoff+idij]*c1[t3-1];
							t3+=ido;
						}
					}
				}
			}

			t1=0;
			t2=ipp2*t0;
			if(nbd<l1){
				for(j=1;j<ipph;j++){
					t1+=t0;
					t2-=t0;
					t3=t1;
					t4=t2;
					for(i=2;i<ido;i+=2){
						t3+=2;
						t4+=2;
						t5=t3-ido;
						t6=t4-ido;
						for(k=0;k<l1;k++){
							t5+=ido;
							t6+=ido;
							c1[t5-1]=ch[t5-1]+ch[t6-1];
							c1[t6-1]=ch[t5]-ch[t6];
							c1[t5]=ch[t5]+ch[t6];
							c1[t6]=ch[t6-1]-ch[t5-1];
						}
					}
				}
			}else{
				for(j=1;j<ipph;j++){
					t1+=t0;
					t2-=t0;
					t3=t1;
					t4=t2;
					for(k=0;k<l1;k++){
						t5=t3;
						t6=t4;
						for(i=2;i<ido;i+=2){
							t5+=2;
							t6+=2;
							c1[t5-1]=ch[t5-1]+ch[t6-1];
							c1[t6-1]=ch[t5]-ch[t6];
							c1[t5]=ch[t5]+ch[t6];
							c1[t6]=ch[t6-1]-ch[t5-1];
						}
						t3+=ido;
						t4+=ido;
					}
				}
			}
		}

		for(ik=0;ik<idl1;ik++)c2[ik]=ch2[ik];

		t1=0;
		t2=ipp2*idl1;
		for(j=1;j<ipph;j++){
			t1+=t0;
			t2-=t0;
			t3=t1-ido;
			t4=t2-ido;
			for(k=0;k<l1;k++){
				t3+=ido;
				t4+=ido;
				c1[t3]=ch[t3]+ch[t4];
				c1[t4]=ch[t4]-ch[t3];
			}
		}

		ar1=1.f;
		ai1=0.f;
		t1=0;
		t2=ipp2*idl1;
		t3=(ip-1)*idl1;
		for(l=1;l<ipph;l++){
			t1+=idl1;
			t2-=idl1;
			ar1h=dcp*ar1-dsp*ai1;
			ai1=dcp*ai1+dsp*ar1;
			ar1=ar1h;
			t4=t1;
			t5=t2;
			t6=t3;
			t7=idl1;

			for(ik=0;ik<idl1;ik++){
				ch2[t4++]=c2[ik]+ar1*c2[t7++];
				ch2[t5++]=ai1*c2[t6++];
			}

			dc2=ar1;
			ds2=ai1;
			ar2=ar1;
			ai2=ai1;

			t4=idl1;
			t5=(ipp2-1)*idl1;
			for(j=2;j<ipph;j++){
				t4+=idl1;
				t5-=idl1;

				ar2h=dc2*ar2-ds2*ai2;
				ai2=dc2*ai2+ds2*ar2;
				ar2=ar2h;

				t6=t1;
				t7=t2;
				t8=t4;
				t9=t5;
				for(ik=0;ik<idl1;ik++){
					ch2[t6++]+=ar2*c2[t8++];
					ch2[t7++]+=ai2*c2[t9++];
				}
			}
		}

		t1=0;
		for(j=1;j<ipph;j++){
			t1+=idl1;
			t2=t1;
			for(ik=0;ik<idl1;ik++)ch2[ik]+=c2[t2++];
		}

		if(ido<l1) {
			for(i=0;i<ido;i++){
				t1=i;
				t2=i;
				for(k=0;k<l1;k++){
					cc[t2]=ch[t1];
					t1+=ido;
					t2+=t10;
				}
			}
		} else {
			t1=0;
			t2=0;
			for(k=0;k<l1;k++){
				t3=t1;
				t4=t2;
				for(i=0;i<ido;i++)cc[t4++]=ch[t3++];
				t1+=ido;
				t2+=t10;
			}
		}

		t1=0;
		t2=ido<<1;
		t3=0;
		t4=ipp2*t0;
		for(j=1;j<ipph;j++){

			t1+=t2;
			t3+=t0;
			t4-=t0;

			t5=t1;
			t6=t3;
			t7=t4;

			for(k=0;k<l1;k++){
				cc[t5-1]=ch[t6];
				cc[t5]=ch[t7];
				t5+=t10;
				t6+=ido;
				t7+=ido;
			}
		}

		if (ido==1)
			return;

		if(nbd<l1) {
			t1=-ido;
			t3=0;
			t4=0;
			t5=ipp2*t0;
			for(j=1;j<ipph;j++){
				t1+=t2;
				t3+=t2;
				t4+=t0;
				t5-=t0;
				for(i=2;i<ido;i+=2){
					t6=idp2+t1-i;
					t7=i+t3;
					t8=i+t4;
					t9=i+t5;
					for(k=0;k<l1;k++){
						cc[t7-1]=ch[t8-1]+ch[t9-1];
						cc[t6-1]=ch[t8-1]-ch[t9-1];
						cc[t7]=ch[t8]+ch[t9];
						cc[t6]=ch[t9]-ch[t8];
						t6+=t10;
						t7+=t10;
						t8+=ido;
						t9+=ido;
					}
				}
			}
		} else {
			t1=-ido;
			t3=0;
			t4=0;
			t5=ipp2*t0;
			for(j=1;j<ipph;j++){
				t1+=t2;
				t3+=t2;
				t4+=t0;
				t5-=t0;
				t6=t1;
				t7=t3;
				t8=t4;
				t9=t5;
				for(k=0;k<l1;k++){
					for(i=2;i<ido;i+=2){
						ic=idp2-i;
						cc[i+t7-1]=ch[i+t8-1]+ch[i+t9-1];
						cc[ic+t6-1]=ch[i+t8-1]-ch[i+t9-1];
						cc[i+t7]=ch[i+t8]+ch[i+t9];
						cc[ic+t6]=ch[i+t9]-ch[i+t8];
					}
					t6+=t10;
					t7+=t10;
					t8+=ido;
					t9+=ido;
				}
			}
		}
	}

	static void drftb1( int _n, float[] c, float[] ch, int[] ifac ) {

		float[] wa = ch;
		int waoff = _n;
		int i,k1,l1,l2=0;
		int na;
		int nf,ip=0,iw,ix2,ix3,ido=0,idl1=0;

		nf=ifac[1];
		na=0;
		l1=1;
		iw=1;

		for ( k1=0; k1<nf; k1++ ) {

			int state = L101;

			while ( state != LDONE ) {

				switch (state) {

					case L101: {
						ip = ifac[k1 + 2];
						l2 = ip*l1;
						ido = _n/l2;
						idl1 = ido*l1;
						if (ip!=4) {
							state = L103;
							break;
						}

						ix2=iw+ido;
						ix3=ix2+ido;

						if (na!=0)
							dradb4( ido, l1, ch, c, wa, waoff+iw-1, waoff+ix2-1, waoff+ix3-1 );
						else
							dradb4( ido, l1, c, ch, wa, waoff+iw-1, waoff+ix2-1, waoff+ix3-1 );
						na=1-na;
						state = L115;
						break;
					} 

					case L103: {
						if (ip!=2) {
							state = L106;
							break;
						}

						if(na!=0)
							dradb2( ido, l1, ch, c, wa, waoff+iw-1 );
						else
							dradb2( ido, l1, c, ch, wa, waoff+iw-1 );
						na=1-na;
						state = L115;
						break;
					}

					case L106: {
						if (ip!=3) {
							state = L109;
							break;
						}

						ix2=iw+ido;
						if(na!=0)
							dradb3( ido, l1, ch, c, wa, waoff+iw-1, waoff+ix2-1 );
						else
							dradb3( ido, l1, c, ch, wa, waoff+iw-1, waoff+ix2-1 );
						na=1-na;
						state = L115;
						break;
					}

					case L109: {
					//	The radix five case can be translated later.....
					//	if(ip!=5)goto L112;

					//	ix2=iw+ido;
					//	ix3=ix2+ido;
					//	ix4=ix3+ido;
					//	if(na!=0)
					//		dradb5(ido,l1,ch,c,wa+iw-1,wa+ix2-1,wa+ix3-1,wa+ix4-1 );
					//	else
					//		dradb5(ido,l1,c,ch,wa+iw-1,wa+ix2-1,wa+ix3-1,wa+ix4-1 );
					//	na=1-na;
					//	goto L115;

					// L112:

						if (na!=0)
							dradbg( ido, ip, l1, idl1, ch, ch, ch, c, c, wa, waoff+iw-1 );
						else
							dradbg( ido, ip, l1, idl1, c, c, c, ch, ch, wa, waoff+iw-1 );
						if (ido==1)
							na=1-na;
						state = L115;
						break;
					}

					case L115: {
						l1=l2;
						iw+=(ip-1)*ido;
						state = LDONE;
						break;
					}
				}
			}
		}

		if (na==0)
			return;

		for ( i=0; i<_n; i++ )
			c[i]=ch[i];
	}

	static void dradb2( int ido, int l1, float[] cc, float[] ch, float[] wa1, int waoff ) {

		int i,k,t0,t1,t2,t3,t4,t5,t6;
		float ti2,tr2;

		t0=l1*ido;
  
		t1=0;
		t2=0;
		t3=(ido<<1)-1;
		for ( k=0; k<l1; k++ ) {
			ch[t1]=cc[t2]+cc[t3+t2];
			ch[t1+t0]=cc[t2]-cc[t3+t2];
			t2=(t1+=ido)<<1;
		}

		if ( ido < 2 )
			return;

		if ( ido != 2 ) {

			t1=0;
			t2=0;
			for ( k=0; k<l1; k++ ) {
				t3=t1;
				t5=(t4=t2)+(ido<<1);
				t6=t0+t1;
				for ( i=2; i<ido; i+=2 ) {
					t3+=2;
					t4+=2;
					t5-=2;
					t6+=2;
					ch[t3-1]=cc[t4-1]+cc[t5-1];
					tr2=cc[t4-1]-cc[t5-1];
					ch[t3]=cc[t4]-cc[t5];
					ti2=cc[t4]+cc[t5];
					ch[t6-1]=wa1[waoff+i-2]*tr2-wa1[waoff+i-1]*ti2;
					ch[t6]=wa1[waoff+i-2]*ti2+wa1[waoff+i-1]*tr2;
				}
				t2=(t1+=ido)<<1;
			}

			if (ido%2==1)
				return;
		}

		t1=ido-1;
		t2=ido-1;
		for ( k=0; k<l1; k++ ) {
			ch[t1]=cc[t2]+cc[t2];
			ch[t1+t0]=-(cc[t2+1]+cc[t2+1]);
			t1+=ido;
			t2+=ido<<1;
		}
	}

	static void dradb3( int ido, int l1, float[] cc, float[] ch, float[] wa, int wa1, int wa2 ) {

		float taur = -.5f;
		float taui = .8660254037844386f;
		int i,k,t0,t1,t2,t3,t4,t5,t6,t7,t8,t9,t10;
		float ci2,ci3,di2,di3,cr2,cr3,dr2,dr3,ti2,tr2;
		t0=l1*ido;

		t1=0;
		t2=t0<<1;
		t3=ido<<1;
		t4=ido+(ido<<1);
		t5=0;
		for ( k=0; k<l1; k++ ) {
			tr2=cc[t3-1]+cc[t3-1];
			cr2=cc[t5]+(taur*tr2);
			ch[t1]=cc[t5]+tr2;
			ci3=taui*(cc[t3]+cc[t3]);
			ch[t1+t0]=cr2-ci3;
			ch[t1+t2]=cr2+ci3;
			t1+=ido;
			t3+=t4;
			t5+=t4;
		}

		if (ido==1)
			return;

		t1=0;
		t3=ido<<1;
		for ( k=0; k<l1; k++ ) {
			t7=t1+(t1<<1);
			t6=(t5=t7+t3);
			t8=t1;
			t10=(t9=t1+t0)+t0;

			for ( i=2; i<ido; i+=2 ) {
				t5+=2;
				t6-=2;
				t7+=2;
				t8+=2;
				t9+=2;
				t10+=2;
				tr2=cc[t5-1]+cc[t6-1];
				cr2=cc[t7-1]+(taur*tr2);
				ch[t8-1]=cc[t7-1]+tr2;
				ti2=cc[t5]-cc[t6];
				ci2=cc[t7]+(taur*ti2);
				ch[t8]=cc[t7]+ti2;
				cr3=taui*(cc[t5-1]-cc[t6-1]);
				ci3=taui*(cc[t5]+cc[t6]);
				dr2=cr2-ci3;
				dr3=cr2+ci3;
				di2=ci2+cr3;
				di3=ci2-cr3;
				ch[t9-1]=wa[wa1+i-2]*dr2-wa[wa1+i-1]*di2;
				ch[t9]=wa[wa1+i-2]*di2+wa[wa1+i-1]*dr2;
				ch[t10-1]=wa[wa2+i-2]*dr3-wa[wa2+i-1]*di3;
				ch[t10]=wa[wa2+i-2]*di3+wa[wa2+i-1]*dr3;
			}
			t1+=ido;
		}
	}

	static void dradb4( int ido, int l1, float[] cc, float[] ch, float[] wa, int wa1, int wa2, int wa3 ) {

		float sqrt2=1.414213562373095f;
		int i,k,t0,t1,t2,t3,t4,t5,t6,t7,t8;
		float ci2,ci3,ci4,cr2,cr3,cr4,ti1,ti2,ti3,ti4,tr1,tr2,tr3,tr4;
		t0=l1*ido;
  
		t1=0;
		t2=ido<<2;
		t3=0;
		t6=ido<<1;
		for ( k=0; k<l1; k++ ) {
			t4=t3+t6;
			t5=t1;
			tr3=cc[t4-1]+cc[t4-1];
			tr4=cc[t4]+cc[t4]; 
			tr1=cc[t3]-cc[(t4+=t6)-1];
			tr2=cc[t3]+cc[t4-1];
			ch[t5]=tr2+tr3;
			ch[t5+=t0]=tr1-tr4;
			ch[t5+=t0]=tr2-tr3;
			ch[t5+=t0]=tr1+tr4;
			t1+=ido;
			t3+=t2;
		}

		if ( ido < 2 )
			return;

		if ( ido != 2 ) {

			t1=0;
			for ( k=0; k<l1; k++ ) {
				t5=(t4=(t3=(t2=t1<<2)+t6))+t6;
				t7=t1;
				for ( i=2; i<ido; i+=2 ) {
					t2+=2;
					t3+=2;
					t4-=2;
					t5-=2;
					t7+=2;
					ti1=cc[t2]+cc[t5];
					ti2=cc[t2]-cc[t5];
					ti3=cc[t3]-cc[t4];
					tr4=cc[t3]+cc[t4];
					tr1=cc[t2-1]-cc[t5-1];
					tr2=cc[t2-1]+cc[t5-1];
					ti4=cc[t3-1]-cc[t4-1];
					tr3=cc[t3-1]+cc[t4-1];
					ch[t7-1]=tr2+tr3;
					cr3=tr2-tr3;
					ch[t7]=ti2+ti3;
					ci3=ti2-ti3;
					cr2=tr1-tr4;
					cr4=tr1+tr4;
					ci2=ti1+ti4;
					ci4=ti1-ti4;

					ch[(t8=t7+t0)-1]=wa[wa1+i-2]*cr2-wa[wa1+i-1]*ci2;
					ch[t8]=wa[wa1+i-2]*ci2+wa[wa1+i-1]*cr2;
					ch[(t8+=t0)-1]=wa[wa2+i-2]*cr3-wa[wa2+i-1]*ci3;
					ch[t8]=wa[wa2+i-2]*ci3+wa[wa2+i-1]*cr3;
					ch[(t8+=t0)-1]=wa[wa3+i-2]*cr4-wa[wa3+i-1]*ci4;
					ch[t8]=wa[wa3+i-2]*ci4+wa[wa3+i-1]*cr4;
				}
				t1+=ido;
			}

  			if (ido%2 == 1)
				return;
		}

		t1=ido;
		t2=ido<<2;
		t3=ido-1;
		t4=ido+(ido<<1);
		for ( k=0; k<l1; k++ ) {
			t5=t3;
			ti1=cc[t1]+cc[t4];
			ti2=cc[t4]-cc[t1];
			tr1=cc[t1-1]-cc[t4-1];
			tr2=cc[t1-1]+cc[t4-1];
			ch[t5]=tr2+tr2;
			ch[t5+=t0]=sqrt2*(tr1-ti1);
			ch[t5+=t0]=ti2+ti2;
			ch[t5+=t0]=-sqrt2*(tr1+ti1);

			t3+=ido;
			t1+=t2;
			t4+=t2;
		}
	}

	static void dradbg( int ido, int ip, int l1, int idl1, float[] cc, float[] c1, float[] c2, float[] ch, float[] ch2, float[] wa, int waoff ) {

		float tpi=6.283185307179586f;
		int idij,ipph,i,j,k,l,ik,is,t0,t1,t2,t3,t4,t5,t6,t7,t8,t9,t10,t11,t12;
		float dc2,ai1,ai2,ar1,ar2,ds2;
		int nbd;
		float dcp,arg,dsp,ar1h,ar2h;
		int ipp2;

		t10=ip*ido;
		t0=l1*ido;
		arg=tpi/ new Integer( ip ).floatValue();
		dcp=new Double( Math.cos(arg) ).floatValue();
		dsp=new Double( Math.sin(arg) ).floatValue();
		nbd=(ido-1)>>1;
		ipp2=ip;
		ipph=(ip+1)>>1;

		if(ido<l1) {
			t1=0;
			for(i=0;i<ido;i++){
				t2=t1;
				t3=t1;
				for(k=0;k<l1;k++){
					ch[t2]=cc[t3];
					t2+=ido;
					t3+=t10;
				}
				t1++;
			}
		} else {
			t1=0;
			t2=0;
			for(k=0;k<l1;k++){
				t3=t1;
				t4=t2;
				for(i=0;i<ido;i++){
					ch[t3]=cc[t4];
					t3++;
					t4++;
				}
				t1+=ido;
				t2+=t10;
			}
		}

		t1=0;
		t2=ipp2*t0;
		t7=(t5=ido<<1);
		for(j=1;j<ipph;j++){
			t1+=t0;
			t2-=t0;
			t3=t1;
			t4=t2;
			t6=t5;
			for(k=0;k<l1;k++){
				ch[t3]=cc[t6-1]+cc[t6-1];
				ch[t4]=cc[t6]+cc[t6];
				t3+=ido;
				t4+=ido;
				t6+=t10;
			}
			t5+=t7;
		}

		if (ido != 1) {

			if(nbd<l1) {
				t1=0;
				t2=ipp2*t0;
				t7=0;
				for(j=1;j<ipph;j++){
					t1+=t0;
					t2-=t0;
					t3=t1;
					t4=t2;
					t7+=(ido<<1);
					t8=t7;
					t9=t7;
					for(i=2;i<ido;i+=2){
						t3+=2;
						t4+=2;
						t8+=2;
						t9-=2;
						t5=t3;
						t6=t4;
						t11=t8;
						t12=t9;
						for(k=0;k<l1;k++){
							ch[t5-1]=cc[t11-1]+cc[t12-1];
							ch[t6-1]=cc[t11-1]-cc[t12-1];
							ch[t5]=cc[t11]-cc[t12];
							ch[t6]=cc[t11]+cc[t12];
							t5+=ido;
							t6+=ido;
							t11+=t10;
							t12+=t10;
						}
					}
				}
			} else {
				t1=0;
				t2=ipp2*t0;
				t7=0;
				for(j=1;j<ipph;j++){
					t1+=t0;
					t2-=t0;
					t3=t1;
					t4=t2;

					t7+=(ido<<1);
					t8=t7;
					for(k=0;k<l1;k++){
						t5=t3;
						t6=t4;
						t9=t8;
						t11=t8;
						for(i=2;i<ido;i+=2){
							t5+=2;
							t6+=2;
							t9+=2;
							t11-=2;
							ch[t5-1]=cc[t9-1]+cc[t11-1];
							ch[t6-1]=cc[t9-1]-cc[t11-1];
							ch[t5]=cc[t9]-cc[t11];
							ch[t6]=cc[t9]+cc[t11];
						}
						t3+=ido;
						t4+=ido;
						t8+=t10;
					}
				}
			}
		}

		ar1=1.f;
		ai1=0.f;
		t1=0;
		t9=(t2=ipp2*idl1);
		t3=(ip-1)*idl1;
		for(l=1;l<ipph;l++){
			t1+=idl1;
			t2-=idl1;

			ar1h=dcp*ar1-dsp*ai1;
			ai1=dcp*ai1+dsp*ar1;
			ar1=ar1h;
			t4=t1;
			t5=t2;
			t6=0;
			t7=idl1;
			t8=t3;
			for(ik=0;ik<idl1;ik++){
				c2[t4++]=ch2[t6++]+ar1*ch2[t7++];
				c2[t5++]=ai1*ch2[t8++];
			}
			dc2=ar1;
			ds2=ai1;
			ar2=ar1;
			ai2=ai1;

			t6=idl1;
			t7=t9-idl1;
			for(j=2;j<ipph;j++){
				t6+=idl1;
				t7-=idl1;
				ar2h=dc2*ar2-ds2*ai2;
				ai2=dc2*ai2+ds2*ar2;
				ar2=ar2h;
				t4=t1;
				t5=t2;
				t11=t6;
				t12=t7;
				for(ik=0;ik<idl1;ik++){
					c2[t4++]+=ar2*ch2[t11++];
					c2[t5++]+=ai2*ch2[t12++];
				}
			}
		}

		t1=0;
		for(j=1;j<ipph;j++){
			t1+=idl1;
			t2=t1;
			for(ik=0;ik<idl1;ik++)ch2[ik]+=ch2[t2++];
		}

		t1=0;
		t2=ipp2*t0;
		for(j=1;j<ipph;j++){
			t1+=t0;
			t2-=t0;
			t3=t1;
			t4=t2;
			for(k=0;k<l1;k++){
				ch[t3]=c1[t3]-c1[t4];
				ch[t4]=c1[t3]+c1[t4];
				t3+=ido;
				t4+=ido;
			}
		}

		if (ido != 1) {

			if(nbd<l1) {
				t1=0;
				t2=ipp2*t0;
				for(j=1;j<ipph;j++){
					t1+=t0;
					t2-=t0;
					t3=t1;
					t4=t2;
					for(i=2;i<ido;i+=2){
						t3+=2;
						t4+=2;
						t5=t3;
						t6=t4;
						for(k=0;k<l1;k++){
							ch[t5-1]=c1[t5-1]-c1[t6];
							ch[t6-1]=c1[t5-1]+c1[t6];
							ch[t5]=c1[t5]+c1[t6-1];
							ch[t6]=c1[t5]-c1[t6-1];
							t5+=ido;
							t6+=ido;
						}
					}
				}
			} else {
				t1=0;
				t2=ipp2*t0;
				for(j=1;j<ipph;j++){
					t1+=t0;
					t2-=t0;
					t3=t1;
					t4=t2;
					for(k=0;k<l1;k++){
						t5=t3;
						t6=t4;
						for(i=2;i<ido;i+=2){
							t5+=2;
							t6+=2;
							ch[t5-1]=c1[t5-1]-c1[t6];
							ch[t6-1]=c1[t5-1]+c1[t6];
							ch[t5]=c1[t5]+c1[t6-1];
							ch[t6]=c1[t5]-c1[t6-1];
						}
						t3+=ido;
						t4+=ido;
					}
				}
			}
		}

		if(ido==1)
			return;

		for(ik=0;ik<idl1;ik++)c2[ik]=ch2[ik];

		t1=0;
		for(j=1;j<ip;j++){
			t2=(t1+=t0);
			for(k=0;k<l1;k++){
				c1[t2]=ch[t2];
				t2+=ido;
			}
		}

		if(nbd>l1) {

			is= -ido-1;
			t1=0;
			for(j=1;j<ip;j++){
				is+=ido;
				t1+=t0;
				t2=t1;
				for(k=0;k<l1;k++){
					idij=is;
					t3=t2;
					for(i=2;i<ido;i+=2){
						idij+=2;
						t3+=2;
						c1[t3-1]=wa[waoff+idij-1]*ch[t3-1]-wa[waoff+idij]*ch[t3];
						c1[t3]=wa[waoff+idij-1]*ch[t3]+wa[waoff+idij]*ch[t3-1];
					}
					t2+=ido;
				}
			}
		} else {

			is= -ido-1;
			t1=0;
			for(j=1;j<ip;j++){
				is+=ido;
				t1+=t0;
				idij=is;
				t2=t1;
				for(i=2;i<ido;i+=2){
					t2+=2;
					idij+=2;
					t3=t2;
					for(k=0;k<l1;k++){
						c1[t3-1]=wa[waoff+idij-1]*ch[t3-1]-wa[waoff+idij]*ch[t3];
						c1[t3]=wa[waoff+idij-1]*ch[t3]+wa[waoff+idij]*ch[t3-1];
						t3+=ido;
					}
				}
			}
		}
	}
}