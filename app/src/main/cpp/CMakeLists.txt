cmake_minimum_required(VERSION 3.18.1)
project(systls C)

# JNI shim over the device's own OpenSSL; see systls.c. Links nothing but libdl.
add_library(systls SHARED systls.c)
target_compile_options(systls PRIVATE -Os -Wall -Wextra -Wno-unused-parameter)
# 16 KiB pages on newer 64-bit devices; harmless elsewhere.
target_link_options(systls PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(systls dl)
