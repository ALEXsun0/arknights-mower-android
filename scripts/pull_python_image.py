"""Download the public ARM64 Python image using the host's proxy settings."""
import hashlib
import io
import json
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'artifacts'
BASE = 'https://registry-1.docker.io/v2/library/python/'
TOKEN = json.load(urllib.request.urlopen('https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/python:pull', timeout=30))['token']


def fetch(path, accept=None):
    headers = {'Authorization': f'Bearer {TOKEN}'}
    if accept:
        headers['Accept'] = accept
    with urllib.request.urlopen(urllib.request.Request(BASE + path, headers=headers), timeout=90) as response:
        return response.read()


accept = 'application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json'
index = json.loads(fetch('manifests/3.12-slim-bookworm', accept))
entry = next(m for m in index['manifests'] if m['platform']['architecture'] == 'arm64' and m['platform']['os'] == 'linux')
manifest = json.loads(fetch('manifests/' + entry['digest'], accept))
entries = []
for item in [manifest['config']] + manifest['layers']:
    digest = item['digest'].split(':')[1]
    target = ROOT / digest
    if not target.exists():
        print('Downloading', digest, item['size'], flush=True)
        data = fetch('blobs/' + item['digest'])
        assert hashlib.sha256(data).hexdigest() == digest
        target.write_bytes(data)
    entries.append(target)
with tarfile.open(ROOT / 'python-image.tar', 'w') as tar:
    tar.add(entries[0], arcname='config.json')
    for i, file in enumerate(entries[1:]):
        tar.add(file, arcname=f'layer-{i}.tar.gz')
    data = json.dumps([{'Config': 'config.json', 'RepoTags': ['python:3.12-slim-bookworm'], 'Layers': [f'layer-{i}.tar.gz' for i in range(len(entries)-1)]}]).encode()
    info = tarfile.TarInfo('manifest.json'); info.size = len(data)
    tar.addfile(info, io.BytesIO(data))
print(ROOT / 'python-image.tar', flush=True)
