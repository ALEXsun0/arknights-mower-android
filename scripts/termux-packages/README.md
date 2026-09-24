These ARM64 Termux packages are stored in the repository so Android builds do
not depend on older package versions remaining available on the Termux mirror.

The original repository paths, versions, and SHA-256 digests are recorded in
`../engine-assets.lock.json`. `prepare_engine_assets.py` checks each package
against that lock before extracting the native binaries.

Upstream package source: https://packages.termux.dev/apt/termux-main/
