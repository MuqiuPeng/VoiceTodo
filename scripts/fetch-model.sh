#!/usr/bin/env bash
# Downloads the binaries kept out of git: the sherpa-onnx AAR (speech
# runtime) and the SenseVoice int8 model (~240 MB) bundled as app assets.
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION=1.13.8
MODEL=https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main

mkdir -p app/libs app/src/main/assets/sense-voice
[ -s app/libs/sherpa-onnx-$SHERPA_VERSION.aar ] || curl -fL -o app/libs/sherpa-onnx-$SHERPA_VERSION.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar
[ -s app/src/main/assets/sense-voice/tokens.txt ] || curl -fL -o app/src/main/assets/sense-voice/tokens.txt $MODEL/tokens.txt
[ -s app/src/main/assets/sense-voice/model.int8.onnx ] || curl -fL -o app/src/main/assets/sense-voice/model.int8.onnx $MODEL/model.int8.onnx
echo "done"
