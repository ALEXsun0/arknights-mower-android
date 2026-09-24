"""Identify changes that require an APK; bundled Mower/MAA and the hot adapter are excluded."""
import hashlib
import re
from pathlib import Path


def host_digest(root, requirements=None):
    root=Path(root); files=[]
    for directory in ('android/app/src/main','android/hidden-api/src','runtime/mower_android'):
        for p in (root/directory).rglob('*'):
            if not p.is_file() or '__pycache__' in p.parts or 'assets' in p.parts or 'jniLibs' in p.parts: continue
            if directory=='runtime/mower_android' and (p.name=='maa_adapter.py' or p.suffix!='.py'): continue
            files.append(p)
    files += [root/p for p in ('android/build.gradle.kts','android/app/build.gradle.kts','android/hidden-api/build.gradle.kts','scripts/runtime.Dockerfile','scripts/engine-assets.lock.json','runtime/requirements.in')]
    files += list((root/'scripts/termux-packages').glob('*.deb'))
    digest=hashlib.sha256()
    for p in sorted(set(files)):
        data=p.read_bytes()
        if p.name=='build.gradle.kts': data=re.sub(rb'version(?:Code|Name) = [^\n]+',b'version = <distribution>',data)
        if p==root/'runtime/requirements.in' and requirements is not None: data=requirements
        digest.update(str(p.relative_to(root)).encode()+b'\0'+data+b'\0')
    return digest.hexdigest()
