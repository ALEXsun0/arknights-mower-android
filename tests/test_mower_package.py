import io
import hashlib
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'runtime'))
from mower_android import mower_package, app_update


class MowerPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        context=patch.dict(os.environ, {'MOWER_DATA_DIR':str(self.root)})
        context.start(); self.addCleanup(context.stop)
        jobs = patch('mower_android.background_cleanup.submit', side_effect=lambda key, action: action())
        jobs.start(); self.addCleanup(jobs.stop)
        os.environ.pop('MOWER_ACTIVE_ID', None)
        self.addCleanup(os.environ.pop, 'MOWER_ACTIVE_ID', None)

    def pack(self, api=1, extra=None, body='__version__ = "4.2.0"\n'):
        path=self.root/'update.zip'
        with zipfile.ZipFile(path,'w') as z:
            z.writestr('mower-android.json',json.dumps({'kind':'mower-android','format':1,'platform':'android','arch':'arm64','runtime_api':api,'python':'3.12','version':'4.2.0','revision':'a'*40}))
            z.writestr('mower/arknights_mower/__init__.py',body)
            z.writestr('mower/server.py','pass')
            z.writestr('mower/ui/dist/index.html','<h1>Mower</h1>')
            z.writestr('mower/requirements.txt','Flask==3.0.3')
            if extra: z.writestr(extra,'pass')
        return path

    def test_inspect_does_not_activate_and_failed_packages_preserve_state(self):
        mower_package.inspect(self.pack())
        self.assertEqual(mower_package.state(),{})
        mower_package.install(self.pack()); previous=mower_package.state()
        for kwargs in ({'api':2},{'extra':'mower/mower_android/launcher.py'},{'extra':'../outside'},{'body':'__version__ = "4.2.0"\nsyntax error!'}):
            with self.subTest(kwargs=kwargs):
                with self.assertRaises((ValueError,SyntaxError)): mower_package.install(self.pack(**kwargs))
                self.assertEqual(mower_package.state(),previous)
        self.assertFalse((self.root.parent/'outside').exists())

    def test_successful_launch_keeps_updated_mower_and_failed_launch_rolls_back(self):
        bundled=self.root/'bundled'
        mower_package.install(self.pack())
        active=mower_package.select_source(bundled)
        self.assertNotEqual(active,bundled)
        self.assertTrue(mower_package.state()['booting'])
        self.assertEqual(mower_package.select_source(bundled),bundled)
        mower_package.install(self.pack())
        active=mower_package.select_source(bundled); mower_package.mark_ready()
        self.assertEqual(mower_package.select_source(bundled),active)
        mower_package.reset(); self.assertEqual(mower_package.select_source(bundled),bundled)

    def test_backups_retire_only_after_the_new_server_is_ready(self):
        bundled=self.root/'bundled'
        mower_package.install(self.pack(body='__version__ = "4.2.0"\n# first'))
        first=mower_package.select_source(bundled);mower_package.mark_ready()
        mower_package.install(self.pack(body='__version__ = "4.2.0"\n# next'))
        mower_package.mark_ready() # old server cannot commit the pending update
        self.assertTrue(first.exists());self.assertTrue(mower_package.state()['pending'])
        second=mower_package.select_source(bundled)
        self.assertTrue(first.exists())
        mower_package.mark_ready()
        self.assertFalse(first.exists());self.assertTrue(second.exists())
        self.assertNotIn('previous',mower_package.state())
        mower_package.reset();mower_package.mark_ready()
        self.assertTrue(second.exists()) # still serving the old process
        mower_package.select_source(bundled);mower_package.mark_ready()
        self.assertFalse(second.exists())

    def test_failed_launch_keeps_the_previous_version_until_recovery_is_ready(self):
        bundled=self.root/'bundled'
        mower_package.install(self.pack(body='__version__ = "4.2.0"\n# first'))
        first=mower_package.select_source(bundled);mower_package.mark_ready()
        mower_package.install(self.pack(body='__version__ = "4.2.0"\n# bad'))
        failed=mower_package.select_source(bundled)
        self.assertTrue(first.exists())
        self.assertEqual(mower_package.select_source(bundled),first)
        self.assertTrue(failed.exists())
        mower_package.mark_ready()
        self.assertTrue(first.exists());self.assertFalse(failed.exists())

    def pack_runtime(self, *, minimum=29, python='3.13', corrupt=False):
        original = self.pack()
        with zipfile.ZipFile(original) as z:
            files = {name:z.read(name) for name in z.namelist()}
        payload = b'compressed runtime fixture; extraction is tested by the native installer'
        meta = json.loads(files['mower-android.json'])
        meta.update(format=2, min_apk=minimum, python=python,
                    runtime={'file':'python-runtime.zip.xz', 'sha256':hashlib.sha256(payload).hexdigest(), 'unpacked_size':1024})
        files['mower-android.json'] = json.dumps(meta)
        files['python-runtime.zip.xz'] = payload + (b'corrupt' if corrupt else b'')
        with zipfile.ZipFile(original, 'w') as z:
            for name, contents in files.items(): z.writestr(name, contents)
        return original

    def test_runtime_update_requires_new_host_and_verified_payload(self):
        with patch.dict(os.environ, {'MOWER_APK_CODE':'28'}):
            with self.assertRaisesRegex(ValueError, '最低版本代码 29'):
                mower_package.install(self.pack_runtime())
        with patch.dict(os.environ, {'MOWER_APK_CODE':'29'}):
            meta = mower_package.inspect(self.pack_runtime())
            self.assertEqual(meta['python'], '3.13')
            mower_package.install(self.pack_runtime())
            previous = mower_package.state()
            self.assertTrue((mower_package.folder()/previous['id']/'python-runtime.zip.xz').is_file())
            with self.assertRaisesRegex(ValueError, '校验失败'):
                mower_package.install(self.pack_runtime(corrupt=True))
            self.assertEqual(mower_package.state(), previous)
            with self.assertRaisesRegex(ValueError, '最低版本代码 30'):
                mower_package.inspect(self.pack_runtime(minimum=30))

    def test_native_selection_does_not_roll_back_the_current_boot(self):
        mower_package.install(self.pack())
        ident = mower_package.state()['id']
        mower_package.save({**mower_package.state(), 'booting':True})
        with patch.dict(os.environ, {'MOWER_SOURCE_SELECTED':'1', 'MOWER_ACTIVE_ID':ident}):
            source = mower_package.select_source(self.root/'bundled')
            self.assertEqual(source, mower_package.folder()/ident/'mower')
            self.assertTrue(mower_package.state()['booting'])
            mower_package.mark_ready()
            self.assertNotIn('pending', mower_package.state())
        with patch.dict(os.environ, {'MOWER_SOURCE_SELECTED':'1', 'MOWER_ACTIVE_ID':''}):
            self.assertEqual(mower_package.select_source(self.root/'bundled'), self.root/'bundled')
            self.assertNotIn('MOWER_ACTIVE_ID', os.environ)

    def test_manual_downgrade_requires_confirmation(self):
        from werkzeug.datastructures import FileStorage
        import arknights_mower
        with patch.object(arknights_mower, '__version__', '4.3.0'):
            result=app_update.inspect_upload(FileStorage(stream=io.BytesIO(self.pack().read_bytes()),filename='mower.zip'))
        self.assertTrue(result['downgrade'])
        with self.assertRaises(ValueError): app_update.submit(result['check_id'])
        app_update.submit(result['check_id'],confirm_downgrade=True)
        self.assertTrue(app_update._lock.acquire(timeout=5)); app_update._lock.release()
        self.assertEqual(app_update.status()['status'],'succeeded')
        self.assertEqual(mower_package.state()['version'],'4.2.0')

    def test_shared_update_selects_upstream_mower_zip_not_apk(self):
        asset={'name':'arknights-mower_4.2.0_android_arm64.zip','digest':'sha256:'+'a'*64,'size':10,
               'browser_download_url':'https://github.com/ArkMowers/arknights-mower/releases/download/v4.2.0/arknights-mower_4.2.0_android_arm64.zip'}
        reply=Mock(); reply.json.return_value={'tag_name':'v4.2.0','draft':False,'prerelease':False,'published_at':'2026-09-11T00:00:00Z','assets':[{'name':'mower-android-arm64.apk'},asset],'html_url':'https://github.com/ArkMowers/arknights-mower/releases/tag/v4.2.0'}
        with patch.object(app_update, 'release_index', return_value=None), \
             patch.object(app_update.requests,'get',return_value=reply) as get:
            result=app_update.check('stable')
        self.assertIn('/repos/ArkMowers/arknights-mower/',get.call_args.args[0])
        self.assertEqual(app_update._plans[result['check_id']]['asset'],asset)
        self.assertTrue(result['available'])


if __name__=='__main__': unittest.main()
