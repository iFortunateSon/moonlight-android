#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
ndk_bin="$ANDROID_HOME/ndk/23.2.8568313/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$ndk_bin/armv7a-linux-androideabi21-clang" ]]
mkdir -p .build/downloads .build/dolby-arm
archive="$PWD/.build/downloads/ffmpeg-7.1.1.tar.xz"
if [[ ! -f "$archive" ]]; then
  curl --proto '=https' --tlsv1.2 -fL --retry 2 \
    https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz -o "$archive.partial"
  mv "$archive.partial" "$archive"
fi
echo "733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1  $archive" | sha256sum -c -
# These are generated build directories, never user source or backups.
# A fresh extraction prevents an edited source tree from diverging from the bundle.
rm -rf -- .build/ffmpeg-7.1.1 .build/ffmpeg-arm .build/dolby-arm
mkdir -p .build/dolby-arm .build/ffmpeg-arm
tar -xf "$archive" -C .build
prefix="$PWD/.build/dolby-arm"
cd .build/ffmpeg-arm
../ffmpeg-7.1.1/configure --prefix="$prefix" --target-os=android --arch=arm --cpu=armv7-a \
  --enable-cross-compile --cc="$ndk_bin/armv7a-linux-androideabi21-clang" \
  --cxx="$ndk_bin/armv7a-linux-androideabi21-clang++" --ar="$ndk_bin/llvm-ar" \
  --ranlib="$ndk_bin/llvm-ranlib" --strip="$ndk_bin/llvm-strip" --nm="$ndk_bin/llvm-nm" \
  --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network \
  --disable-avformat --disable-avdevice --disable-avfilter --disable-swresample --disable-swscale \
  --disable-shared --enable-static --enable-pic --enable-small --enable-encoder=ac3,eac3 \
  --extra-cflags='-O3 -mfpu=neon' --pkg-config=/bin/false
make -j"${BUILD_JOBS:-2}"
make install
