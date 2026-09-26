import hashlib
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from flask import Flask
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'runtime'))
from mower_android import python_package, app_update


class PythonPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.zip=self.root/'adapter.zip'
        self.code=(Path(__file__).resolve().parents[1]/'runtime/mower_android/maa_adapter.py').read_bytes()
        self.meta={'kind':'mower-maa-python','format':1,'version':'1.0.0','channel':'stable','bridge_protocol':1,'min_apk':5,'sha256':hashlib.sha256(self.code).hexdigest()}
        self.patch=patch.dict(os.environ,{'MOWER_APK_CODE':'5'});self.patch.start();self.addCleanup(self.patch.stop)
    def pack(self,**changes):
        with zipfile.ZipFile(self.zip,'w') as z:
            z.writestr('maa-python.json',json.dumps({**self.meta,**changes}));z.writestr('maa.py',self.code)
    def test_install_preserves_original_until_next_instance_and_can_reset(self):
        self.pack();root=self.root/'installed'
        installed=python_package.install(self.zip,root)
        self.assertEqual(installed['version'],'1.0.0')
        self.assertTrue((root/(self.meta['sha256']+'.py')).exists())
        with patch.object(python_package,'ROOT',root):
            self.assertFalse(python_package.info()['bundled'])
            self.assertEqual(python_package.adapter_class().__name__,'Asst')
            python_package.reset();self.assertTrue(python_package.info()['bundled'])
    def test_incompatible_package_does_not_replace_current_adapter(self):
        root=self.root/'installed';self.pack();python_package.install(self.zip,root);original=(root/'active.json').read_bytes()
        for changes in ({'min_apk':999},{'bridge_protocol':2},{'channel':'dev'},{'sha256':'0'*64}):
            with self.subTest(changes=changes):
                self.pack(**changes)
                with self.assertRaises(ValueError):python_package.install(self.zip,root)
                self.assertEqual((root/'active.json').read_bytes(),original)
    def test_desktop_python_or_extra_files_are_rejected(self):
        self.pack()
        with zipfile.ZipFile(self.zip,'a') as z:z.writestr('../outside.py','pass')
        with self.assertRaises(ValueError):python_package.install(self.zip,self.root/'installed')
        self.assertFalse((self.root/'outside.py').exists())


class DistributionPolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        p=patch.dict(os.environ,{'MOWER_DATA_DIR':self.temp.name});p.start();self.addCleanup(p.stop)
        from arknights_mower.views.software_update import software_update_bp
        app=Flask(__name__);app.extensions['software_update_provider']=app_update
        app.register_blueprint(software_update_bp);self.client=app.test_client()
        self.headers={'X-Mower-Update':'1'}
    def test_release_accepts_dev_channel(self):
        self.assertEqual(self.client.post('/software-update/settings',json={'channel':'dev'},headers=self.headers).status_code,200)
        self.assertEqual(app_update.prefs()['channel'],'dev')
    def test_release_rejects_repository_and_silent_install(self):
        self.assertEqual(self.client.post('/software-update/settings',json={'channel':'beta','repository':'owner/repo'},headers=self.headers).status_code,400)
        self.assertEqual(self.client.post('/software-update/settings',json={'channel':'beta','auto_update':True},headers=self.headers).status_code,400)
        self.assertEqual(self.client.post('/software-update/settings',json={'channel':'stable'},headers=self.headers).status_code,200)
        self.assertEqual(app_update.prefs()['channel'],'stable')
    def test_manual_python_preview_is_read_only_until_confirmed(self):
        import io
        code=(Path(__file__).resolve().parents[1]/'runtime/mower_android/maa_adapter.py').read_bytes()
        meta={'kind':'mower-maa-python','format':1,'version':'1.0.0','channel':'stable','bridge_protocol':1,'min_apk':5,'sha256':hashlib.sha256(code).hexdigest()}
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:
            z.writestr('maa-python.json',json.dumps(meta));z.writestr('maa.py',code)
        live=Path(self.temp.name)/'live'
        with patch.object(python_package,'ROOT',live):
            response=self.client.post('/software-update/manual/inspect',data={'file':(io.BytesIO(data.getvalue()),'compat.zip')},headers=self.headers)
            self.assertEqual(response.status_code,200)
            self.assertTrue(python_package.info()['bundled'])
            check_id=response.json['check_id']
            result=self.client.post('/software-update/start',json={'check_id':check_id},headers=self.headers)
            self.assertEqual(result.status_code,200)
            self.assertTrue(app_update._lock.acquire(timeout=5))
            app_update._lock.release()
            self.assertEqual(app_update.status()['status'],'succeeded')
            self.assertFalse(python_package.info()['bundled'])

    @patch('mower_android.app_update.Bridge')
    def test_common_ui_detects_release_and_host_capabilities(self,bridge):
        bridge.return_value.call.return_value={'apk_version':'0.1.0-alpha.1'}
        data=self.client.get('/software-update/info').json
        self.assertEqual(data['deployment'],'release')
        self.assertEqual([c['value'] for c in data['channels']],['stable','beta','dev'])
        self.assertTrue(data['capabilities']['dev_update'])
        self.assertFalse(data['capabilities']['silent_restart'])

if __name__=='__main__':unittest.main()
