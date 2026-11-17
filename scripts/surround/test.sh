#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p .build/tests
javac -d .build/tests \
  app/src/main/java/com/limelight/binding/audio/{PcmFrameAccumulator,Ac3WriteLoop}.java \
  ac3-tests/{FrameAccumulatorTest,WriteLoopTest}.java
java -cp .build/tests FrameAccumulatorTest
java -cp .build/tests WriteLoopTest
cc -std=c11 -Wall -Wextra -Werror ac3-tests/channels_test.c -lm -o .build/tests/channels-test
.build/tests/channels-test
