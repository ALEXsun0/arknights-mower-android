import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android import app_update


class ChannelPromotionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'settings').mkdir()

    def index(self, version, current, *, valid=True):
        url = f'https://github.com/{app_update.OTA_REPO}/releases/download/{version}/'
        full = f'arknights-mower_{version[1:]}_android_arm64.zip'
        ota = f'arknights-mower-ota_{current}_to_{version[1:]}_android_arm64.zip'
        return {
            'schema': 1, 'version': version, 'source_release': url,
            'full_assets': [{'name': full, 'size': 1000, 'url': url + full,
                             'digest': 'sha256:' + 'a'*64 if valid else ''}],
            'ota_assets': [{'name': ota, 'size': 100, 'url': url + ota,
                            'digest': 'sha256:' + 'b'*64}],
        }

    def check(self, channel, current, indexes):
        def read(name):
            if name not in indexes:
                raise ValueError('Channel unavailable')
            return indexes[name]

        package = types.ModuleType('arknights_mower')
        package.__version__ = current
        utils = types.ModuleType('arknights_mower.utils')
        software = types.ModuleType('arknights_mower.utils.software_update')
        software.choose_release = Mock()
        ident = 'b'*64
        (self.root / ident).mkdir(exist_ok=True)
        (self.root / ident / 'mower-android.json').write_text('{}')
        with (patch.dict(sys.modules, {'arknights_mower': package,
                                       'arknights_mower.utils': utils,
                                       'arknights_mower.utils.software_update': software}),
              patch.object(app_update, 'release_index', side_effect=read) as network,
              patch.object(app_update, 'folder', return_value=self.root / 'settings'),
              patch.object(app_update.mower_package, 'folder', return_value=self.root),
              patch.object(app_update.mower_package, 'state', return_value={
                  'id': ident, 'version': current, 'pending': False})):
            app_update.save_settings({'channel': channel})
            result = app_update.check(channel)
            self.assertEqual(app_update.prefs()['channel'], channel)
        software.choose_release.assert_not_called()
        plan = app_update._plans.pop(result['check_id'])
        return result, plan, network

    def test_newer_beta_and_stable_use_ota_without_switching_channel(self):
        current = '4.1.6-alpha.10.g85e916f7'
        for channel, promoted, target in (
            ('dev', 'beta', 'v4.1.6-alpha.11'),
            ('dev', 'stable', 'v4.1.6'),
            ('beta', 'stable', 'v4.1.6'),
        ):
            with self.subTest(channel=channel, target=target):
                own = 'v' + current if channel == 'dev' else 'v4.1.6-alpha.11'
                result, plan, _ = self.check(channel, current, {
                    channel: self.index(own, current), promoted: self.index(target, current)})
                self.assertEqual(result['channel'], channel)
                self.assertEqual(result['version'], target)
                self.assertTrue(result['available'])
                self.assertFalse(result['downgrade'])
                self.assertIn(f'_to_{target[1:]}_', plan['ota']['asset']['name'])
                self.assertIn(f'/{target}/', plan['asset']['browser_download_url'])

    def test_current_stable_not_reinstalled_and_newer_nightly_remains_eligible(self):
        current = '4.1.6'
        indexes = {
            'dev': self.index('v4.1.6-alpha.10.g85e916f7', current),
            'beta': self.index('v4.1.6-alpha.11', current),
            'stable': self.index('v4.1.6', current),
        }
        result, _, _ = self.check('dev', current, indexes)
        self.assertEqual(result['version'], 'v4.1.6')
        self.assertFalse(result['available'])
        indexes['dev'] = self.index('v4.1.7-alpha.1.g12345678', current)
        result, _, _ = self.check('dev', current, indexes)
        self.assertEqual(result['version'], indexes['dev']['version'])

    def test_optional_unavailable_or_unverifiable_channels_preserve_own_release(self):
        current = '4.1.6-alpha.10.g85e916f7'
        own = self.index('v4.1.6-alpha.11.g12345678', current)
        incompatible = self.index('v4.1.6', current)
        incompatible['full_assets'] = []
        for other in ({}, {'stable': self.index('v4.1.6', current, valid=False)},
                      {'stable': incompatible}):
            with self.subTest(other=bool(other)):
                result, _, _ = self.check('dev', current, {'dev': own, **other})
                self.assertEqual(result['version'], own['version'])

    def test_missing_exact_source_ota_keeps_public_targets_full_package(self):
        current = '4.1.6-alpha.10.g85e916f7'
        target = self.index('v4.1.6-alpha.11', '4.1.6-alpha.10')
        result, plan, _ = self.check('dev', current, {
            'dev': self.index('v' + current, current), 'beta': target})
        self.assertEqual(result['version'], target['version'])
        self.assertNotIn('ota', plan)
        self.assertIn('/v4.1.6-alpha.11/', plan['asset']['browser_download_url'])

    def test_optional_malformed_asset_metadata_preserves_primary_release(self):
        current = '4.1.6-alpha.10.g85e916f7'
        for field, value in (('size', None), ('size', -1), ('digest', 123), ('url', 123)):
            with self.subTest(field=field, value=value):
                candidate = self.index('v4.1.6', current)
                candidate['full_assets'][0][field] = value
                result, _, _ = self.check('dev', current, {
                    'dev': self.index('v' + current, current), 'stable': candidate})
                self.assertFalse(result['available'])

    def test_stable_excludes_beta_and_nightly(self):
        current = '4.1.5'
        result, _, network = self.check('stable', current, {
            'stable': self.index('v4.1.6', current),
            'beta': self.index('v4.1.7-alpha.11', current),
            'dev': self.index('v4.1.7-alpha.11.g12345678', current),
        })
        self.assertEqual(result['version'], 'v4.1.6')
        network.assert_called_once_with('stable')

    def test_index_rejects_wrong_channel_or_invalid_version(self):
        for channel, version in (('stable', 'v4.1.6-alpha.11'),
                                 ('beta', 'v4.1.6-alpha.11.g12345678'),
                                 ('dev', 'v4.1.6-alpha.11'),
                                 ('stable', 'not-a-version')):
            with self.subTest(channel=channel, version=version):
                response = Mock()
                response.json.return_value = self.index(version, '4.1.5')
                with patch.object(app_update.requests, 'get', return_value=response):
                    with self.assertRaises(ValueError):
                        app_update.release_index(channel)
