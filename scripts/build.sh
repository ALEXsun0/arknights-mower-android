#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts
python3 scripts/prepare_distribution.py plan
python3 scripts/prepare_distribution.py apply
python3 scripts/prepare_engine_assets.py
if python3 -c 'import json; raise SystemExit(json.load(open("artifacts/distribution.json"))["bundled"]["mower"]["source"] != "bundled")'; then
  (cd runtime/ui && npm ci --no-audit --no-fund && npm run build)
fi
python3 scripts/check_webui_build.py
docker build --platform linux/arm64 -f scripts/runtime.Dockerfile -t mower-android-runtime:dev .
python3 scripts/prepare_android_maa.py
python3 scripts/verify_maa_adapter.py
python3 scripts/package_maa_python.py
python3 scripts/pack_runtime.py
docker run --rm --platform linux/arm64 -v "$PWD:/project" -w /project mower-android-runtime:dev python -m unittest discover -s tests -v
bash android/gradlew -p android :app:assembleDebug --console=plain
cp android/app/build/outputs/apk/debug/app-debug.apk artifacts/mower-android-arm64-debug.apk
