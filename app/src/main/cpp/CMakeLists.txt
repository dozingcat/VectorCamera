cmake_minimum_required(VERSION 3.22.1)

project("edge_luminance_native")

# Create the native library
add_library(edge_luminance_native SHARED
    edge_luminance_native.cpp)

# Link against the log library (for Android logging)
find_library(log-lib log)
target_link_libraries(edge_luminance_native ${log-lib}) 