# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.4.1)

# Prebuilt libvpx static library.
add_library(
    vpx
    STATIC
    IMPORTED)
set_target_properties(
    vpx
    PROPERTIES IMPORTED_LOCATION
    ${PROJECT_SOURCE_DIR}/src/main/cpp/libvpx_prebuilt_libs/${ANDROID_ABI}/libvpx.a)

# Library with functions to write WebM files.
add_library(
    webmenc
    STATIC
    src/main/cpp/libvpx/webmenc.cc
    src/main/cpp/libvpx/third_party/libwebm/mkvmuxer.cpp
    src/main/cpp/libvpx/third_party/libwebm/mkvmuxerutil.cpp
    src/main/cpp/libvpx/third_party/libwebm/mkvparser.cpp
    src/main/cpp/libvpx/third_party/libwebm/mkvreader.cpp
    src/main/cpp/libvpx/third_party/libwebm/mkvwriter.cpp)

# Main shared library loaded by the app.
add_library(
    vectorcamera
    SHARED
    src/main/cpp/bc_video.c )

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries(
    # Specifies the target library.
    vectorcamera
    # Libraries to link.
    vpx
    webmenc)
