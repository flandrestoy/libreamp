#!/usr/bin/env bash
# Links ffmpeg_bridge.c against the prebuilt static FFmpeg libs into
# libffmpeg_player.so for arm64-v8a. Run manually after build_ffmpeg.sh
# and whenever ffmpeg_bridge.c changes; not invoked by Gradle.
set -euo pipefail

NDK="${NDK:-$HOME/Android/Sdk/ndk/27.2.12479018}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=26
TARGET=aarch64-linux-android
CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"

PREFIX="${PREFIX:-$HOME/ffmpeg-android-out/arm64-v8a}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/../../app/src/main/jniLibs/arm64-v8a}"

mkdir -p "$OUT_DIR"

"$CC" -shared -fPIC \
  -o "$OUT_DIR/libffmpeg_player.so" \
  "$SCRIPT_DIR/ffmpeg_bridge.c" \
  -I"$PREFIX/include" -L"$PREFIX/lib" \
  -Wl,--gc-sections -ffunction-sections -fdata-sections \
  -Wl,--build-id=sha1 -Wl,-soname,libffmpeg_player.so \
  -lavfilter -lavformat -lavcodec -lswresample -lavutil \
  -llog -lm -s

echo "OK: $OUT_DIR/libffmpeg_player.so"
file "$OUT_DIR/libffmpeg_player.so" || true
