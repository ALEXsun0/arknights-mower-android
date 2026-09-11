#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts
python3 scripts/prepare_engine_assets.py
(cd runtime/ui && npm ci --no-audit --no-fund && npm run build)
python3 scripts/check_webui_build.py
docker build --platform linux/arm64 -f scripts/runtime.Dockerfile -t mower-android-runtime:dev .
python3 scripts/prepare_android_maa.py
python3 scripts/pack_runtime.py
docker run --rm --platform linux/arm64 -v "$PWD:/project" -w /project mower-android-runtime:dev python -m unittest discover -s tests -v
bash android/gradlew -p android :app:assembleDebug --console=plain
cp android/app/build/outputs/apk/debug/app-debug.apk artifacts/mower-android-arm64-debug.apk
