#!/usr/bin/env bash
# Cross-compiles a minimal, LGPL-only, audio-only FFmpeg 7.1 static build for arm64-v8a
# using the Android NDK r27c unified LLVM toolchain. Run manually; not invoked by Gradle.
set -euo pipefail

NDK="${NDK:-$HOME/Android/Sdk/ndk/27.2.12479018}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=26
TARGET=aarch64-linux-android

FFMPEG_SRC="${FFMPEG_SRC:?Set FFMPEG_SRC to the extracted ffmpeg-7.1 source directory}"
PREFIX="${PREFIX:-$HOME/ffmpeg-android-out/arm64-v8a}"

export CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export NM="$TOOLCHAIN/bin/llvm-nm"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"

mkdir -p "$PREFIX"
cd "$FFMPEG_SRC"

./configure \
  --prefix="$PREFIX" \
  --target-os=android --arch=aarch64 --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC" --cxx="$CXX" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --extra-cflags="-O2 -fPIC -fvisibility=hidden" \
  --extra-ldflags="-static-libgcc" \
  --pkg-config=/bin/false \
  --disable-autodetect \
  --enable-static --disable-shared --enable-pic \
  --disable-programs --disable-doc --disable-debug \
  --disable-avdevice --disable-swscale --disable-postproc \
  --disable-network --disable-protocols \
  --disable-everything \
  --enable-avformat --enable-avcodec --enable-avutil --enable-swresample \
  --enable-avfilter \
  --enable-filter=abuffer,abuffersink,aformat,aresample,anull,superequalizer,equalizer,firequalizer,anequalizer,bass,treble,volume,crossfeed,acompressor,alimiter,dynaudnorm,loudnorm,atempo,aecho,stereotools,extrastereo,apulsator \
  --enable-decoder=mp3,mp3float,aac,aac_latm,flac,vorbis,opus,alac,wmav1,wmav2,wmapro,wmalossless,wmavoice,pcm_s16le,pcm_s16be,pcm_u8,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f64le,pcm_alaw,pcm_mulaw \
  --enable-demuxer=mov,matroska,avi,asf,ogg,flac,mp3,wav,aiff,caf,aac \
  --enable-parser=aac,flac,mpegaudio,vorbis,opus \
  --disable-bsfs \
  --disable-gpl --disable-nonfree

make -j2 install

echo "=== Build complete. Verifying outputs ==="
for lib in avformat avcodec avutil swresample avfilter; do
  f="$PREFIX/lib/lib${lib}.a"
  if [ -f "$f" ]; then
    echo "OK: $f ($(du -h "$f" | cut -f1))"
  else
    echo "MISSING: $f" >&2
    exit 1
  fi
done
