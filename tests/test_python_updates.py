import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch,Mock
from flask import Flask
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'runtime'))
from mower_android import python_updates as updates, python_package

class OnlinePythonTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.env=patch.dict(os.environ,{'MOWER_DATA_DIR':self.temp.name,'MOWER_APK_CODE':'15'})
        self.env.start();self.addCleanup(self.env.stop)
        p=patch.object(python_package,'ROOT',self.root/'installed');p.start();self.addCleanup(p.stop)
        self.meta=json.loads((ROOT/'runtime/mower_android/maa-python.json').read_text())
        self.release={'tag_name':'v0.2.0','published_at':'2026-09-11T00:00:00Z','html_url':'https://github.com/'+updates.REPO+'/releases/tag/v0.2.0',
                      'assets':[{'name':'android-release.json'},{'name':'adapter.zip'}]}
    def check(self,meta=None):
        response=Mock();response.json.return_value=[self.release]
        with patch.object(updates.requests,'get',return_value=response),patch.object(updates,'fetch',return_value=json.dumps({'maa_python':{'name':'adapter.zip',**(meta or self.meta)}}).encode()):
            return updates.check()['latest']
    def test_new_apk_release_with_identical_python_does_not_offer_update(self):
        self.release['tag_name']='v0.2.99'
        self.assertFalse(self.check()['available'])
    def test_changed_python_content_offers_update(self):
        self.assertTrue(self.check({**self.meta,'sha256':'a'*64,'version':'1.0.1'})['available'])
    def test_incompatible_host_does_not_offer_python_install(self):
        result=self.check({**self.meta,'sha256':'a'*64,'min_apk':999})
        self.assertFalse(result['available']);self.assertFalse(result['compatible'])
    def test_auto_check_is_optional_and_deduplicates_notifications(self):
        notify=Mock();updates.save({'auto_check':False})
        with patch.object(updates,'check') as check: updates.auto_check_once(notify);check.assert_not_called()
        updates.save({})
        result={'available':True,'sha256':'a'*64,'version':'1.0.1','checked_at':0}
        def check():
            value=updates.state();value['latest']=result;updates.save(value);return {'latest':result}
        with patch.object(updates,'check',side_effect=check):
            updates.auto_check_once(notify);updates.auto_check_once(notify)
        notify.assert_called_once_with('1.0.1')
    def test_online_install_verifies_descriptor_before_activation(self):
        code=(ROOT/'runtime/mower_android/maa_adapter.py').read_bytes()+b'\n# next interface\n'
        meta={**self.meta,'version':'1.0.1','sha256':hashlib.sha256(code).hexdigest()}
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:
            z.writestr('maa.py',code);z.writestr('maa-python.json',json.dumps(meta))
        updates.save({'latest':{'compatible':True,'sha256':'b'*64,'version':'1.0.1','asset':{}}})
        with patch.object(updates,'fetch',return_value=data.getvalue()):
            with self.assertRaises(ValueError):updates.install()
            self.assertTrue(python_package.info()['bundled'])
            value=updates.state();value['latest']['sha256']=meta['sha256'];updates.save(value)
            result=updates.install();self.assertEqual(result['version'],'1.0.1')
            self.assertFalse(python_package.info()['bundled'])
            self.assertFalse(updates.info()['latest']['available'])
    def test_cached_availability_tracks_manual_import_and_reset(self):
        updates.save({'latest': {'sha256': 'a'*64, 'compatible': True, 'available': False}})
        self.assertTrue(updates.info()['latest']['available'])
        with patch.object(python_package, 'info', return_value={'sha256': 'a'*64}):
            self.assertFalse(updates.info()['latest']['available'])
        updates.save({'latest': {'sha256': 'a'*64, 'compatible': False, 'available': True}})
        self.assertFalse(updates.info()['latest']['available'])

    def test_android_maa_info_advertises_combined_check_without_network(self):
        app = Flask(__name__)
        @app.get('/maa-update/info')
        def maa_info():
            return {'ok': True, 'latest': {'tag': 'v6.18.0'}}
        updates.register_routes(app)
        with patch.object(updates, 'check') as check:
            result = app.test_client().get('/maa-update/info').json
            check.assert_not_called()
        self.assertEqual(result['latest'], {'tag': 'v6.18.0'})
        self.assertEqual(result['component_updates'][0]['endpoint'], '/android/python-update')
        self.assertTrue(result['component_updates'][0]['combined_check'])

    def test_auth_failure_does_not_advertise_components(self):
        app = Flask(__name__)
        @app.get('/maa-update/info')
        def maa_info():
            return {'ok': False}, 401
        updates.register_routes(app)
        result = app.test_client().get('/maa-update/info')
        self.assertEqual(result.status_code, 401)
        self.assertNotIn('component_updates', result.json)

    def test_webui_route_supports_lan_with_update_header(self):
        app=Flask(__name__);updates.register_routes(app);client=app.test_client()
        self.assertEqual(client.post('/android/python-update',json={'auto_check':False}).status_code,403)
        response=client.post('/android/python-update',json={'auto_check':False},headers={'X-Mower-Update':'1'},environ_base={'REMOTE_ADDR':'192.168.50.2'})
        self.assertEqual(response.status_code,200);self.assertFalse(response.json['auto_check'])

if __name__=='__main__':unittest.main()
