import hashlib
import io
import lzma
import json
import sys
import tempfile
import types
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch

from werkzeug.datastructures import FileStorage

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android import app_update, mower_ota, mower_package


class MowerOtaTests(unittest.TestCase):
    def test_beta_check_reads_full_and_ota_from_one_mower_release_index(self):
        target = 'v4.1.6-alpha.8'
        full_name = 'arknights-mower_4.1.6-alpha.8_android_arm64.zip'
        ota_name = 'arknights-mower-ota_4.1.6-alpha.7_to_4.1.6-alpha.8_android_arm64.zip'
        base_url = f'https://github.com/{app_update.OTA_REPO}/releases/download/{target}/'
        index = {
            'schema': 1, 'version': target,
            'source_release': f'https://github.com/{app_update.REPO}/releases/tag/{target}',
            'notes': 'Release notes',
            'full_assets': [{'name': full_name, 'size': 200,
                             'digest': 'sha256:' + 'a'*64, 'url': base_url + full_name}],
            'ota_assets': [{'name': ota_name, 'size': 100,
                            'digest': 'sha256:' + 'b'*64, 'url': base_url + ota_name}],
        }
        response = Mock()
        response.json.return_value = index
        ident = 'b'*64
        folder = self.root / 'mower-programs'
        (folder / ident).mkdir(parents=True)
        (folder / ident / 'mower-android.json').write_text('{}')
        package = types.ModuleType('arknights_mower')
        package.__version__ = '4.1.6-alpha.7'
        utils = types.ModuleType('arknights_mower.utils')
        software = types.ModuleType('arknights_mower.utils.software_update')
        software.version_key = lambda value: int(value.rsplit('.', 1)[1])
        software.choose_release = Mock()
        with (patch.dict(sys.modules, {'arknights_mower': package,
                                       'arknights_mower.utils': utils,
                                       'arknights_mower.utils.software_update': software}),
              patch.object(app_update.mower_package, 'folder', return_value=folder),
              patch.object(app_update.mower_package, 'state', return_value={
                  'id': ident, 'version': '4.1.6-alpha.7'}),
              patch.object(app_update.requests, 'get', return_value=response) as get):
            result = app_update.check('beta')
        get.assert_called_once_with(f'{app_update.OTA_INDEX_URL}/beta.json', timeout=30)
        plan = app_update._plans.pop(result['check_id'])
        self.assertEqual(plan['asset']['browser_download_url'], base_url + full_name)
        self.assertEqual(plan['ota']['asset']['browser_download_url'], base_url + ota_name)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.base = self.root / 'base'
        (self.base / 'mower').mkdir(parents=True)
        (self.base / 'mower-android.json').write_bytes(b'old manifest')
        (self.base / 'python-runtime.zip.xz').write_bytes(b'unchanged runtime')
        (self.base / 'mower/server.py').write_bytes(b'old code')

    def delta(self, *, path='mower/server.py', bad_runtime=False):
        def info(value):
            return {'type': 'file', 'sha256': hashlib.sha256(value).hexdigest(), 'mode': 0o644}
        files = {
            'mower-android.json': info(b'new manifest'),
            'python-runtime.zip.xz': info(b'wrong' if bad_runtime else b'unchanged runtime'),
            path: info(b'new code'),
        }
        manifest = {'kind': 'mower-ota', 'format': 1, 'from': '4.1.6-alpha.7',
                    'to': '4.1.6-alpha.8', 'platform': 'android', 'arch': 'arm64',
                    'files': files, 'changed': ['mower-android.json', path]}
        output = self.root / 'ota.zip'
        with zipfile.ZipFile(output, 'w') as archive:
            archive.writestr('ota.json', json.dumps(manifest))
            archive.writestr('payload/mower-android.json', b'new manifest')
            archive.writestr('payload/' + path, b'new code')
        return output

    def test_reconstructs_full_package_without_changing_base(self):
        output = self.root / 'complete.zip'
        mower_ota.reconstruct(self.delta(), self.base, output,
                              from_version='4.1.6-alpha.7', to_version='v4.1.6-alpha.8')
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(archive.read('python-runtime.zip.xz'), b'unchanged runtime')
            self.assertEqual(archive.read('mower/server.py'), b'new code')
            self.assertEqual(archive.read('mower-android.json'), b'new manifest')
        self.assertEqual((self.base / 'mower/server.py').read_bytes(), b'old code')

    def test_rejects_modified_base_and_traversal(self):
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            mower_ota.reconstruct(self.delta(bad_runtime=True), self.base,
                                  self.root / 'complete.zip', from_version='4.1.6-alpha.7',
                                  to_version='v4.1.6-alpha.8')
        with self.assertRaisesRegex(ValueError, '非法文件路径'):
            mower_ota.reconstruct(self.delta(path='mower/../escape.py'), self.base,
                                  self.root / 'complete.zip', from_version='4.1.6-alpha.7',
                                  to_version='v4.1.6-alpha.8')
        self.assertFalse((self.root / 'escape.py').exists())

    def test_selects_only_matching_online_base(self):
        name = 'arknights-mower-ota_4.1.6-alpha.7_to_4.1.6-alpha.8_android_arm64.zip'
        url = f'https://github.com/{app_update.OTA_REPO}/releases/download/v4.1.6-alpha.8/{name}'
        release = {'tag_name': 'v4.1.6-alpha.8', 'draft': False,
                   'assets': [{'name': name, 'size': 100, 'digest': 'sha256:' + 'a'*64,
                               'browser_download_url': url}]}
        response = Mock()
        response.json.return_value = release
        folder = self.root / 'mower-programs'
        ident = 'b' * 64
        (folder / ident).mkdir(parents=True)
        (folder / ident / 'mower-android.json').write_text('{}')
        with (patch.object(app_update.mower_package, 'folder', return_value=folder),
              patch.object(app_update.mower_package, 'state', return_value={
                  'id': ident, 'version': '4.1.6-alpha.7'}),
              patch.object(app_update.requests, 'get', return_value=response)):
            selected = app_update.choose_ota_asset('v4.1.6-alpha.8', '4.1.6-alpha.7', 200)
            self.assertEqual(selected['base_id'], ident)
        with patch.object(app_update.mower_package, 'state', return_value={}):
            self.assertIsNone(app_update.choose_ota_asset('v4.1.6-alpha.8',
                                                           '4.1.6-alpha.7', 200))

    def test_failed_ota_installs_full_mower_package(self):
        base_id = 'b' * 64
        full = b'full package'
        full_sha = hashlib.sha256(full).hexdigest()
        ota_asset = {'name': 'ota.zip', 'digest': 'sha256:' + 'a' * 64}
        full_asset = {'name': 'full.zip', 'digest': 'sha256:' + full_sha}
        check_id = 'test-fallback'
        app_update._plans[check_id] = {
            'asset': full_asset, 'version': 'v4.1.6-alpha.8',
            'ota': {'asset': ota_asset, 'base_id': base_id,
                    'from_version': '4.1.6-alpha.7'},
        }
        self.addCleanup(app_update._plans.pop, check_id, None)
        downloaded = []

        def download(asset, label):
            path = self.root / asset['name']
            path.write_bytes(b'ota package' if asset is ota_asset else full)
            downloaded.append(label)
            return path

        def thread(*, target, daemon):
            return Mock(start=target)

        with (patch.object(app_update, 'folder', return_value=self.root),
              patch.object(app_update.mower_package, 'state',
                           return_value={'id': base_id, 'pending': False}),
              patch.object(app_update.mower_package, 'folder', return_value=self.root),
              patch.object(app_update, 'download_asset', side_effect=download),
              patch.object(app_update.mower_ota, 'reconstruct',
                           side_effect=ValueError('base mismatch')),
              patch.object(app_update.mower_package, 'install',
                           return_value={'message': 'installed'}) as install,
              patch.object(app_update.threading, 'Thread', side_effect=thread)):
            app_update.submit(check_id)

        self.assertEqual(downloaded, ['Mower OTA', 'Mower'])
        install.assert_called_once_with(self.root / 'full.zip')
        self.assertEqual(app_update.status()['status'], 'succeeded')
        self.assertFalse((self.root / 'ota.zip').exists())
        self.assertFalse((self.root / 'full.zip').exists())


    @staticmethod
    def runtime(data):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            archive.writestr('usr/', b'')
            archive.writestr('usr/local/bin/python3.12', b'python binary')
            archive.writestr('usr/lib/changed.so', data)
            archive.writestr('.symlinks.json', b'{}')
        return lzma.compress(output.getvalue(), preset=6)


    def test_rebuilds_changed_python_runtime_from_inner_files(self):
        old_runtime = self.runtime(b'old dependency')
        official_runtime = self.runtime(b'new dependency')
        (self.base / 'python-runtime.zip.xz').write_bytes(old_runtime)
        old_zip = zipfile.ZipFile(io.BytesIO(lzma.decompress(old_runtime)))
        target_zip = zipfile.ZipFile(io.BytesIO(lzma.decompress(official_runtime)))

        def item(data):
            return {'type': 'file', 'sha256': hashlib.sha256(data).hexdigest(), 'mode': 0o644}

        files = {
            'mower-android.json': None,
            'python-runtime.zip.xz': item(official_runtime),
            'mower/server.py': item(b"print('new code')"),
            'mower/arknights_mower/__init__.py': item(b'__version__ = "4.1.6-alpha.8"'),
            'mower/ui/dist/index.html': item(b'ui'),
            'mower/requirements.txt': item(b'requirements'),
        }
        meta = {
            'kind': 'mower-android', 'format': 2, 'min_apk': 29,
            'version': '4.1.6-alpha.8', 'revision': 'a'*40, 'runtime_api': 1,
            'python': '3.12', 'platform': 'android', 'arch': 'arm64',
            'runtime': {'file': 'python-runtime.zip.xz',
                        'sha256': hashlib.sha256(official_runtime).hexdigest(),
                        'unpacked_size': sum(i.file_size for i in target_zip.infolist())},
        }
        official_meta = json.dumps(meta).encode()
        files['mower-android.json'] = item(official_meta)
        runtime_files = {}
        for entry in target_zip.infolist():
            if entry.is_dir():
                runtime_files[entry.filename] = {'type': 'dir', 'mode': 0o644}
            else:
                data = target_zip.read(entry)
                runtime_files[entry.filename] = {**item(data), 'size': len(data)}
        manifest = {
            'kind': 'mower-ota', 'format': 2, 'from': '4.1.6-alpha.7',
            'to': '4.1.6-alpha.8', 'platform': 'android', 'arch': 'arm64',
            'files': files,
            'changed': [name for name in files if name != 'python-runtime.zip.xz'],
            'runtime': {'files': runtime_files, 'changed': ['usr/lib/changed.so']},
        }
        delta = self.root / 'runtime-ota.zip'
        with zipfile.ZipFile(delta, 'w') as archive:
            archive.writestr('ota.json', json.dumps(manifest))
            for name in manifest['changed']:
                payload = official_meta if name == 'mower-android.json' else (
                    b"print('new code')" if name == 'mower/server.py' else
                    b'__version__ = "4.1.6-alpha.8"' if name.endswith('__init__.py') else
                    b'ui' if name.endswith('index.html') else b'requirements')
                archive.writestr('payload/' + name, payload)
            archive.writestr('runtime/usr/lib/changed.so', b'new dependency')
        complete = self.root / 'rebuilt.zip'
        mower_ota.reconstruct(delta, self.base, complete,
                              from_version='4.1.6-alpha.7', to_version='v4.1.6-alpha.8')
        with zipfile.ZipFile(complete) as archive:
            rebuilt_runtime = archive.read('python-runtime.zip.xz')
            rebuilt_meta = json.loads(archive.read('mower-android.json'))
        self.assertEqual(rebuilt_meta['runtime']['sha256'], hashlib.sha256(rebuilt_runtime).hexdigest())
        with zipfile.ZipFile(io.BytesIO(lzma.decompress(rebuilt_runtime))) as runtime:
            self.assertEqual(runtime.read('usr/lib/changed.so'), b'new dependency')
            self.assertEqual(runtime.read('usr/local/bin/python3.12'), old_zip.read('usr/local/bin/python3.12'))
        self.assertEqual(mower_package.inspect(complete, apk_code=29)['version'], '4.1.6-alpha.8')
        (self.base / 'python-runtime.zip.xz').write_bytes(b'corrupted')
        failed = self.root / 'failed.zip'
        with self.assertRaises((ValueError, lzma.LZMAError)):
            mower_ota.reconstruct(delta, self.base, failed,
                                  from_version='4.1.6-alpha.7', to_version='v4.1.6-alpha.8')
        self.assertFalse(failed.exists())


    def test_development_channel_uses_one_index_for_full_and_ota(self):
        import arknights_mower
        tag = 'v4.1.6-alpha.9.g40ac54e4'
        current = '4.1.6-alpha.9'
        full_name = f'arknights-mower_{tag[1:]}_android_arm64.zip'
        ota_name = f'arknights-mower-ota_{current}_to_{tag[1:]}_android_arm64.zip'
        asset_url = f'https://github.com/{app_update.OTA_REPO}/releases/download/{tag}/'
        index = {
            'schema': 1, 'version': tag,
            'source_release': f'https://github.com/{app_update.OTA_REPO}/releases/tag/{tag}',
            'full_assets': [{'name': full_name, 'size': 1000, 'digest': 'sha256:'+'a'*64,
                             'url': asset_url + full_name}],
            'ota_assets': [{'name': ota_name, 'size': 100, 'digest': 'sha256:'+'b'*64,
                            'url': asset_url + ota_name}],
        }
        response = Mock()
        response.json.return_value = index
        ident = 'b' * 64
        base = self.root / 'programs'
        (base / ident).mkdir(parents=True)
        (base / ident / 'mower-android.json').write_text('{}')
        with (patch.object(arknights_mower, '__version__', current),
              patch.object(app_update.requests, 'get', return_value=response) as request,
              patch.object(app_update.mower_package, 'folder', return_value=base),
              patch.object(app_update.mower_package, 'state', return_value={
                  'id': ident, 'version': current, 'pending': False})):
            result = app_update.check('dev')
        self.assertTrue(result['available'])
        self.assertEqual(result['version'], tag)
        self.assertEqual(app_update._plans[result['check_id']]['ota']['asset']['name'], ota_name)
        self.assertEqual([call.args[0] for call in request.call_args_list], [f'{app_update.OTA_INDEX_URL}/dev.json'])
        app_update._plans.pop(result['check_id'], None)


    def test_manual_ota_reconstructs_offline(self):
        import arknights_mower
        ident = 'b' * 64
        programs = self.root / 'programs'
        (programs / ident).mkdir(parents=True)
        (programs / ident / 'mower-android.json').write_text('{}')
        delta = self.delta()
        upload = FileStorage(stream=io.BytesIO(delta.read_bytes()), filename='renamed.bin')
        with (patch.object(arknights_mower, '__version__', '4.1.6-alpha.7'),
              patch.object(app_update, 'folder', return_value=self.root),
              patch.object(app_update.mower_package, 'folder', return_value=programs),
              patch.object(app_update.mower_package, 'state', return_value={
                  'id': ident, 'version': '4.1.6-alpha.7', 'pending': False}),
              patch.object(app_update, 'download_asset', side_effect=AssertionError('offline')),
              patch.object(app_update.mower_ota, 'reconstruct') as reconstruct,
              patch.object(app_update.mower_package, 'install', return_value={'message': 'ready'}) as install,
              patch.object(app_update.threading, 'Thread', side_effect=lambda *, target, daemon: Mock(start=target))):
            preview = app_update.inspect_upload(upload)
            self.assertEqual(preview['version'], 'v4.1.6-alpha.8')
            app_update.submit(preview['check_id'])
        reconstruct.assert_called_once()
        install.assert_called_once()
        self.assertEqual(app_update.status()['status'], 'succeeded')


if __name__ == '__main__':
    unittest.main()
