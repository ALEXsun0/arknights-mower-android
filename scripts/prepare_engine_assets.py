"""Fetch pinned PRoot assets; verify all downloads before extraction."""
import hashlib
import io
import json
import tarfile
import urllib.request
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
cache = root / 'artifacts'
cache.mkdir(exist_ok=True)
lock = json.loads(Path(__file__).with_name('engine-assets.lock.json').read_text())
native = root / 'android/app/src/main/jniLibs/arm64-v8a'
native.mkdir(parents=True, exist_ok=True)


def download(url, digest):
    target = cache / url.rsplit('/', 1)[1]
    if not target.exists():
        with urllib.request.urlopen(url, timeout=90) as response:
            target.write_bytes(response.read())
    data = target.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f'Checksum mismatch: {target}; remove it and retry')
    return data


names = {'proot': 'libproot.so', 'loader': 'libproot-loader.so', 'libtalloc.so.2.4.3': 'libtalloc.so', 'libandroid-shmem.so': 'libandroid-shmem.so'}
for package in lock['termux']:
    raw = download('https://packages.termux.dev/apt/termux-main/' + package['Filename'], package['SHA256'])
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
