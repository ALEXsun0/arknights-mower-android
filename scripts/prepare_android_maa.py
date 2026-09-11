"""Pin and package the official Android ARM64 component; no Meow APK is involved."""
import argparse
import hashlib
import json
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(); parser.add_argument('--archive', type=Path); args = parser.parse_args()
lock = json.loads(Path(__file__).with_name('maa-android.lock.json').read_text())
archive = args.archive or root / 'artifacts' / lock['url'].rsplit('/', 1)[1]
archive.parent.mkdir(exist_ok=True, parents=True)
if not archive.exists():
    with urllib.request.urlopen(lock['url'], timeout=90) as response, archive.open('wb') as output: shutil.copyfileobj(response, output)
if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['sha256']: raise ValueError('Official component checksum mismatch')
assets = root / 'android/app/src/main/assets'; assets.mkdir(parents=True, exist_ok=True)
stage = root / 'artifacts' / ('maa-android-' + lock['sha256'][:12])
if not (stage / '.mower-android.json').exists():
    shutil.rmtree(stage, ignore_errors=True); stage.mkdir()
    with tarfile.open(archive) as tar:
        for item in tar:
            name = Path(item.name).as_posix().removeprefix('./')
            if item.isfile() or item.islnk():
                if Path(name).is_absolute() or '..' in Path(name).parts: raise ValueError('Invalid component path')
                dest = stage / name; dest.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(item) as source, dest.open('wb') as output: shutil.copyfileobj(source, output)
    (stage / '.mower-android.json').write_text(json.dumps({'version':lock['version'], 'asset_sha256':lock['sha256'], 'platform':'android', 'arch':'arm64'}))
subprocess.run(['docker', 'run', '--rm', '--platform', 'linux/arm64', '-v', f'{root}/runtime:/project:ro',
                '-v', f'{stage}:/component', '-v', f'{assets}:/output', '-e', 'PYTHONPATH=/project',
                'mower-android-runtime:dev', 'python', '-c',
                "from mower_android.managed import pack_component; pack_component('/component', '/output/maa-component.zip')"], check=True)
print('Verified and packaged official Android ARM64 MAA ' + lock['version'])
