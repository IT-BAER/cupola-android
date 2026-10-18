cmake_minimum_required(VERSION 3.22.1)
project(cupola_render LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

file(GLOB GLSLC_CANDIDATES "${ANDROID_NDK}/shader-tools/*/glslc${CMAKE_HOST_EXECUTABLE_SUFFIX}")
if(NOT GLSLC_CANDIDATES)
    message(FATAL_ERROR "glslc not found under ${ANDROID_NDK}/shader-tools")
endif()
list(GET GLSLC_CANDIDATES 0 GLSLC)

set(SHADER_OUT "${CMAKE_CURRENT_BINARY_DIR}/shaders")
file(MAKE_DIRECTORY "${SHADER_OUT}")
set(SHADER_INCS)
foreach(SHADER fullscreen.vert pattern.frag)
    set(SRC "${CMAKE_CURRENT_SOURCE_DIR}/shaders/${SHADER}")
    set(INC "${SHADER_OUT}/${SHADER}.inc")
    add_custom_command(
        OUTPUT "${INC}"
        COMMAND "${GLSLC}" --target-env=vulkan1.1 -O -Werror -mfmt=c -o "${INC}" "${SRC}"
        DEPENDS "${SRC}"
        COMMENT "glslc ${SHADER}"
        VERBATIM)
    list(APPEND SHADER_INCS "${INC}")
endforeach()
add_custom_target(cupola_shaders DEPENDS ${SHADER_INCS})

add_library(cupola_render SHARED
    device.cpp
    jni_bridge.cpp
    offscreen.cpp
    surface_target.cpp)
add_dependencies(cupola_render cupola_shaders)
target_include_directories(cupola_render PRIVATE "${SHADER_OUT}")
target_compile_options(cupola_render PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers -fno-exceptions -fno-rtti)
target_compile_definitions(cupola_render PRIVATE $<IF:$<CONFIG:Debug>,CUPOLA_DEBUG=1,CUPOLA_DEBUG=0>)
target_link_options(cupola_render PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
target_link_libraries(cupola_render PRIVATE vulkan android log)
