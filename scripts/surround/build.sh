#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
: "${ANDROID_HOME:?Set ANDROID_HOME to SDK 34 with NDK 23.2.8568313}"
export PATH="$JAVA_HOME/bin:$PATH"
export AC3_FFMPEG_PREFIX="$PWD/.build/dolby-arm"
mkdir -p .build/reports
bash scripts/surround/test.sh | tee .build/reports/unit-tests.txt
bash scripts/surround/build-ffmpeg.sh
bash scripts/surround/test-native.sh > .build/reports/native-tests.txt 2>&1 || {
  tail -n 60 .build/reports/native-tests.txt
  exit 1
}
tail -n 16 .build/reports/native-tests.txt
python3 scripts/surround/generate-fixtures.py "$PWD"
./gradlew assembleNonRootDebug assembleNonRootRelease lintNonRootDebug lintNonRootRelease \
  --no-daemon --max-workers="${BUILD_JOBS:-2}" \
  '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m' --console plain
python3 scripts/surround/verify-apk.py app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk debug
python3 scripts/surround/verify-apk.py app/build/outputs/apk/nonRoot/release/app-nonRoot-release-unsigned.apk release
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
sha256sum app/build/outputs/apk/nonRoot/{debug/app-nonRoot-debug.apk,release/app-nonRoot-release-unsigned.apk}
