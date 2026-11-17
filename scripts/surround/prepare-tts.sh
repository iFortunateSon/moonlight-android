#!/usr/bin/env bash
# Prepare build-time speech tools; nothing in this cache is packaged in the APK.
set -euo pipefail
umask 077
cd "$(dirname "$0")/../.."
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || {
  echo 'Neural fixture generation requires Linux x86_64.' >&2
  exit 1
}
workspace="$PWD/.build/tts"
mkdir -p "$workspace/downloads" "$workspace/models"
fetch() {
  local url=$1 path=$2 digest=$3 temporary
  if [[ -f "$path" ]]; then
    printf '%s  %s\n' "$digest" "$path" | sha256sum -c -
    return
  fi
  temporary=$(mktemp "$path.download.XXXXXX")
  if ! curl --proto '=https' --tlsv1.2 -fsSL --retry 2 --retry-all-errors \
      --connect-timeout 20 --max-time 600 "$url" -o "$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  if ! printf '%s  %s\n' "$digest" "$temporary" | sha256sum -c -; then
    rm -f -- "$temporary"
    return 1
  fi
  mv -- "$temporary" "$path"
}
revision=e9ab671f2a0e50a701b34361136ba3e6a3a9eb0c
base="https://huggingface.co/rhasspy/piper-voices/resolve/$revision/en/en_US/ljspeech/high"
fetch "$base/en_US-ljspeech-high.onnx" "$workspace/models/en_US-ljspeech-high.onnx" \
  5d4f08ba6a2a48c44592eed3ce56bf85e9de3dd4e20df90541ae68a8310c029a
fetch "$base/en_US-ljspeech-high.onnx.json" "$workspace/models/en_US-ljspeech-high.onnx.json" \
  7e1f4634af596d83cca997fb7a931ba80b70f8a316a2655ee69c55365e0ace14
fetch "$base/MODEL_CARD" "$workspace/models/MODEL_CARD" \
  289f7421072d689a3d91f6c632f486af46f9b8b04417536104a4ea667b8a394b
wheel=piper_tts-1.8.0-cp39-abi3-manylinux_2_17_x86_64.manylinux2014_x86_64.manylinux_2_28_x86_64.whl
fetch "https://files.pythonhosted.org/packages/84/81/0112a7d510911f33018dc24023d1655bb772f84a4e95fe7f0180f66bbd17/$wheel" \
  "$workspace/downloads/$wheel" 25b4d3f31ff70c8fa7151908e00aaa5650cbdf16bca8fcf21299f3941b89a7d3
if [[ ! -x "$workspace/venv/bin/python" ]]; then
  python3 -m venv "$workspace/venv"
fi
"$workspace/venv/bin/python" -m pip --isolated install --index-url https://pypi.org/simple \
  --only-binary=:all: --no-cache-dir "$workspace/downloads/$wheel"
"$workspace/venv/bin/python" - <<'PY'
import importlib.metadata as metadata
import onnxruntime
from piper import PiperVoice
assert metadata.version('piper-tts') == '1.8.0'
print('PASS: build-time Piper 1.8.0 with CPU ONNX Runtime', onnxruntime.__version__)
PY
