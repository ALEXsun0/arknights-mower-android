import io
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

    def test_shared_update_selects_upstream_mower_zip_not_apk(self):
        asset={'name':'arknights-mower_4.2.0_android_arm64.zip','digest':'sha256:'+'a'*64,'size':10}
        reply=Mock(); reply.json.return_value={'tag_name':'v4.2.0','draft':False,'prerelease':False,'published_at':'2026-09-11T00:00:00Z','assets':[{'name':'mower-android-arm64.apk'},asset],'html_url':'https://github.com/ArkMowers/arknights-mower/releases/tag/v4.2.0'}
        with patch.object(app_update.requests,'get',return_value=reply) as get:
            result=app_update.check('stable')
        self.assertIn('/repos/ArkMowers/arknights-mower/',get.call_args.args[0])
        self.assertEqual(app_update._plans[result['check_id']]['asset'],asset)
        self.assertTrue(result['available'])


if __name__=='__main__': unittest.main()
