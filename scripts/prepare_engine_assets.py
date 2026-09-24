"""Verify and extract the vendored PRoot packages."""
import hashlib
import io
import json
import tarfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
lock = json.loads(Path(__file__).with_name('engine-assets.lock.json').read_text())
packages = root / 'scripts/termux-packages'
native = root / 'android/app/src/main/jniLibs/arm64-v8a'
native.mkdir(parents=True, exist_ok=True)


names = {'proot': 'libproot.so', 'loader': 'libproot-loader.so', 'libtalloc.so.2.4.3': 'libtalloc.so', 'libandroid-shmem.so': 'libandroid-shmem.so'}
for package in lock['termux']:
    source = packages / Path(package['Filename']).name
    raw = source.read_bytes()
    if hashlib.sha256(raw).hexdigest() != package['SHA256']:
        raise ValueError(f'Checksum mismatch: {source}')
    offset = 8
    while offset < len(raw):
        header = raw[offset:offset+60]
        length = int(header[48:58]); name = header[:16].decode().strip().rstrip('/')
        offset += 60
        if name.startswith('data.tar'):
            with tarfile.open(fileobj=io.BytesIO(raw[offset:offset+length])) as tar:
                for member in tar:
                    filename = Path(member.name).name
                    if member.isfile() and filename in names:
                        (native / names[filename]).write_bytes(tar.extractfile(member).read())
        offset += length + length % 2
print('Verified and prepared ARM64 PRoot assets')
