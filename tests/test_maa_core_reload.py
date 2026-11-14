import hashlib
import io
import shutil
import sys
import tarfile
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android import app_update, managed
from arknights_mower.utils import maa_update


class CoreUpdateActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = self.enterContext(tempfile.TemporaryDirectory())
        self.root = Path(self.temp)
        self.target = self.root / 'maa'
        self.target.mkdir()
        (self.target / 'libMaaCore.so').write_bytes(b'old core')
        (self.target / 'config.json').write_text('saved settings')
        self.component = self.root / 'maa-component.zip'
        self.enterContext(patch.object(managed, 'MAA_PATH', self.target))
        self.enterContext(patch.object(managed, 'COMPONENT', self.component))
        self.filename = 'MAAComponent-v6.22.0-android-arm64.tar.gz'
        self.package = self.root / self.filename
        files = {'libMaaCore.so': b'new core', 'libMaaAndroidNativeControlUnit.so': b'controller'}
        for kind in ('det', 'rec'):
            for ext in ('param', 'bin'):
                files[f'resource/PaddleOCR/{kind}/{kind}.ncnn.{ext}'] = b'model'
        with tarfile.open(self.package, 'w:gz') as archive:
            for name, content in files.items():
                info = tarfile.TarInfo(name); info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
        digest = hashlib.sha256(self.package.read_bytes()).hexdigest()
        release = SimpleNamespace(tag='v6.22.0', runtime=SimpleNamespace(name=self.filename, sha256=digest))
        self.enterContext(patch.object(maa_update, 'get_latest_release', return_value=release))
        self.enterContext(patch.object(maa_update, 'parse_release', return_value=release))
        self.enterContext(patch('arknights_mower.utils.github_download.request_github'))
        self.enterContext(patch.object(maa_update, 'download_asset', side_effect=self.download))
        self.bridge = self.enterContext(patch('mower_android.bridge.Bridge')).return_value
        self.bridge.call.side_effect = self.activate

    def download(self, asset, destination, **kwargs):
        shutil.copyfile(self.package, destination)
        return destination.stat().st_size

    def activate(self, method, **params):
        self.assertEqual(method, 'maa_reload')
        # The actual installer must finish both disk replacement and component packing first.
        self.assertEqual((self.target / 'libMaaCore.so').read_bytes(), b'new core')
        self.assertEqual((self.root / 'maa.old/libMaaCore.so').read_bytes(), b'old core')
        expected = hashlib.sha256(self.component.read_bytes()).hexdigest()
        self.assertEqual(params, {'component_hash': expected})
        self.assertEqual(self.component.with_suffix('.sha256').read_text().strip(), expected)
        return {'component_hash': expected, 'activated': True}

    def install(self, manual=False):
        if manual:
            return managed.import_component(self.package, self.filename)
        return managed.install_update(self.target)

    def test_online_update_activates_committed_core_and_preserves_configuration_and_backup(self):
        result = self.install()
        self.bridge.call.assert_called_once()
        self.assertTrue(result['activated']); self.assertFalse(result['restart_required'])
        self.assertEqual((self.target / 'config.json').read_text(), 'saved settings')
        self.assertTrue((self.root / 'maa.old').is_dir())

    def test_manual_import_uses_same_activation_without_restarting_python(self):
        result = self.install(manual=True)
        self.bridge.call.assert_called_once()
        self.assertTrue(result['activated']); self.assertFalse(result['restart_required'])

    def test_failed_activation_preserves_successful_install_and_recovery_message(self):
        self.bridge.call.side_effect = RuntimeError('old process did not exit')
        result = self.install()
        self.assertTrue(result['restart_required']); self.assertFalse(result['activated'])
        self.assertIn('已安装', result['message']); self.assertIn('old process did not exit', result['message'])
        self.assertEqual((self.target / 'libMaaCore.so').read_bytes(), b'new core')
        self.assertTrue((self.root / 'maa.old').is_dir())

    def test_unloaded_core_does_not_require_service_restart(self):
        self.bridge.call.side_effect = lambda method, **p: {**p, 'activated': False}
        result = self.install()
        self.assertFalse(result['activated']); self.assertFalse(result['restart_required'])
        self.assertIn('下次连接', result['message'])

    def test_mismatched_or_invalid_acknowledgment_never_reports_activation(self):
        for response in (True, {'activated': True, 'component_hash': 'c' * 64}, {'activated': 'yes'}):
            with self.subTest(response=response):
                self.component.with_suffix('.sha256').write_text('a' * 64)
                self.bridge.call.side_effect = None
                self.bridge.call.return_value = response
                result = managed.activate_core()
                self.assertFalse(result['activated']); self.assertTrue(result['restart_required'])

    def test_bad_archive_never_requests_activation(self):
        with patch.object(maa_update, 'extract_linux_package', side_effect=ValueError('invalid archive')):
            with self.assertRaisesRegex(ValueError, 'invalid archive'):
                self.install()
        self.bridge.call.assert_not_called()
        self.assertEqual((self.target / 'libMaaCore.so').read_bytes(), b'old core')

    def test_manual_submission_rejects_active_mower_before_installation(self):
        server = SimpleNamespace(maa_maintenance_lock=threading.RLock(),
                                 _mower_busy_response=lambda: {'ok': False})
        plan = {'kind': 'maa-core', 'path': self.package, 'filename': self.filename,
                'sha256': hashlib.sha256(self.package.read_bytes()).hexdigest()}
        with patch.dict(sys.modules, {'server': server}), patch.dict(app_update._plans, {'manual': plan}), \
                patch.object(app_update, '_job', {'status': 'idle'}), patch.object(app_update, 'read_restart', return_value={}):
            app_update.submit('manual')
            self.assertTrue(app_update._lock.acquire(timeout=5)); app_update._lock.release()
            self.assertEqual(app_update._job['status'], 'failed')
            self.assertIn('停止任务', app_update._job['message'])
        self.bridge.call.assert_not_called()
        self.assertEqual((self.target / 'libMaaCore.so').read_bytes(), b'old core')


if __name__ == '__main__':
    unittest.main()
