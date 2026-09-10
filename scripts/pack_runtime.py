"""Create a versioned, hashed Android runtime from the built ARM64 image."""
import hashlib
import json
import posixpath
import subprocess
import tarfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
assets = ROOT / 'android/app/src/main/assets'
archive = ROOT / 'artifacts/runtime-rootfs.tar'
container = subprocess.check_output(['docker', 'create', 'mower-android-runtime:dev'], text=True).strip()
try:
    subprocess.run(['docker', 'export', '-o', str(archive), container], check=True)
finally:
    subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL)

links = {}
output = assets / 'python-runtime.zip'
with tarfile.open(archive) as tar, zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zip:
    for member in tar:
        name = member.name.lstrip('./')
        if not name or name in ('etc/hosts', 'etc/resolv.conf') or name.startswith(('dev/', 'proc/', 'sys/', 'usr/share/man/')):
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
    zip.writestr('mower/arknights_mower/git_revision', json.loads((ROOT / 'UPSTREAM.json').read_text())['mower']['commit'])
    zip.writestr('.symlinks.json', json.dumps(links))
digest = hashlib.sha256(output.read_bytes()).hexdigest()
(assets / 'python-runtime.sha256').write_text(digest + '\n')
print(f'{output}: {output.stat().st_size // 1024 // 1024} MiB, SHA256 {digest}')
