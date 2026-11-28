cmake_minimum_required(VERSION 3.22.1)
project("upm_jvm")

# Code adapted from Morphe, see third-party/NOTICE for more information.
# https://github.com/MorpheApp/morphe-manager/pull/1474
add_library(jvm_launcher SHARED jvm_launcher.c)
target_compile_features(jvm_launcher PRIVATE c_std_11)

add_library(prop_override SHARED prop_override.cpp)
target_link_libraries(prop_override dl)
