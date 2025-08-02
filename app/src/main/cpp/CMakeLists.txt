cmake_minimum_required(VERSION 3.22.1)

project("vectorcamera_native")

# Set optimization flags
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffast-math -funroll-loops")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")

# Create the unified native library
add_library(vectorcamera_native SHARED
    edge_luminance_native.cpp
    edge_effect_native.cpp
    permute_color_effect_native.cpp
    cartoon_effect_native.cpp
    convolve3x3_effect_native.cpp
    matrix_effect_native.cpp
    ascii_effect.cpp)

# Set C++17 standard
set_property(TARGET vectorcamera_native PROPERTY CXX_STANDARD 17)

# Add aggressive optimization and architecture-specific flags
target_compile_options(vectorcamera_native PRIVATE
    -O3
    -ffast-math
    -funroll-loops
    -fomit-frame-pointer
    -ftree-vectorize
    -fno-signed-zeros
    -fno-trapping-math
    -fassociative-math
    -freciprocal-math
)

# Add ARM-specific optimizations when targeting ARM
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(vectorcamera_native PRIVATE -mcpu=cortex-a53)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(vectorcamera_native PRIVATE -mcpu=cortex-a9 -mfpu=neon)
endif()

# Link against the log library (for Android logging)
find_library(log-lib log)
target_link_libraries(vectorcamera_native ${log-lib})
