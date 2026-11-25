cmake_minimum_required(VERSION 3.16)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_CXX_FLAGS "-DANDROID -g")
set(CMAKE_C_FLAGS "-w -std=c11 -DANDROID -D_LARGEFILE_SOURCE=1 -g -Wno-error=implicit-function-declaration")
set(CMAKE_ASM_FLAGS "${CFLAGS} -x assembler-with-cpp")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -flto=full")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden -flto=full")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto=full -Wl,--gc-sections -Wl,--icf=safe -Wl,-Bsymbolic")

if (${ANDROID_ABI} STREQUAL "armeabi-v7a" OR ${ANDROID_ABI} STREQUAL "arm64-v8a")
    enable_language(ASM)
else()
    enable_language(ASM_NASM)
endif()

set(CAN_COMPILE_SME FALSE CACHE INTERNAL "Disable libyuv SME" FORCE)
add_subdirectory(
        third_party/libyuv
        ${CMAKE_CURRENT_BINARY_DIR}/libyuv
        EXCLUDE_FROM_ALL)



add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libavutil.a)

add_library(avformat STATIC IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libavformat.a)

add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libavcodec.a)

add_library(swresample STATIC IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libswresample.a)

add_library(swscale STATIC IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libswscale.a)

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libcrypto.a)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libssl.a)


#aesige
#add_library(aesige STATIC openssl/aes_ige.c)
#target_compile_options(aesige PUBLIC -Wall -frtti -finline-functions -ffast-math -Oz)
#target_include_directories(aesige PUBLIC third_party/boringssl/include openssl/include)
#target_link_libraries(aesige crypto)


add_library(libvpx STATIC IMPORTED)
set_target_properties(libvpx PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libvpx.a)

add_library(libdav1d STATIC IMPORTED)
set_target_properties(libdav1d PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libdav1d.a)

add_library(libopus STATIC IMPORTED)
set_target_properties(libopus PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libopus.a)


add_library(tdutils STATIC IMPORTED)
set_target_properties(tdutils PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libtdutils.a)

add_subdirectory(
        prisma
        ${CMAKE_CURRENT_BINARY_DIR}/prisma
        EXCLUDE_FROM_ALL)

#tgnet
add_library(tgnet STATIC
        openssl/aes_ige.c
        tgnet/ApiScheme.cpp
        tgnet/BuffersStorage.cpp
        tgnet/ByteArray.cpp
        tgnet/ByteStream.cpp
        tgnet/Connection.cpp
        tgnet/ConnectionSession.cpp
        tgnet/ConnectionsManager.cpp
        tgnet/TLSHello.cpp
        tgnet/ConnectionSocket.cpp
        tgnet/Datacenter.cpp
        tgnet/EventObject.cpp
        tgnet/FileLog.cpp
        tgnet/MTProtoScheme.cpp
        tgnet/NativeByteBuffer.cpp
        tgnet/Request.cpp
        tgnet/Timer.cpp
        tgnet/TLObject.cpp
        tgnet/ProxyCheckInfo.cpp
        tgnet/Handshake.cpp
        tgnet/Config.cpp)
target_compile_options(tgnet PUBLIC
        -Wall -frtti -finline-functions -ffast-math -Oz)
set_target_properties(tgnet PROPERTIES
        ANDROID_ARM_MODE arm)
target_compile_definitions(tgnet PUBLIC
        HAVE_PTHREAD)
target_include_directories(tgnet PUBLIC
        third_party/boringssl/include
        openssl/include)
target_link_libraries(tgnet
        crypto)

# tlottie (prebuilt Rust static library)
add_library(tlottie STATIC IMPORTED)
set_target_properties(tlottie PROPERTIES
        IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libtlottie.a)


#sqlite
add_library(sqlite STATIC
        sqlite/sqlite3.c)
target_compile_options(sqlite PUBLIC
        -fno-strict-aliasing -Oz)
set_target_properties(sqlite PROPERTIES
        ANDROID_ARM_MODE arm)
target_compile_definitions(sqlite PUBLIC
        NULL=0 SOCKLEN_T=socklen_t LOCALE_NOT_USED ANDROID_NDK DISABLE_IMPORTGL AVOID_TABLES ANDROID_TILE_BASED_DECODE HAVE_STRCHRNUL=0 ANDROID_ARMV6_IDCT)

#breakpad
add_library(breakpad STATIC
        third_party/breakpad/src/client/linux/crash_generation/crash_generation_client.cc
        third_party/breakpad/src/client/linux/handler/exception_handler.cc
        third_party/breakpad/src/client/linux/handler/minidump_descriptor.cc
        third_party/breakpad/src/client/linux/log/log.cc
        third_party/breakpad/src/client/linux/dump_writer_common/thread_info.cc
        third_party/breakpad/src/client/linux/dump_writer_common/seccomp_unwinder.cc
        third_party/breakpad/src/client/linux/dump_writer_common/ucontext_reader.cc
        third_party/breakpad/src/client/linux/microdump_writer/microdump_writer.cc
        third_party/breakpad/src/client/linux/minidump_writer/linux_dumper.cc
        third_party/breakpad/src/client/linux/minidump_writer/linux_ptrace_dumper.cc
        third_party/breakpad/src/client/linux/minidump_writer/minidump_writer.cc
        third_party/breakpad/src/client/minidump_file_writer.cc
        third_party/breakpad/src/common/android/breakpad_getcontext.S
        third_party/breakpad/src/common/convert_UTF.c
        third_party/breakpad/src/common/md5.cc
        third_party/breakpad/src/common/string_conversion.cc
        third_party/breakpad/src/common/linux/elfutils.cc
        third_party/breakpad/src/common/linux/file_id.cc
        third_party/breakpad/src/common/linux/guid_creator.cc
        third_party/breakpad/src/common/linux/linux_libc_support.cc
        third_party/breakpad/src/common/linux/memory_mapped_file.cc
        third_party/breakpad/src/common/linux/safe_readlink.cc)
set_target_properties(breakpad PROPERTIES ANDROID_ARM_MODE arm)
set_property(SOURCE third_party/breakpad/src/common/android/breakpad_getcontext.S PROPERTY LANGUAGE C)
# rnnoise (voice message noise suppression in audio.c)
add_library(rnnoise STATIC
        voip/rnnoise/src/celt_lpc.c
        voip/rnnoise/src/denoise.c
        voip/rnnoise/src/kiss_fft.c
        voip/rnnoise/src/pitch.c
        voip/rnnoise/src/rnn_data.c
        voip/rnnoise/src/rnn_reader.c
        voip/rnnoise/src/rnn.c)
target_compile_options(rnnoise PRIVATE
        -Wall -finline-functions -fno-strict-aliasing -O3 -Wno-unknown-pragmas -funroll-loops -fno-math-errno)
set_target_properties(rnnoise PROPERTIES ANDROID_ARM_MODE arm)
target_compile_definitions(rnnoise PRIVATE
        HAVE_PTHREAD __STDC_LIMIT_MACROS BSD=1 USE_KISS_FFT NULL=0 SOCKLEN_T=socklen_t LOCALE_NOT_USED _LARGEFILE_SOURCE=1 _FILE_OFFSET_BITS=64 restrict= __EMX__ OPUS_BUILD USE_ALLOCA HAVE_LRINT HAVE_LRINTF
        _celt_autocorr=rnnoise__celt_autocorr
        celt_fir=rnnoise_celt_fir
        celt_iir=rnnoise_celt_iir
        _celt_lpc=rnnoise__celt_lpc
        celt_pitch_xcorr=rnnoise_celt_pitch_xcorr
        compute_band_corr=rnnoise_compute_band_corr
        compute_band_energy=rnnoise_compute_band_energy
        compute_dense=rnnoise_compute_dense
        compute_gru=rnnoise_compute_gru
        compute_rnn=rnnoise_compute_rnn
        interp_band_gain=rnnoise_interp_band_gain
        opus_fft_alloc=rnnoise_opus_fft_alloc
        opus_fft_alloc_arch_c=rnnoise_opus_fft_alloc_arch_c
        opus_fft_alloc_twiddles=rnnoise_opus_fft_alloc_twiddles
        opus_fft_c=rnnoise_opus_fft_c
        opus_fft_free=rnnoise_opus_fft_free
        opus_fft_free_arch_c=rnnoise_opus_fft_free_arch_c
        opus_fft_impl=rnnoise_opus_fft_impl
        opus_ifft_c=rnnoise_opus_ifft_c
        pitch_downsample=rnnoise_pitch_downsample
        pitch_filter=rnnoise_pitch_filter
        pitch_search=rnnoise_pitch_search
        remove_doubling=rnnoise_remove_doubling)
target_include_directories(rnnoise PUBLIC voip/rnnoise/include)

target_include_directories(breakpad PUBLIC
        third_party/breakpad/src/common/android/include
        third_party/breakpad/src)



set(NATIVE_LIB "tmessages.49")

#tmessages
add_library(${NATIVE_LIB} SHARED
        jni.c
        audio.c
        webm_encoder.c
        image.cpp
        intro/IntroRenderer.c
        SqliteWrapper.cpp
        utilities.cpp
        gifvideo.cpp
        lottie.cpp
        TgNetWrapper.cpp
        ffmpeg_jni.cc
        fast-edge.cpp
        genann.c
        secureid_ocr.cpp
        tests.cpp
)

# HTML widgets are libtwidget.so, apart from the client library: the isolated engine process loads only it
# (WidgetEngineService), the client never does. What the client needs of the same code (the gesture policy, the CPU
# filter pass; no widget code reaches either) is compiled into the client library (twidget_client_sources).
include(twidget/twidget.cmake)
add_library(twidget SHARED twidget/twidget-onload.cpp twidget/twidget-seccomp.cpp)
twidget_target_sources(twidget)
twidget_client_sources(${NATIVE_LIB})
target_link_options(twidget PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(twidget PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/twidget/exports.map")

# Compile JNI directly into the shared library so archive extraction cannot drop its exports.
set(PRISMA_BRIDGE_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/prisma/bridge/PrismaEngine.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/prisma/bridge/PrismaJni.cpp")
target_sources(${NATIVE_LIB} PRIVATE ${PRISMA_BRIDGE_SOURCES})
target_include_directories(${NATIVE_LIB} PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/prisma/bridge")
set_property(SOURCE ${PRISMA_BRIDGE_SOURCES} APPEND PROPERTY
        COMPILE_OPTIONS -fexceptions -frtti)
set_property(SOURCE ${PRISMA_BRIDGE_SOURCES} APPEND PROPERTY
        COMPILE_DEFINITIONS BOOST_REGEX_STANDALONE)

target_compile_options(${NATIVE_LIB} PUBLIC
        -ffast-math -Oz -funroll-loops -ffast-math -fno-strict-aliasing -fno-math-errno)

target_compile_definitions(${NATIVE_LIB} PUBLIC
        BSD=1 NULL=0 SOCKLEN_T=socklen_t ANDROID_NDK DISABLE_IMPORTGL AVOID_TABLES ANDROID_TILE_BASED_DECODE __STDC_CONSTANT_MACROS ANDROID_ARMV6_IDCT OPUS_BUILD FIXED_POINT USE_ALLOCA restrict= __EMX__ LOCALE_NOT_USED HAVE_LRINT HAVE_LRINTF)

target_sources(${NATIVE_LIB} PRIVATE

        third_party/xiph/opusfile/src/info.c
        third_party/xiph/opusfile/src/internal.c
        third_party/xiph/opusfile/src/opusfile.c
        third_party/xiph/opusfile/src/stream.c
        third_party/xiph/ogg/src/bitwise.c
        third_party/xiph/ogg/src/framing.c)

target_include_directories(${NATIVE_LIB} PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party
        third_party/xiph/opus/include
        third_party/xiph/opus/silk
        third_party/xiph/opus/silk/fixed
        third_party/xiph/opus/celt
        third_party/xiph/opus
        third_party/xiph/opusfile/include
        third_party/xiph/ogg/include
        third_party/libyuv/include
        third_party/boringssl/include
        openssl/include
        prebuild/include/${ANDROID_ABI}
        third_party/ffmpeg
        third_party/libvpx
        third_party/dav1d/include
        emoji
        exoplayer/include
        intro
        tlottie/include
)

target_link_options(${NATIVE_LIB} PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(${NATIVE_LIB} PRIVATE "-Wl,-z,common-page-size=16384")
target_link_options(${NATIVE_LIB} PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
target_link_libraries(${NATIVE_LIB}
            rnnoise
            tgnet
        prisma
        tlottie
        sqlite
        swscale
        avformat
        avcodec
        swresample
        libvpx
        libdav1d
        libopus
        tdutils
        yuv
        avutil
        ssl
        crypto
        jnigraphics
        log
        z
        GLESv2
        EGL
        android
        OpenSLES
        cpufeatures
        breakpad)

#if (${ANDROID_ABI} STREQUAL "x86" OR ${ANDROID_ABI} STREQUAL "x86_64")
#    target_link_libraries(${NATIVE_LIB}
#            -Wl,--whole-archive vpxasm -Wl,--no-whole-archive
#            c)
#endif()

include(AndroidNdkModules)
android_ndk_import_module_cpufeatures()
