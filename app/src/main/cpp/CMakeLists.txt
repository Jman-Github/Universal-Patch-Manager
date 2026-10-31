cmake_minimum_required(VERSION 3.22.1)
project("upm_jvm")

# Code adapted from Morphe, see third-party/NOTICE for more information.
# https://github.com/MorpheApp/morphe-manager/pull/1202
add_library(jvm_launcher SHARED jvm_launcher.cpp)
target_link_libraries(jvm_launcher log dl)

# The JVM runs in the manager process (Morphe PR #1223).
target_compile_features(jvm_launcher PRIVATE cxx_std_17)

add_library(prop_override SHARED prop_override.cpp)
target_link_libraries(prop_override dl)
