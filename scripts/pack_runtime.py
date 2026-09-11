"""Create a versioned, hashed Android runtime from the built ARM64 image."""
import argparse
import hashlib
import json
import lzma
import shutil
import posixpath
import subprocess
import tarfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if not (ROOT/'runtime/CHANGELOG.md').is_file() or not (ROOT/'runtime/CHANGELOG.md').stat().st_size:
    raise ValueError('CHANGELOG.md must be present before packaging')
assets = ROOT / 'android/app/src/main/assets'
archive = ROOT / 'artifacts/runtime-rootfs.tar'
parser = argparse.ArgumentParser()
parser.add_argument('--reuse-rootfs', action='store_true', help='Reuse the local dependency archive when the runtime Docker image has not changed')
args = parser.parse_args()
if args.reuse_rootfs:
    if not archive.is_file():
        parser.error('No cached dependency archive; run without --reuse-rootfs first')
else:
    container = subprocess.check_output(['docker', 'create', 'mower-android-runtime:dev'], text=True).strip()
    try:
        subprocess.run(['docker', 'export', '-o', str(archive), container], check=True)
    finally:
        subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL)

links = {}
output = assets / 'python-runtime.zip.xz'
uncompressed = ROOT / 'artifacts/python-runtime-stored.zip'
with tarfile.open(archive) as tar, zipfile.ZipFile(uncompressed, 'w', compression=zipfile.ZIP_STORED) as zip:
    for member in tar:
        name = member.name.lstrip('./')
        if not name or name in ('etc/hosts', 'etc/resolv.conf') or name.startswith(('dev/', 'proc/', 'sys/', 'usr/share/man/')):
            continue
        if any(part in ('__pycache__', 'tests', 'test', '.pytest_cache') for part in Path(name).parts) or name.endswith(('.pyc', '.pyo')):
            continue
        if name.startswith('usr/local/include/') or name.endswith('/ddddocr/common.onnx'):
            continue
        if name.startswith('usr/share/doc/') and not name.endswith('/copyright'):
            continue
        if member.issym():
            links[name] = member.linkname
        elif member.islnk():
            links[name] = posixpath.relpath(member.linkname, posixpath.dirname(name))
        elif member.isfile():
            zip.writestr(name, tar.extractfile(member).read())
    # Android shares the host network, not Docker's DNS resolver.
    zip.writestr('etc/resolv.conf', 'nameserver 223.5.5.5\nnameserver 1.1.1.1\n')
    zip.writestr('etc/hosts', '127.0.0.1 localhost\n::1 localhost\n')
    for directory in ('mower-data/', 'dev/', 'proc/', 'sys/', 'tmp/', 'root/', 'mower/'):
        zip.writestr(directory, '')
    runtime = ROOT / 'runtime'
    roots = ['arknights_mower', 'mower_android', 'ui/dist', 'server.py', 'LICENSE', 'CHANGELOG.md']
    for item in roots:
        base = runtime / item
        for file in ([base] if base.is_file() else base.rglob('*')):
            if file.is_file() and not any(part in ('__pycache__', '.pytest_cache', 'tests') for part in file.parts):
                zip.write(file, 'mower/' + file.relative_to(runtime).as_posix())
    zip.writestr('mower/arknights_mower/utils/git_revision', json.loads((ROOT / 'UPSTREAM.json').read_text())['mower']['commit'])
    zip.writestr('.symlinks.json', json.dumps(links))
# Publish atomically: an interrupted compressor must not replace a usable asset.
compressed = output.with_suffix(output.suffix + '.tmp')
try:
    if shutil.which('xz'):
        with compressed.open('wb') as destination:
            subprocess.run(['xz', '-T2', '-6', '--stdout', str(uncompressed)], stdout=destination, check=True)
    else:
        with uncompressed.open('rb') as source, lzma.open(compressed, 'wb', preset=6) as destination:
            shutil.copyfileobj(source, destination, 1024*1024)
    compressed.replace(output)
finally:
    compressed.unlink(missing_ok=True)
uncompressed.unlink()
with output.open('rb') as source:
    digest = hashlib.file_digest(source, 'sha256').hexdigest()
(assets / 'python-runtime.sha256').write_text(digest + '\n')
(assets / 'python-runtime.zip').unlink(missing_ok=True)
print(f'{output}: {output.stat().st_size // 1024 // 1024} MiB, SHA256 {digest}')
