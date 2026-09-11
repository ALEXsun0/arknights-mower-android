import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from prepare_distribution import adapter_metadata
from release_common import latest_beta
from package_maa_python import build
from host_fingerprint import host_digest
from publish_distribution import compatibility

class ReleaseAutomationTests(unittest.TestCase):
    def test_latest_beta_includes_newer_stable_and_ignores_old_calendar_version(self):
        releases=[{'tag_name':tag,'published_at':at,'draft':False} for tag,at in [
            ('2025.2.1','2025-02-01T00:00:00Z'),('v4.1.6-alpha.5','2026-09-09T00:00:00Z'),
            ('v4.1.6','2026-09-10T00:00:00Z'),('v4.2.0-nightly','2026-09-11T00:00:00Z')]]
        self.assertEqual(latest_beta(releases)['tag_name'],'v4.1.6')
    def test_unchanged_interface_preserves_version_and_compatibility_start(self):
        baseline=json.loads((ROOT/'runtime/mower_android/maa-python.json').read_text())
        code=(ROOT/'runtime/mower_android/maa_adapter.py').read_bytes()
        next_meta=adapter_metadata(code,baseline,baseline,'v0.2.9','v4.2.0','v6.19.0')
        self.assertEqual(next_meta,baseline)
    def test_changed_interface_advances_once_and_starts_new_range(self):
        baseline=json.loads((ROOT/'runtime/mower_android/maa-python.json').read_text())
        next_meta=adapter_metadata(b'changed',baseline,baseline,'v0.2.9','v4.2.0','v6.19.0')
        self.assertEqual(next_meta['version'],'1.0.1')
        self.assertEqual(next_meta['compatibility']['since_android'],'v0.2.9')
        self.assertEqual(adapter_metadata(b'changed',baseline,next_meta,'v0.2.10','v4.2.1','v6.19.1'),next_meta)
    def test_zip_is_reproducible_and_payload_matches_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); (root/'runtime/mower_android').mkdir(parents=True)
            for name in ['maa-python.json','maa_adapter.py']:
                (root/'runtime/mower_android'/name).write_bytes((ROOT/'runtime/mower_android'/name).read_bytes())
            first=build(root).read_bytes(); second=build(root).read_bytes()
            self.assertEqual(first,second)
    def test_only_resource_and_version_bumps_do_not_change_host_identity(self):
        baseline=host_digest(ROOT)
        real=Path.read_bytes
        def read(path):
            data=real(path)
            if path.name=='build.gradle.kts': data=data.replace(b'versionCode = 15',b'versionCode = 500')
            if path.name=='maa_adapter.py' or 'jniLibs' in path.parts: return b'changed generated or hot-update bytes'
            return data
        with patch.object(Path,'read_bytes',read): self.assertEqual(host_digest(ROOT),baseline)
        self.assertNotEqual(host_digest(ROOT,b'new-dependency==1.0'),baseline)
    def test_release_page_closes_range_at_next_interface_change(self):
        adapter=json.loads((ROOT/'runtime/mower_android/maa-python.json').read_text())
        meta={'maa_python':adapter,'bundled':{'mower':{'tag':'v4.1.6-alpha.5'},'maa':{'tag':'v6.17.5'}}}
        self.assertIn('下一次 Python',compatibility(meta))
        self.assertIn('至 v0.2.9 之前',compatibility(meta,'v0.2.9'))


class SnapshotTests(unittest.TestCase):
    def test_resource_only_snapshot_and_unchanged_snapshot(self):
        import prepare_distribution as prepare
        import shutil
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for name in ['scripts/bundled-release.json','android/app/build.gradle.kts','runtime/mower_android/maa-python.json','runtime/mower_android/maa_adapter.py']:
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,p)
            baseline=json.loads((root/'scripts/bundled-release.json').read_text())
            adapter=json.loads((root/'runtime/mower_android/maa-python.json').read_text())
            previous={'apk':{'version':'0.2.0','version_code':15,'host_sha256':'a'*64,'host_version_code':15},'maa_python':adapter,'bundled':baseline}
            previous_file=root/'previous.json';previous_file.write_text(json.dumps(previous))
            def asset(name): return {'name':name,'digest':'sha256:'+'b'*64,'size':10,'browser_download_url':'https://github.com/example/release.zip'}
            def release(tag,assets): return {'tag_name':tag,'published_at':'2026-09-11T00:00:00Z','assets':assets}
            mower=release(baseline['mower']['tag'],[])
            maa=release(baseline['maa']['tag'],[asset('MAAComponent-'+baseline['maa']['tag']+'-android-arm64.tar.gz')])
            android=release('v0.2.0',[asset('android-release.json')])
            responses={prepare.MOWER_REPO:[mower],prepare.MAA_REPO:[maa],prepare.ANDROID_REPO:[android]}
            with patch.object(prepare,'ROOT',root),patch.object(prepare,'releases',side_effect=lambda repo: responses[repo]),patch.object(prepare,'download',return_value=previous_file),patch.object(prepare,'host_digest',return_value='a'*64):
                same=prepare.plan(publish=True)
                self.assertFalse(same['publish']);self.assertFalse(same['host_update'])
                maa['tag_name']='v6.17.6';maa['assets']=[asset('MAAComponent-v6.17.6-android-arm64.tar.gz')]
                changed=prepare.plan(publish=True)
                self.assertTrue(changed['publish']);self.assertFalse(changed['host_update'])
                self.assertEqual(changed['host_version_code'],15)
                self.assertEqual(changed['version_code'],16)
                self.assertEqual(changed['adapter']['sha256'],adapter['sha256'])
                self.assertFalse((root/'android/app/src/main/assets').exists())
                prepare.apply(metadata_only=True)
                self.assertTrue((root/'android/app/src/main/assets/host-build.json').is_file())

if __name__=='__main__': unittest.main()
