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

public class vorbis_info_mapping0 {

	int submaps;		// <= 16
	int[] chmuxlist;	// up to 256 channels in a Vorbis stream
  
	int[] floorsubmap;	// [mux] submap to floors 16
	int[] residuesubmap;	// [mux] submap to residue 16

	int coupling_steps;
	int[] coupling_mag;	// 256
	int[] coupling_ang;	// 256


	public vorbis_info_mapping0( int _submaps, int[] _chmuxlist, int[] _floorsubmap, int[] _residuesubmap, int _coupling_steps, int[] _coupling_mag, int[] _coupling_ang ) {

		submaps = _submaps;

		chmuxlist = new int[ 256 ];
		System.arraycopy( _chmuxlist, 0, chmuxlist, 0, _chmuxlist.length );

		floorsubmap = new int[ 16 ];
		System.arraycopy( _floorsubmap, 0, floorsubmap, 0, _floorsubmap.length );

		residuesubmap = new int[ 16 ];
		System.arraycopy( _residuesubmap, 0, residuesubmap, 0, _residuesubmap.length );

		coupling_steps = _coupling_steps;

		coupling_mag = new int[ 256 ];
		System.arraycopy( _coupling_mag, 0, coupling_mag, 0, _coupling_mag.length );

		coupling_ang = new int[ 256 ];
		System.arraycopy( _coupling_ang, 0, coupling_ang, 0, _coupling_ang.length );
	}

	public vorbis_info_mapping0( vorbis_info_mapping0 src ) {

		this( src.submaps, src.chmuxlist, src.floorsubmap, src.residuesubmap, src.coupling_steps, src.coupling_mag, src.coupling_ang );
	}
}