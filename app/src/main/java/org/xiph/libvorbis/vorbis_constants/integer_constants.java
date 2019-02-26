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


package org.xiph.libvorbis.vorbis_constants;


public class integer_constants {

	public static final int PACKETBLOBS = 15;

	public static final int NOISE_COMPAND_LEVELS = 40;

	public static final int P_BANDS = 17;		// 62Hz to 16kHz
	public static final int P_LEVELS = 8;		// 30dB to 100dB
	public static final int P_LEVEL_0 = 30;		// 30 dB
	public static final int P_NOISECURVES = 3;
	
	public static final int VQ_FEXP = 10;
	public static final int VQ_FMAN = 21;
	public static final int VQ_FEXP_BIAS = 768; // bias toward values smaller than 1.

	public static final int MAX_ATH = 88;
	public static final int EHMER_OFFSET = 16;
	public static final int EHMER_MAX = 56;

	public static final int VE_PRE = 16;
	public static final int VE_WIN = 4;
	public static final int VE_POST = 2;
	public static final int VE_AMP = (VE_PRE+VE_POST-1);
	public static final int VE_BANDS = 7;
	public static final int VE_NEARDC = 15;
	public static final int VE_MINSTRETCH = 2;   // a bit less than short block
	public static final int VE_MAXSTRETCH = 12;  // one-third full block
	
	public static final int BLOCKTYPE_IMPULSE = 0;
	public static final int BLOCKTYPE_PADDING = 1;
	public static final int BLOCKTYPE_TRANSITION = 0;
	public static final int BLOCKTYPE_LONG = 1;
	
	public static final int WORD_ALIGN = 8;

	public static final int VIF_POSIT = 63;
	public static final int VIF_CLASS = 16;
	public static final int VIF_PARTS = 31;

	public static final int VI_TRANSFORMB = 1;
	public static final int VI_WINDOWB = 1;
	public static final int VI_TIMEB = 1;
	public static final int VI_FLOORB = 2;
	public static final int VI_RESB = 3;
	public static final int VI_MAPB = 1;

	public static final float cPI3_8 = .38268343236508977175F;
	public static final float cPI2_8 = .70710678118654752441F;
	public static final float cPI1_8 = .92387953251128675613F;
	public static final float M_PI = (3.1415926536f);

	public static final int BUFFER_INCREMENT = 256;
	
	public static final float NEGINF = -9999.f;
	public static float[] stereo_threshholds = { 0.0f, .5f, 1.0f, 1.5f, 2.5f, 4.5f, 8.5f, 16.5f, 9e10f };
	public static float[] stereo_threshholds_limited = { 0.0f, .5f, 1.0f, 1.5f, 2.0f, 2.5f, 4.5f, 8.5f, 9e10f };
	
	
	public static final int OV_FALSE = -1;
	public static final int OV_EOF = -2;
	public static final int OV_HOLE = -3;

	public static final int OV_EREAD = -128;
	public static final int OV_EFAULT = -129;
	public static final int OV_EIMPL = -130;
	public static final int OV_EINVAL = -131;
	public static final int OV_ENOTVORBIS = -132;
	public static final int OV_EBADHEADER = -133;
	public static final int OV_EVERSION = -134;
	public static final int OV_ENOTAUDIO = -135;
	public static final int OV_EBADPACKET = -136;
	public static final int OV_EBADLINK = -137;
	public static final int OV_ENOSEEK = -138;
	
	// deprecated rate management supported only for compatability
	public static final int OV_ECTL_RATEMANAGE_GET = 0x10;
	public static final int OV_ECTL_RATEMANAGE_SET = 0x11;
	public static final int OV_ECTL_RATEMANAGE_AVG = 0x12;
	public static final int OV_ECTL_RATEMANAGE_HARD = 0x13;
	
	// new rate setup
	public static final int OV_ECTL_RATEMANAGE2_GET = 0x14;
	public static final int OV_ECTL_RATEMANAGE2_SET = 0x15;
	
	public static final int OV_ECTL_LOWPASS_GET = 0x20;
	public static final int OV_ECTL_LOWPASS_SET = 0x21;

	public static final int OV_ECTL_IBLOCK_GET = 0x30;
	public static final int OV_ECTL_IBLOCK_SET = 0x31;


	public static int ilog( int v ) {

		int ret=0;
		while ( v > 0 ) {
			ret++;
			v >>>= 1;
		}
		return ret;
	}

	public static int ilog2( int v ) {

		int ret=0;
		if ( v > 0 ) { --v; }
		while ( v > 0 ) {
			ret++;
			v >>>= 1;
		}
  		return ret;
	}
	
	public static int icount( int v ) {
		
		int ret=0;
		while( v > 0 ) {
			ret += v&1;
			v >>>= 1;
		}
		return ret;
	}
	
	public static float unitnorm( float x ) {
		
		float calc;
		int calc2;
		
		// Bit 31 (the bit that is selected by the mask 0x80000000) represents the sign of the floating-point number
		// 0x3f800000 is the hex representation of the float 1.0f.
		
		int i = Float.floatToIntBits( x );
		calc2 = (i & 0x80000000) | (0x3f800000);
		calc = Float.intBitsToFloat( calc2 );
		
		return calc;
	}
	
	public static float todB( float x ) {
		
		// ogg_int32_t *i=(ogg_int32_t *)x;
		int i = Float.floatToIntBits(x);
		i = i & 0x7fffffff;
		// return (float)(i * 7.17711438e-7f -764.6161886f);
		float calc = i * 7.17711438e-7f;
		calc -= 764.6161886f;
		return calc;
	}
	
	public static int render_point( int x0, int x1, int y0, int y1, int x ) {
		
		y0 &= 0x7fff; // mask off flag
		y1 &= 0x7fff;
		
		int dy = y1-y0;
		int adx = x1-x0;
		int ady = Math.abs(dy);
		int err = ady*(x-x0);
		
		int off = err/adx;
		if ( dy < 0 )
			return(y0-off);
		
		return(y0+off);
	}
	
	public static int vorbis_dBquant( float x ) {
		
		int i = new Float( x * 7.3142857f + 1023.5f ).intValue();
		if ( i > 1023 )
			return(1023);
		if ( i < 0 )
			return(0);
		return i;
	}
	
	public static float[] FLOOR1_fromdB_LOOKUP = { // [256]
		  1.0649863e-07F, 1.1341951e-07F, 1.2079015e-07F, 1.2863978e-07F, 
		  1.3699951e-07F, 1.4590251e-07F, 1.5538408e-07F, 1.6548181e-07F, 
		  1.7623575e-07F, 1.8768855e-07F, 1.9988561e-07F, 2.128753e-07F, 
		  2.2670913e-07F, 2.4144197e-07F, 2.5713223e-07F, 2.7384213e-07F, 
		  2.9163793e-07F, 3.1059021e-07F, 3.3077411e-07F, 3.5226968e-07F, 
		  3.7516214e-07F, 3.9954229e-07F, 4.2550680e-07F, 4.5315863e-07F, 
		  4.8260743e-07F, 5.1396998e-07F, 5.4737065e-07F, 5.8294187e-07F, 
		  6.2082472e-07F, 6.6116941e-07F, 7.0413592e-07F, 7.4989464e-07F, 
		  7.9862701e-07F, 8.5052630e-07F, 9.0579828e-07F, 9.6466216e-07F, 
		  1.0273513e-06F, 1.0941144e-06F, 1.1652161e-06F, 1.2409384e-06F, 
		  1.3215816e-06F, 1.4074654e-06F, 1.4989305e-06F, 1.5963394e-06F, 
		  1.7000785e-06F, 1.8105592e-06F, 1.9282195e-06F, 2.0535261e-06F, 
		  2.1869758e-06F, 2.3290978e-06F, 2.4804557e-06F, 2.6416497e-06F, 
		  2.8133190e-06F, 2.9961443e-06F, 3.1908506e-06F, 3.3982101e-06F, 
		  3.6190449e-06F, 3.8542308e-06F, 4.1047004e-06F, 4.3714470e-06F, 
		  4.6555282e-06F, 4.9580707e-06F, 5.2802740e-06F, 5.6234160e-06F, 
		  5.9888572e-06F, 6.3780469e-06F, 6.7925283e-06F, 7.2339451e-06F, 
		  7.7040476e-06F, 8.2047000e-06F, 8.7378876e-06F, 9.3057248e-06F, 
		  9.9104632e-06F, 1.0554501e-05F, 1.1240392e-05F, 1.1970856e-05F, 
		  1.2748789e-05F, 1.3577278e-05F, 1.4459606e-05F, 1.5399272e-05F, 
		  1.6400004e-05F, 1.7465768e-05F, 1.8600792e-05F, 1.9809576e-05F, 
		  2.1096914e-05F, 2.2467911e-05F, 2.3928002e-05F, 2.5482978e-05F, 
		  2.7139006e-05F, 2.8902651e-05F, 3.0780908e-05F, 3.2781225e-05F, 
		  3.4911534e-05F, 3.7180282e-05F, 3.9596466e-05F, 4.2169667e-05F, 
		  4.4910090e-05F, 4.7828601e-05F, 5.0936773e-05F, 5.4246931e-05F, 
		  5.7772202e-05F, 6.1526565e-05F, 6.5524908e-05F, 6.9783085e-05F, 
		  7.4317983e-05F, 7.9147585e-05F, 8.4291040e-05F, 8.9768747e-05F, 
		  9.5602426e-05F, 0.00010181521F, 0.00010843174F, 0.00011547824F, 
		  0.00012298267F, 0.00013097477F, 0.00013948625F, 0.00014855085F, 
		  0.00015820453F, 0.00016848555F, 0.00017943469F, 0.00019109536F, 
		  0.00020351382F, 0.00021673929F, 0.00023082423F, 0.00024582449F, 
		  0.00026179955F, 0.00027881276F, 0.00029693158F, 0.00031622787F, 
		  0.00033677814F, 0.00035866388F, 0.00038197188F, 0.00040679456F, 
		  0.00043323036F, 0.00046138411F, 0.00049136745F, 0.00052329927F, 
		  0.00055730621F, 0.00059352311F, 0.00063209358F, 0.00067317058F, 
		  0.00071691700F, 0.00076350630F, 0.00081312324F, 0.00086596457F, 
		  0.00092223983F, 0.00098217216F, 0.0010459992F, 0.0011139742F, 
		  0.0011863665F, 0.0012634633F, 0.0013455702F, 0.0014330129F, 
		  0.0015261382F, 0.0016253153F, 0.0017309374F, 0.0018434235F, 
		  0.0019632195F, 0.0020908006F, 0.0022266726F, 0.0023713743F, 
		  0.0025254795F, 0.0026895994F, 0.0028643847F, 0.0030505286F, 
		  0.0032487691F, 0.0034598925F, 0.0036847358F, 0.0039241906F, 
		  0.0041792066F, 0.0044507950F, 0.0047400328F, 0.0050480668F, 
		  0.0053761186F, 0.0057254891F, 0.0060975636F, 0.0064938176F, 
		  0.0069158225F, 0.0073652516F, 0.0078438871F, 0.0083536271F, 
		  0.0088964928F, 0.009474637F, 0.010090352F, 0.010746080F, 
		  0.011444421F, 0.012188144F, 0.012980198F, 0.013823725F, 
		  0.014722068F, 0.015678791F, 0.016697687F, 0.017782797F, 
		  0.018938423F, 0.020169149F, 0.021479854F, 0.022875735F, 
		  0.024362330F, 0.025945531F, 0.027631618F, 0.029427276F, 
		  0.031339626F, 0.033376252F, 0.035545228F, 0.037855157F, 
		  0.040315199F, 0.042935108F, 0.045725273F, 0.048696758F, 
		  0.051861348F, 0.055231591F, 0.058820850F, 0.062643361F, 
		  0.066714279F, 0.071049749F, 0.075666962F, 0.080584227F, 
		  0.085821044F, 0.091398179F, 0.097337747F, 0.10366330F, 
		  0.11039993F, 0.11757434F, 0.12521498F, 0.13335215F, 
		  0.14201813F, 0.15124727F, 0.16107617F, 0.17154380F, 
		  0.18269168F, 0.19456402F, 0.20720788F, 0.22067342F, 
		  0.23501402F, 0.25028656F, 0.26655159F, 0.28387361F, 
		  0.30232132F, 0.32196786F, 0.34289114F, 0.36517414F, 
		  0.38890521F, 0.41417847F, 0.44109412F, 0.46975890F, 
		  0.50028648F, 0.53279791F, 0.56742212F, 0.60429640F, 
		  0.64356699F, 0.68538959F, 0.72993007F, 0.77736504F, 
		  0.82788260F, 0.88168307F, 0.9389798F, 1.F, 
		};
	
	public static float[] FLOOR1_fromdB_INV_LOOKUP = {	// [256]
		  0.F, 8.81683e+06F, 8.27882e+06F, 7.77365e+06F, 
		  7.29930e+06F, 6.85389e+06F, 6.43567e+06F, 6.04296e+06F, 
		  5.67422e+06F, 5.32798e+06F, 5.00286e+06F, 4.69759e+06F, 
		  4.41094e+06F, 4.14178e+06F, 3.88905e+06F, 3.65174e+06F, 
		  3.42891e+06F, 3.21968e+06F, 3.02321e+06F, 2.83873e+06F, 
		  2.66551e+06F, 2.50286e+06F, 2.35014e+06F, 2.20673e+06F, 
		  2.07208e+06F, 1.94564e+06F, 1.82692e+06F, 1.71544e+06F, 
		  1.61076e+06F, 1.51247e+06F, 1.42018e+06F, 1.33352e+06F, 
		  1.25215e+06F, 1.17574e+06F, 1.10400e+06F, 1.03663e+06F, 
		  973377.F, 913981.F, 858210.F, 805842.F, 
		  756669.F, 710497.F, 667142.F, 626433.F, 
		  588208.F, 552316.F, 518613.F, 486967.F, 
		  457252.F, 429351.F, 403152.F, 378551.F, 
		  355452.F, 333762.F, 313396.F, 294273.F, 
		  276316.F, 259455.F, 243623.F, 228757.F, 
		  214798.F, 201691.F, 189384.F, 177828.F, 
		  166977.F, 156788.F, 147221.F, 138237.F, 
		  129802.F, 121881.F, 114444.F, 107461.F, 
		  100903.F, 94746.3F, 88964.9F, 83536.2F, 
		  78438.8F, 73652.5F, 69158.2F, 64938.1F, 
		  60975.6F, 57254.9F, 53761.2F, 50480.6F, 
		  47400.3F, 44507.9F, 41792.0F, 39241.9F, 
		  36847.3F, 34598.9F, 32487.7F, 30505.3F, 
		  28643.8F, 26896.0F, 25254.8F, 23713.7F, 
		  22266.7F, 20908.0F, 19632.2F, 18434.2F, 
		  17309.4F, 16253.1F, 15261.4F, 14330.1F, 
		  13455.7F, 12634.6F, 11863.7F, 11139.7F, 
		  10460.0F, 9821.72F, 9222.39F, 8659.64F, 
		  8131.23F, 7635.06F, 7169.17F, 6731.70F, 
		  6320.93F, 5935.23F, 5573.06F, 5232.99F, 
		  4913.67F, 4613.84F, 4332.30F, 4067.94F, 
		  3819.72F, 3586.64F, 3367.78F, 3162.28F, 
		  2969.31F, 2788.13F, 2617.99F, 2458.24F, 
		  2308.24F, 2167.39F, 2035.14F, 1910.95F, 
		  1794.35F, 1684.85F, 1582.04F, 1485.51F, 
		  1394.86F, 1309.75F, 1229.83F, 1154.78F, 
		  1084.32F, 1018.15F, 956.024F, 897.687F, 
		  842.910F, 791.475F, 743.179F, 697.830F, 
		  655.249F, 615.265F, 577.722F, 542.469F, 
		  509.367F, 478.286F, 449.101F, 421.696F, 
		  395.964F, 371.803F, 349.115F, 327.812F, 
		  307.809F, 289.026F, 271.390F, 254.830F, 
		  239.280F, 224.679F, 210.969F, 198.096F, 
		  186.008F, 174.658F, 164.000F, 153.993F, 
		  144.596F, 135.773F, 127.488F, 119.708F, 
		  112.404F, 105.545F, 99.1046F, 93.0572F, 
		  87.3788F, 82.0469F, 77.0404F, 72.3394F, 
		  67.9252F, 63.7804F, 59.8885F, 56.2341F, 
		  52.8027F, 49.5807F, 46.5553F, 43.7144F, 
		  41.0470F, 38.5423F, 36.1904F, 33.9821F, 
		  31.9085F, 29.9614F, 28.1332F, 26.4165F, 
		  24.8045F, 23.2910F, 21.8697F, 20.5352F, 
		  19.2822F, 18.1056F, 17.0008F, 15.9634F, 
		  14.9893F, 14.0746F, 13.2158F, 12.4094F, 
		  11.6522F, 10.9411F, 10.2735F, 9.64662F, 
		  9.05798F, 8.50526F, 7.98626F, 7.49894F, 
		  7.04135F, 6.61169F, 6.20824F, 5.82941F, 
		  5.47370F, 5.13970F, 4.82607F, 4.53158F, 
		  4.25507F, 3.99542F, 3.75162F, 3.52269F, 
		  3.30774F, 3.10590F, 2.91638F, 2.73842F, 
		  2.57132F, 2.41442F, 2.26709F, 2.12875F, 
		  1.99885F, 1.87688F, 1.76236F, 1.65482F, 
		  1.55384F, 1.45902F, 1.36999F, 1.28640F, 
		  1.20790F, 1.13419F, 1.06499F, 1.F
		};
	
	public static void render_line( int x0, int x1, int y0, int y1, float[] d ) {
		
		int dy=y1-y0;
		int adx=x1-x0;
		int ady=Math.abs(dy);
		int base=dy/adx;
		int sy=(dy<0?base-1:base+1);
		int x=x0;
		int y=y0;
		int err=0;
		
		ady-= Math.abs(base*adx);
		
		d[x]*=FLOOR1_fromdB_LOOKUP[y];
		
		while ( ++x < x1 ) {
			err=err+ady;
			if ( err >= adx ){
				err-=adx;
				y+=sy;
			} else {
				y += base;
			}
			d[x] *= FLOOR1_fromdB_LOOKUP[y];
		}
	}
	
	public static void render_line0( int x0, int x1, int y0, int y1, int[] d ) {
		
		int dy=y1-y0;
		int adx=x1-x0;
		int ady=Math.abs(dy);
		int base=dy/adx;
		int sy=(dy<0?base-1:base+1);
		int x=x0;
		int y=y0;
		int err=0;
		
		ady-=Math.abs(base*adx);
		
		d[x]=y;
		
		while ( ++x < x1 ) {
			err=err+ady;
			if ( err >= adx ) {
				err-=adx;
				y+=sy;
			} else {
				y += base;
			}
			d[x]=y;
		}
	}
}
