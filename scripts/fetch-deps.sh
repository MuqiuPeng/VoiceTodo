#!/usr/bin/env bash
# Downloads the sherpa-onnx AAR (speech runtime), which is kept out of git.
# The speech model itself is not part of the build: the app downloads it as
# a speech pack (Settings → 语音包) from this repo's speech-* pre-release.
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION=1.13.8
mkdir -p app/libs
[ -s app/libs/sherpa-onnx-$SHERPA_VERSION.aar ] || curl -fL -o app/libs/sherpa-onnx-$SHERPA_VERSION.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar
echo "done"
