import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android import app_update, mower_ota


class MowerOtaTests(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
