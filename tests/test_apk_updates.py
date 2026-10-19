import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import requests
from flask import Flask

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android import apk_updates
from arknights_mower.utils import github_download, network_settings


class ApkUpdateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        patches = [
            patch.dict(os.environ, {'MOWER_DATA_DIR': str(self.root)}, clear=True),
            patch.object(network_settings, 'settings_path', return_value=self.root/'network.json'),
            patch.object(network_settings, '_base_environment', None),
            patch.object(network_settings, '_effective_settings', None),
            patch.object(apk_updates, '_plan', {}),
            patch.object(apk_updates, '_job', {'ok': True, 'status': 'idle'}),
        ]
        for context in patches:
            context.start()
            self.addCleanup(context.stop)
        self.payload = b'a verified APK'
        self.apk = dict(name='mower.apk', version='0.3.0', version_code=30,
                        host_sha256='b'*64, host_version_code=30)
        self.descriptor = json.dumps({'format': 1, 'apk': self.apk}).encode()
        self.release = dict(tag_name='v0.3.0', draft=False, assets=[
            self.asset('android-release.json', self.descriptor), self.asset('mower.apk', self.payload)])

    def asset(self, name, content):
        return dict(name=name, size=len(content), digest='sha256:'+hashlib.sha256(content).hexdigest(),
                    browser_download_url=f'https://github.com/{apk_updates.REPO}/releases/download/v0.3.0/{name}')

    def response(self, content=b'', *, status=200):
        response = Mock(status_code=status)
        response.iter_content.return_value = [content]
        if status >= 400:
            response.raise_for_status.side_effect = requests.HTTPError('request failed')
        return response

    def settings(self, *, http='', github=''):
        (self.root/'network.json').write_text(json.dumps({'http_proxy': http, 'github_proxy': github}))

    def download(self, check_id):
        # Execute the real worker synchronously; only thread scheduling is replaced.
        with patch.object(apk_updates.threading, 'Thread', side_effect=lambda **kw: Mock(start=kw['target'])):
            return apk_updates.download(check_id)

    def test_check_and_apk_download_use_current_mower_proxies_and_keep_api_unprefixed(self):
        self.settings(http='socks5h://127.0.0.1:7897', github='https://proxy.example/')
        calls = []
        replies = iter([json.dumps(self.release).encode(), self.descriptor, self.payload])
        def get(url, **kwargs):
            calls.append((url, requests.utils.get_environ_proxies(url)))
            self.assertEqual(kwargs, dict(stream=True, timeout=(10, 30)))
            return self.response(next(replies))
        with patch.object(apk_updates.requests, 'get', side_effect=get):
            checked = apk_updates.check()
            self.settings(http='http://127.0.0.1:8888', github='https://new-proxy.example/')
            result = self.download(checked['check_id'])
        self.assertEqual(calls[0][0], f'https://api.github.com/repos/{apk_updates.REPO}/releases/latest')
        self.assertEqual(calls[1][0], 'https://proxy.example/'+self.release['assets'][0]['browser_download_url'])
        self.assertEqual(calls[2][0], 'https://new-proxy.example/'+self.release['assets'][1]['browser_download_url'])
        self.assertEqual(calls[0][1]['https'], 'socks5h://127.0.0.1:7897')
        self.assertEqual(calls[2][1]['https'], 'http://127.0.0.1:8888')
        self.assertEqual(result['status'], 'succeeded')
        self.assertEqual((apk_updates.folder()/(result['sha256']+'.apk')).read_bytes(), self.payload)
        self.assertEqual(apk_updates.folder(), self.root/'tmp/updates/4e21008231518eabb448/android-apk')
        self.assertFalse(list(apk_updates.folder().glob('*.part')))

    def test_direct_timeout_and_retryable_http_status_try_the_default_station_once(self):
        url = self.release['assets'][0]['browser_download_url']
        for failure in (requests.Timeout('timeout'), self.response(status=403), self.response(status=429), self.response(status=503)):
            with self.subTest(failure=type(failure).__name__):
                direct, fallback = failure, self.response(b'content')
                with patch.object(apk_updates.requests, 'get', side_effect=[direct, fallback]) as get:
                    self.assertEqual(apk_updates.receive(url, 100), b'content')
                self.assertEqual([c.args[0] for c in get.call_args_list], [url, 'https://ghfast.top/'+url])
                fallback.close.assert_called_once()
                if not isinstance(failure, Exception):
                    failure.close.assert_called_once()

    def test_default_fallback_works_with_new_shared_helper_and_old_ota_helper(self):
        url = self.release['assets'][0]['browser_download_url']
        legacy = SimpleNamespace(download_url=github_download.download_url)
        with patch('arknights_mower.utils.github_download', legacy):
            with patch.object(apk_updates.requests, 'get', side_effect=[requests.ConnectionError(), self.response(b'ok')]) as get:
                self.assertEqual(apk_updates.receive(url, 2), b'ok')
            self.assertEqual([call.args[0] for call in get.call_args_list], [url, 'https://ghfast.top/'+url])
        with patch.object(github_download, 'download_urls', create=True, return_value=(url, 'https://shared.example/'+url)) as urls:
            with patch.object(apk_updates.requests, 'get', side_effect=[requests.ConnectionError(), self.response(b'ok')]) as get:
                self.assertEqual(apk_updates.receive(url, 2), b'ok')
            urls.assert_called_once_with(url, '')
            self.assertEqual(get.call_args_list[1].args[0], 'https://shared.example/'+url)

    def test_explicit_station_and_api_requests_never_fall_back_to_another_station(self):
        url = self.release['assets'][0]['browser_download_url']
        for github, api in (('https://chosen.example/', False), ('', True)):
            self.settings(github=github)
            with patch.object(apk_updates.requests, 'get', side_effect=requests.ConnectionError('unreachable')) as get:
                with self.assertRaises(requests.ConnectionError):
                    apk_updates.receive(url, 100, file_download=not api)
            self.assertEqual(get.call_count, 1)

    def test_404_oversize_and_bad_digest_do_not_fall_back(self):
        url = self.release['assets'][0]['browser_download_url']
        for response, limit, expected in ((self.response(status=404), 100, None),
                                          (self.response(b'too big'), 2, None),
                                          (self.response(b'corrupt'), 100, self.release['assets'][0])):
            with patch.object(apk_updates.requests, 'get', return_value=response) as get:
                with self.assertRaises((requests.HTTPError, ValueError)):
                    apk_updates.receive(url, limit, expected=expected)
            get.assert_called_once()
            response.close.assert_called_once()

    def test_truncated_body_retries_from_scratch_and_failed_download_removes_partial_file(self):
        self.settings()
        with patch.object(apk_updates.requests, 'get', side_effect=[
                self.response(json.dumps(self.release).encode()), self.response(self.descriptor)]):
            checked = apk_updates.check()
        direct = self.response()
        def truncated(*args):
            yield b'partial'
            raise requests.exceptions.ChunkedEncodingError('disconnected')
        direct.iter_content.side_effect = truncated
        with patch.object(apk_updates.requests, 'get', side_effect=[direct, self.response(self.payload)]) as get:
            succeeded = self.download(checked['check_id'])
        self.assertEqual(get.call_count, 2)
        self.assertEqual((apk_updates.folder()/(succeeded['sha256']+'.apk')).read_bytes(), self.payload)
        with patch.object(apk_updates.requests, 'get', return_value=self.response(b'corrupt')):
            failed = self.download(checked['check_id'])
        self.assertEqual(failed['status'], 'failed')
        self.assertFalse(list(apk_updates.folder().glob('*.part')))
        self.assertEqual((apk_updates.folder()/(succeeded['sha256']+'.apk')).read_bytes(), self.payload)

    def test_untrusted_asset_or_descriptor_never_becomes_a_download_plan(self):
        for mutate in ('url', 'digest', 'size', 'version'):
            release = json.loads(json.dumps(self.release))
            if mutate == 'version':
                release['tag_name'] = 'v0.3.0-nightly'
            else:
                item = release['assets'][0]
                item[{'url': 'browser_download_url', 'digest': 'digest', 'size': 'size'}[mutate]] = {
                    'url': 'https://evil.example/android-release.json', 'digest': 'invalid', 'size': 2*1024**2}[mutate]
            with patch.object(apk_updates.requests, 'get', return_value=self.response(json.dumps(release).encode())):
                with self.assertRaises(ValueError):
                    apk_updates.check()
            self.assertEqual(apk_updates._plan, {})

    def test_expired_plan_and_concurrent_download_are_rejected(self):
        with self.assertRaisesRegex(ValueError, '重新检查'):
            apk_updates.download('missing')
        with patch.object(apk_updates, '_plan', {'id': 'expired', 'checked_at': 0}), \
                patch.object(apk_updates.time, 'monotonic', return_value=3601):
            with self.assertRaisesRegex(ValueError, '重新检查'):
                apk_updates.download('expired')
        with patch.object(apk_updates, '_job', {'status': 'running'}):
            with self.assertRaisesRegex(ValueError, '正在下载'):
                apk_updates.check()
            with self.assertRaisesRegex(ValueError, '正在下载'):
                apk_updates.download('missing')

    def test_routes_require_update_header_and_keep_proxy_errors_out_of_responses(self):
        app = Flask(__name__)
        apk_updates.register_routes(app)
        client = app.test_client()
        self.assertEqual(client.post('/android/apk-update', json={'action': 'check'}).status_code, 403)
        with patch.object(apk_updates.requests, 'get', side_effect=requests.exceptions.ProxyError('secret proxy address')):
            response = client.post('/android/apk-update', json={'action': 'check'}, headers={'X-Mower-Update': '1'})
        self.assertEqual(response.status_code, 400)
        self.assertNotIn('secret', response.get_data(as_text=True))
        self.assertEqual(client.get('/android/apk-update').json['status'], 'idle')


if __name__ == '__main__':
    unittest.main()
