cmake_minimum_required(VERSION 3.22.1)

Project(Pluvia)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -s")

add_subdirectory(extras/adrenotools)

# JNI for the X server drawing (Drawable), SysV shared memory and the Vulkan API-version probe.
add_library(winlator SHARED
            winlator/drawable.c
            winlator/sysvshared_memory.c
            winlator/vulkan_version.c)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      dl)

target_link_options(winlator PRIVATE -Wl,-z,max-page-size=16384)


add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
    COMMAND glslangValidator -V --vn window_vert_code -x
            -o ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
            ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.vert
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.vert
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
    COMMAND glslangValidator -V --vn window_frag_code -x
            -o ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
            ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.frag
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.frag
)

add_library(vulkan_renderer SHARED
            winlator/vulkan_jni.cpp
            winlator/VulkanRendererContext.cpp
            winlator/VulkanRendererScanout.cpp
            ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
            ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h)

target_include_directories(vulkan_renderer PRIVATE
                           ${CMAKE_CURRENT_BINARY_DIR}
                           ${CMAKE_CURRENT_SOURCE_DIR}/winlator)

set_target_properties(vulkan_renderer PROPERTIES
                      CXX_STANDARD 17
                      CXX_STANDARD_REQUIRED ON)

target_compile_options(vulkan_renderer PRIVATE
                       -Wall -Wextra -fvisibility=hidden)

target_link_libraries(vulkan_renderer
                      log
                      android
                      vulkan
                      adrenotools
                      dl
                      atomic)

target_link_options(vulkan_renderer PRIVATE -Wl,-z,max-page-size=16384)

# Rebuild the packaged log-control JNI library when using the native toolchain.
add_library(wow_logging_control SHARED logging_control.c)
target_link_libraries(wow_logging_control dl)
target_link_options(wow_logging_control PRIVATE -Wl,-z,max-page-size=16384)
