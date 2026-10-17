cmake_minimum_required(VERSION 3.18)
project(freeenterprise CXX C)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Snes9x 1.63 core + libretro glue (vendored from DEV/SNES9X/snes9x).
set(CORE ${CMAKE_CURRENT_SOURCE_DIR})

include_directories(
    ${CORE}
    ${CORE}/apu
    ${CORE}/common
    ${CORE}/libretro
    ${CORE}/libretro/libretro-common/include
)

file(GLOB CORE_SOURCES CONFIGURE_DEPENDS
    ${CORE}/*.cpp
    ${CORE}/apu/*.cpp
    ${CORE}/apu/bapu/dsp/*.cpp
    ${CORE}/apu/bapu/smp/*.cpp
    ${CORE}/common/*.cpp
    ${CORE}/*.c
)

add_library(retro SHARED
    ${CORE_SOURCES}
    ${CORE}/libretro/libretro.cpp
    ${CORE}/filter/snes_ntsc.c
)

target_compile_options(retro PRIVATE
    -DANDROID
    -D__LIBRETRO__
    -DHAVE_STRINGS_H
    -DRIGHTSHIFT_IS_SAR
    -O2
    -fvisibility=hidden
    -Wno-narrowing
)

# Export only the libretro API surface.
set_target_properties(retro PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

# ---------------------------------------------------------------- app bridge
add_library(ff4emu SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/ff4emu.cpp
)
target_link_libraries(ff4emu PRIVATE retro android log)
