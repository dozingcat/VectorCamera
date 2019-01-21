Vector Camera uses the libvpx library to create WebM videos. This repository
includes only the subset of libvpx headers and source needed for the NDK code in
vc_video.c to build. The code needs to link against the full libvpx library,
which is included as a binary for ARM and x86 in jni/libvpx_prebuilt_libs.
These are the steps I used to build Android-compatible libraries from the
libvpx sources.

1. Download a standalone NDK from https://developer.android.com/ndk/downloads/.
r15 was the latest version I was able to use; apparently later versions changed
the directory structure in ways that confuse the libvpx scripts.

2. In a terminal, set the NDK variable to the full path to the NDK directory,
for example "export NDK=/home/foo/android/android-ndk-r15c".

3. In app/src/main/jni, check out the libvpx repository:
"git clone https://chromium.googlesource.com/webm/libvpx". The revision used
for the current libraries is 5cbd333f3b491f6ecb41387acf72e8ec27d8a474; later
versions may or may not work.

Each library is its own special snowflake build process.

## 32-bit ARM

From the libvpx directory:
```
./configure --target=armv7-android-gcc --sdk-path=$NDK \
--disable-examples --disable-docs --disable-tools \
--disable-vp8-decoder --disable-vp9-decoder --enable-vp8-encoder --disable-vp9-encoder \
--disable-neon --disable-neon-asm --disable-runtime-cpu-detect

make
```

If all goes well, this will produce `libvpx.a` in the libvpx directory.
Copy it to app/src/main/cpp/libvpx_prebuilt_libs/armeabi-v7a.
Then run "make distclean" from the libvpx directory.

(It fails if neon isn't disabled. I haven't looked into that deeply).

## 64-bit ARM

This will use the `ndk-build` tool from the NDK. (I couldn't get ndk-build to
work for the 32-bit library, and directly building doesn't work for 64-bit. Fun).

HACK: The libvpx configuration scripts don't seem to be fully aware of the NDK
layout, so the configure script will fail looking for headers. So, we tweak the
configure script to let it pass. In libvpx/build/make/configure.sh, make the
`check_cmd` function do nothing by commenting out the first and third lines, leaving:
```
check_cmd() {
  #enabled external_build && return
  log "$@"
  #"$@" >>${logfile} 2>&1
}
```
Now from the "jni" directory, run:
```
libvpx/configure --target=arm64-android-gcc --sdk-path=$NDK \
--disable-examples --disable-docs --disable-tools \
--disable-vp8-decoder --disable-vp9-decoder --enable-vp8-encoder --disable-vp9-encoder
```
Then cd to the parent directory (app/src/main) and run:
```
$NDK/ndk-build
```
If that works, it will create a `libvpx.a` library in obj/local/arm64-v8a. Copy it to
cpp/libvpx_prebuilt_libs/arm64-v8a.

To clean up, run `rm *.h` from app/src/main and `make distclean` from app/src/main/jni.
Also revert the changes in libvpx/build/make/configure.sh.

Note: specifically for this target, later NDKs appear to work; I was able to use r19.
The library in the repository was still built with r15 for consistency with the others.

## 32-bit x86

This is similar to the 32-bit ARM build, but there's a problem where we can't use the
default NDK runtime. See
http://stackoverflow.com/questions/28010753/android-ndk-returns-an-error-undefined-reference-to-rand

Fun, again. Make sure that any previous builds are cleaned up, and
from the libvpx directory run:
```
export PATH=$NDK/toolchains/x86-4.9/prebuilt/darwin-x86_64/bin:$PATH
```
(This is on macOS, on Linux the directory after "prebuilt" will be something like
"linux-x86_64").
```
ASFLAGS="-D__ANDROID__" CROSS=i686-linux-android- \
LDFLAGS="--sysroot=$NDK/platforms/android-9/arch-x86" ./configure \
--target=x86-android-gcc --sdk-path=$NDK --disable-examples \
--disable-docs --disable-vp8-decoder --disable-vp9-decoder \
--enable-vp8-encoder --disable-vp9-encoder \
--disable-runtime-cpu-detect --disable-mmx --disable-sse --disable-sse2 \
--disable-sse3 --disable-ssse3 --disable-sse4_1 \
--extra-cflags="--sysroot=$NDK/platforms/android-9/arch-x86"

make
```
This should produce a `libvpx.a` file in the libvpx directory.
Copy it to app/src/main/cpp/libvpx_prebuilt_libs/x86.
Then run "make distclean" from the libvpx directory.

(Why do we have to disable mmx and sse? The mysteries of life).

## 64-bit x86

Similar to 32-bit x86. From the libvpx directory:
```
export PATH=$NDK/toolchains/x86_64-4.9/prebuilt/darwin-x86_64/bin:$PATH
```
(Again, "darwin-x86_64" will be something else on Linux).
```
ASFLAGS="-D__ANDROID__" CROSS=x86_64-linux-android- \
LDFLAGS="--sysroot=$NDK/platforms/android-21/arch-x86_64" ./configure \
--target=x86_64-android-gcc --sdk-path=$NDK --disable-examples \
--disable-docs --disable-vp8-decoder --disable-vp9-decoder \
--enable-vp8-encoder --disable-vp9-encoder \
--disable-runtime-cpu-detect --disable-mmx --disable-sse --disable-sse2 \
--disable-sse3 --disable-ssse3 --disable-sse4_1 \
--extra-cflags="--sysroot=$NDK/platforms/android-21/arch-x86_64"

make
```

This should produce a `libvpx.a` file in the libvpx directory.
Copy it to app/src/main/cpp/libvpx_prebuilt_libs/x86_64.
Then run "make distclean" from the libvpx directory.
