cmake_minimum_required(VERSION 3.22.1)

project("vectorcamera_native")

# Set optimization flags
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffast-math -funroll-loops")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")

# Create the unified native library
add_library(vectorcamera_native SHARED
    edge_luminance_native.cpp
    edge_effect_native.cpp)

# Set C++17 standard
set_property(TARGET vectorcamera_native PROPERTY CXX_STANDARD 17)

# Add optimization and architecture-specific flags
target_compile_options(vectorcamera_native PRIVATE
    -O3
    -ffast-math
    -funroll-loops
    -fomit-frame-pointer
)

# Link against the log library (for Android logging)
find_library(log-lib log)
target_link_libraries(vectorcamera_native ${log-lib})

# For backwards compatibility, create an alias
add_library(edge_luminance_native ALIAS vectorcamera_native) 