"""Emit the compatibility descriptor consumed by Mower's upstream Release CI."""
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'runtime'))
from mower_android.mower_package import RUNTIME_API


def main():
    output = ROOT/'artifacts'
    gradle = (ROOT/'android/app/build.gradle.kts').read_text()
    version = re.search(r'versionName = "([^"]+)"', gradle)[1]
    code = int(re.search(r'versionCode = (\d+)', gradle)[1])
    apk = output/'mower-android-arm64.apk'
    if not apk.is_file(): raise ValueError('Build the APK first')
    adapters = sorted(output.glob('mower-maa-python-*.zip'))
    if len(adapters) != 1: raise ValueError('Expected exactly one Python compatibility package')
    with zipfile.ZipFile(adapters[0]) as z: adapter = json.loads(z.read('maa-python.json'))
    if adapter['min_apk'] > code or adapter['bridge_protocol'] != 1:
        raise ValueError('Python adapter is incompatible with this APK')
    metadata = {
        'format': 1, 'runtime_api': RUNTIME_API,
        'apk': {'name':apk.name, 'version':version, 'version_code':code},
        'maa_python': {'name':adapters[0].name, 'version':adapter['version']},
    }
    (output/'android-release.json').write_text(json.dumps(metadata, indent=2)+'\n')
    print('Android release descriptor:', version, 'runtime API', RUNTIME_API)


if __name__ == '__main__': main()
