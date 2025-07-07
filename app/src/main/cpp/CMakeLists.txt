cmake_minimum_required(VERSION 3.22.1)

project("edge_luminance_native")

# Set optimization flags
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffast-math -funroll-loops")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")

# Create the native library
add_library(edge_luminance_native SHARED
    edge_luminance_native.cpp)

# Set C++17 standard
set_property(TARGET edge_luminance_native PROPERTY CXX_STANDARD 17)

# Add optimization and architecture-specific flags
target_compile_options(edge_luminance_native PRIVATE
    -O3
    -ffast-math
    -funroll-loops
    -fomit-frame-pointer
)

# Link against the log library (for Android logging)
find_library(log-lib log)
target_link_libraries(edge_luminance_native ${log-lib}) 