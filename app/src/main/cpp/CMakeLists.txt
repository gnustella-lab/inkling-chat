cmake_minimum_required(VERSION 3.22.1)
project(llama-android LANGUAGES CXX C)

include(FetchContent)

set(LLAMA_CPP_TAG "master" CACHE STRING "llama.cpp git ref to build against")

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
    GIT_TAG ${LLAMA_CPP_TAG}
    GIT_SHALLOW TRUE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(llama)

add_library(llama-android-jni SHARED llama-android-jni.cpp)

target_link_libraries(llama-android-jni
    PRIVATE
    llama
    android
    log
)

target_compile_features(llama-android-jni PRIVATE cxx_std_17)
