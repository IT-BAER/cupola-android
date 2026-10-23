# Host tests of the KTX2 reader and the atmosphere LUTs (no Android, no Vulkan). From the repo root:
#   cmake -S core/render/src/test/cpp -B build/host-tests -G Ninja && cmake --build build/host-tests
#   ctest --test-dir build/host-tests --output-on-failure
cmake_minimum_required(VERSION 3.22.1)
project(cupola_render_host_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(RENDER_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")
# Built textures (local/ is not in git; the real_files case skips without them).
set(CUPOLA_REAL_TEXTURE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../local/textures/out" CACHE PATH
    "Folder with astc/ and etc2/ KTX2 outputs of build_textures.ps1")

add_executable(ktx2_reader_test ktx2_reader_test.cpp "${RENDER_CPP}/ktx2_reader.cpp")
target_include_directories(ktx2_reader_test PRIVATE "${RENDER_CPP}")
target_compile_definitions(ktx2_reader_test PRIVATE
    CUPOLA_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/fixtures"
    CUPOLA_REAL_TEXTURE_DIR="${CUPOLA_REAL_TEXTURE_DIR}")
if(MSVC)
    target_compile_options(ktx2_reader_test PRIVATE /W4 /WX /utf-8)
else()
    target_compile_options(ktx2_reader_test PRIVATE -Wall -Wextra -Werror)
endif()

enable_testing()
set(CASES
    fixture_astc_6x6_srgb_2d
    fixture_astc_8x8_unorm_array3
    fixture_etc2_rgb_srgb_2d
    fixture_etc2_rgba_unorm_2d
    etc2_rgba_srgb
    negative_truncated
    negative_header
    negative_levels
    negative_index
    real_files)
foreach(CASE ${CASES})
    add_test(NAME ktx2.${CASE} COMMAND ktx2_reader_test ${CASE})
    set_tests_properties(ktx2.${CASE} PROPERTIES SKIP_RETURN_CODE 77)
endforeach()

add_executable(atmosphere_lut_test atmosphere_lut_test.cpp "${RENDER_CPP}/atmosphere_lut.cpp")
target_include_directories(atmosphere_lut_test PRIVATE "${RENDER_CPP}")
if(MSVC)
    target_compile_options(atmosphere_lut_test PRIVATE /W4 /WX /utf-8)
else()
    target_compile_options(atmosphere_lut_test PRIVATE -Wall -Wextra -Werror -O2)
endif()
foreach(CASE sizes_and_finite zenith_transmittance horizon_reddens multi_scatter sky_irradiance)
    add_test(NAME atmosphere.${CASE} COMMAND atmosphere_lut_test ${CASE})
endforeach()
