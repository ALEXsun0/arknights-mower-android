import hashlib
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android import managed, mower_package


class ApkBundledTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {'MOWER_DATA_DIR': str(self.root), 'MOWER_APK_GENERATION': '26:100'})
        self.env.start(); self.addCleanup(self.env.stop)
        os.environ.pop('MOWER_ACTIVE_ID', None)
        self.addCleanup(os.environ.pop, 'MOWER_ACTIVE_ID', None)
        self.bundled = self.root / 'bundled'
        self.hot = mower_package.folder() / ('a' * 64) / 'mower'
        self.hot.mkdir(parents=True)
        (self.hot / 'server.py').write_text('pass')
        self.jobs = patch('mower_android.background_cleanup.submit', side_effect=lambda _, f: f())
        self.jobs.start(); self.addCleanup(self.jobs.stop)

    def active_hot(self, generation='25:100'):
        mower_package.save({'id': 'a' * 64, 'apk_generation': generation})

    def test_apk_overrides_hot_mower_then_retires_it_after_ready(self):
        self.active_hot()
        config = self.root / 'plan.json'; config.write_text('user plan')
        self.assertEqual(mower_package.select_source(self.bundled), self.bundled)
        self.assertTrue(self.hot.exists())
        mower_package.mark_ready()
        self.assertFalse(self.hot.exists())
        self.assertEqual(mower_package.select_source(self.bundled), self.bundled)
        self.assertEqual(config.read_text(), 'user plan')

    def test_failed_bundled_start_rolls_back_without_repeated_reset(self):
        self.active_hot()
        self.assertEqual(mower_package.select_source(self.bundled), self.bundled)
        self.assertEqual(mower_package.select_source(self.bundled), self.hot)
        mower_package.mark_ready()
        self.assertEqual(mower_package.select_source(self.bundled), self.hot)

    def test_apk_fallback_uses_verified_source_not_unstarted_hot_update(self):
        mower_package.save({'id': 'b' * 64, 'previous': 'a' * 64,
                            'pending': True, 'apk_generation': 'old'})
        self.assertEqual(mower_package.select_source(self.bundled), self.bundled)
        self.assertEqual(mower_package.select_source(self.bundled), self.hot)

    def test_normal_restart_preserves_subsequent_hot_update(self):
        self.active_hot('26:100')
        self.assertEqual(mower_package.select_source(self.bundled), self.hot)
        mower_package.save({'id': 'a' * 64, 'pending': True})
        self.assertEqual(mower_package.state()['apk_generation'], '26:100')
        self.assertEqual(mower_package.select_source(self.bundled), self.hot)
        mower_package.mark_ready()
        self.assertEqual(mower_package.select_source(self.bundled), self.hot)

    def test_same_version_reinstallation_also_resets_to_bundle(self):
        self.active_hot('26:99')
        self.assertEqual(mower_package.select_source(self.bundled), self.bundled)

    def test_maa_replaces_resources_preserves_settings_and_retains_backup(self):
        maa = self.root / 'maa'; maa.mkdir()
        (maa / '.mower-android.json').write_text(json.dumps({'version': 'hot'}))
        (maa / 'config.json').write_text('user settings')
        (maa / 'cache/resource').mkdir(parents=True)
        (maa / 'cache/resource/hot.json').write_text('old hot resource')
        component = self.root / 'maa-component.zip'
        with zipfile.ZipFile(component, 'w') as z:
            z.writestr('.mower-android.json', json.dumps({'version': 'bundled'}))
            z.writestr('resource/version.json', 'bundled resource')
        component.with_suffix('.sha256').write_text(hashlib.sha256(component.read_bytes()).hexdigest())
        pending = self.root / 'maa-bundled-pending'; pending.write_text('26:100')
        with patch.object(managed, 'MAA_PATH', maa), patch.object(managed, 'COMPONENT', component):
            managed.prepare_files()
            self.assertEqual(managed.installed_version(maa), 'bundled')
            self.assertEqual((maa / 'config.json').read_text(), 'user settings')
            self.assertFalse((maa / 'cache/resource/hot.json').exists())
            self.assertTrue((self.root / 'maa.old/cache/resource/hot.json').exists())
            self.assertFalse(pending.exists())
            # A crash just before deleting pending must not replace the good backup.
            pending.write_text('26:100'); managed.prepare_files()
            self.assertTrue((self.root / 'maa.old/cache/resource/hot.json').exists())
            # Resource-only hot updates may leave the old generation marker behind.
            (maa / 'resource/version.json').write_text('hot again')
            managed.prepare_files()
            self.assertEqual((maa / 'resource/version.json').read_text(), 'hot again')
            pending.write_text('26:200'); managed.prepare_files()
            self.assertEqual((maa / 'resource/version.json').read_text(), 'bundled resource')

    def test_bad_maa_asset_does_not_change_active_installation(self):
        maa = self.root / 'maa'; maa.mkdir()
        metadata = maa / '.mower-android.json'; metadata.write_text('{"version":"old"}')
        component = self.root / 'maa-component.zip'; component.write_text('corrupt')
        component.with_suffix('.sha256').write_text('0' * 64)
        pending = self.root / 'maa-bundled-pending'; pending.write_text('new')
        with patch.object(managed, 'MAA_PATH', maa), patch.object(managed, 'COMPONENT', component):
            with self.assertRaisesRegex(RuntimeError, '校验失败'): managed.prepare_files()
        self.assertEqual(metadata.read_text(), '{"version":"old"}')
        self.assertTrue(pending.exists())
