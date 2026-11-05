"""Offline integration test: real official archive, download hashing, NCNN packing and swap.

Run inside the disposable Docker runtime with an archive mounted at /archive.tar.gz.
Only release discovery and network transport are replaced with local fixtures.
"""
import argparse
import dataclasses
import hashlib
import json
import tempfile
from pathlib import Path
from unittest.mock import patch

from arknights_mower.utils import maa_update as updater
from mower_android import managed

parser = argparse.ArgumentParser()
parser.add_argument('--archive', type=Path, default=Path('/archive.tar.gz'))
archive = parser.parse_args().archive
lock = json.loads(Path(__file__).with_name('maa-android.lock.json').read_text())
version, checksum = lock['version'], lock['sha256']
assert hashlib.sha256(archive.read_bytes()).hexdigest() == checksum
payload = {'tag_name':version,'assets':[{'name':lock['url'].rsplit('/', 1)[1],
           'browser_download_url':'https://example.invalid/official.tar.gz','size':archive.stat().st_size,'digest':'sha256:'+checksum}]}
release = updater.parse_release(payload, system='android', machine='arm64')
class Response:
    headers = {'Content-Length':str(archive.stat().st_size)}
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def raise_for_status(self): pass
    def iter_content(self, size):
        with archive.open('rb') as file:
            while chunk := file.read(size): yield chunk
class Session:
    def get(self, *args, **kwargs): return Response()
with tempfile.TemporaryDirectory() as work:
    target = Path(work)/'maa'; target.mkdir(); component = Path(work)/'maa-component.zip'
    (target/'.mower-android.json').write_text('{"version":"v0.0.0"}')
    (target/'config.json').write_text('{"preserved":true}')
    adapter = Path(managed.__file__).with_name('maa.py'); before = hashlib.sha256(adapter.read_bytes()).hexdigest()
    with patch.object(managed, 'MAA_PATH', target), patch.object(managed,'COMPONENT',component), patch.object(updater,'get_latest_release',return_value=release):
        result = managed.install_update(target, session=Session())
        assert result['restart_required'] and managed.installed_version(target) == version
        assert (target/'resource/PaddleOCR/rec/rec.ncnn.param').is_file()
        assert json.loads((target/'config.json').read_text())['preserved']
        assert (Path(str(target)+'.old')/'.mower-android.json').is_file()
        assert hashlib.sha256(component.read_bytes()).hexdigest() == component.with_suffix('.sha256').read_text().strip()
        assert hashlib.sha256(adapter.read_bytes()).hexdigest() == before
        component_before = hashlib.sha256(component.read_bytes()).hexdigest()
        bad = dataclasses.replace(release, runtime=dataclasses.replace(release.runtime,sha256='0'*64))
        with patch.object(updater,'get_latest_release',return_value=bad):
            try: managed.install_update(target,session=Session())
            except updater.MaaUpdateError: pass
            else: raise AssertionError('Bad digest accepted')
        assert hashlib.sha256(component.read_bytes()).hexdigest() == component_before
        assert managed.installed_version(target) == version
        print(json.dumps({'official_archive_sha256':checksum,'installed_version':result['version'],'ncnn_models':True,
                          'python_adapter_preserved':True,'user_config_preserved':True,'backup_created':True,
                          'checksum_failure_preserves_installation':True,'restart_required':True}, indent=2))
