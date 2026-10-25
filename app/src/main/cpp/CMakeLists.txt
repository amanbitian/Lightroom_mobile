cmake_minimum_required(VERSION 3.22.1)
project(lumaroom_raw LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
include(ExternalProject)
set(JPEG_BINARY ${CMAKE_CURRENT_BINARY_DIR}/jpeg-build)
ExternalProject_Add(jpeg_build
    SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/jpeg
    BINARY_DIR ${JPEG_BINARY}
    CMAKE_ARGS -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE} -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM} -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF -DWITH_SIMD=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target jpeg-static
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS ${JPEG_BINARY}/libjpeg.a)
add_library(jpeg_native STATIC IMPORTED)
set_target_properties(jpeg_native PROPERTIES IMPORTED_LOCATION ${JPEG_BINARY}/libjpeg.a)
add_dependencies(jpeg_native jpeg_build)
file(GLOB_RECURSE RAW_SOURCES CONFIGURE_DEPENDS vendor/libraw/src/*.cpp)
list(FILTER RAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")
file(GLOB LCMS_SOURCES CONFIGURE_DEPENDS vendor/lcms/src/*.c)
add_library(lumaroom_raw SHARED raw_bridge.cpp ${RAW_SOURCES} ${LCMS_SOURCES})
target_include_directories(lumaroom_raw PRIVATE vendor/libraw vendor/libraw/internal vendor/lcms/include vendor/jpeg/src ${JPEG_BINARY})
target_compile_definitions(lumaroom_raw PRIVATE LIBRAW_NOTHREADS USE_LCMS2 USE_JPEG USE_ZLIB)
target_compile_options(lumaroom_raw PRIVATE -O2 -fexceptions)
target_link_options(lumaroom_raw PRIVATE -Wl,-z,max-page-size=16384)
add_dependencies(lumaroom_raw jpeg_build)
target_link_libraries(lumaroom_raw jpeg_native android jnigraphics log z)
