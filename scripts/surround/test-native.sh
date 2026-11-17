#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?}"
root_dir="$PWD"
rm -rf -- .build/ffmpeg-host .build/dolby-host
mkdir -p .build/ffmpeg-host .build/tests .build/dolby-host
(
  cd .build/ffmpeg-host
  ../ffmpeg-7.1.1/configure --prefix="$root_dir/.build/dolby-host" \
    --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network \
    --disable-avformat --disable-avdevice --disable-avfilter --disable-swresample --disable-swscale \
    --disable-shared --enable-static --enable-pic --enable-small --disable-x86asm \
    --enable-encoder=ac3,eac3 --extra-cflags=-O3 --pkg-config=/bin/false
  make -j"${BUILD_JOBS:-2}"
  make install
)
cc -std=c11 -O2 -fPIC -shared -Wall -Wextra -Werror -Wno-unused-parameter \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I.build/dolby-host/include \
  app/src/main/jni/ac3/encoder.c .build/dolby-host/lib/libavcodec.a \
  .build/dolby-host/lib/libavutil.a -lm -pthread -o .build/tests/libmoonlight-ac3.so
javac -d .build/tests app/src/main/java/com/limelight/binding/audio/Ac3Encoder.java ac3-tests/NativeEncoderTest.java
java -Djava.library.path=.build/tests -cp .build/tests NativeEncoderTest .build/tests
python3 ac3-tests/verify_bitstream.py .build/tests/jni.ac3 ac3
python3 ac3-tests/verify_bitstream.py .build/tests/jni.eac3 eac3
