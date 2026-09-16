"""Resolve an immutable upstream snapshot, then apply it identically in both CI jobs."""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import zipfile
from pathlib import Path
from host_fingerprint import host_digest
from release_common import ANDROID_REPO, MOWER_REPO, MAA_REPO, releases, latest_beta, asset, download, version_key

ROOT = Path(__file__).resolve().parents[1]


def adapter_metadata(code, baseline, previous, tag, mower, maa):
    digest = hashlib.sha256(code).hexdigest()
    old = previous or baseline
    if old.get('sha256') == digest:
        return {**baseline, **{k:old[k] for k in ('version','sha256','compatibility') if k in old}}
    major, minor, patch = map(int, old['version'].split('.'))
    return {**baseline, 'version':f'{major}.{minor}.{patch+1}', 'sha256':digest,
            'compatibility':{'since_android':tag,'mower_from':mower,'maa_from':maa,'until_android':None}}


def plan(publish=False, force=False):
    baseline = json.loads((ROOT/'scripts/bundled-release.json').read_text())
    mower = latest_beta(releases(MOWER_REPO))
    maa = latest_beta(releases(MAA_REPO))
    android = [r for r in releases(ANDROID_REPO) if not r.get('draft') and version_key(r['tag_name'])]
    previous = {}; previous_release = max(android, key=lambda r: version_key(r['tag_name'])) if android else None
    if previous_release:
        descriptor = asset(previous_release, 'android-release.json')
        previous = json.loads(download(descriptor, ROOT/'artifacts/previous-release.json', 1024**2).read_text())
    # Prefer an official update archive even if this tag was once bundled locally.
    mower_asset_name = f"arknights-mower_{mower['tag_name'].removeprefix('v')}_android_arm64.zip"
    if any(a['name'] == mower_asset_name for a in mower.get('assets', [])):
        component = asset(mower, mower_asset_name)
        mower_state = {'tag':mower['tag_name'], 'source':'release', 'asset':component}
    elif mower['tag_name'] == baseline['mower']['tag'] and baseline['mower']['source'] == 'bundled':
        # The old alpha.5 predates official Android archives. Only that configured
        # bootstrap snapshot may fall back; a new incomplete release must retry.
        mower_state = dict(baseline['mower'])
    else:
        raise ValueError(f"{mower['tag_name']} has not published {mower_asset_name} yet")
    maa_asset = asset(maa, f"MAAComponent-{maa['tag_name']}-android-arm64.tar.gz")
    bundled = {'mower':mower_state, 'maa':{'tag':maa['tag_name'], 'asset':maa_asset}}
    gradle = (ROOT/'android/app/build.gradle.kts').read_text()
    source_version = re.search(r'versionName = "([^"]+)"', gradle)[1]
    previous_version = previous.get('apk', {}).get('version', '0.0.0')
    if version_key(source_version) <= version_key(previous_version):
        major, minor, patch = map(int, previous_version.split('.'))
        version = f'{major}.{minor}.{patch+1}'
    else: version = source_version
    code = max(int(re.search(r'versionCode = (\d+)', gradle)[1]), previous.get('apk', {}).get('version_code', 0)+1)
    meta = json.loads((ROOT/'runtime/mower_android/maa-python.json').read_text())
    old_adapter = previous.get('maa_python', {})
    if 'sha256' not in old_adapter: old_adapter = None
    adapter = adapter_metadata((ROOT/'runtime/mower_android/maa_adapter.py').read_bytes(), meta, old_adapter,
                               'v'+version, mower['tag_name'], maa['tag_name'])
    old_bundled = previous.get('bundled', {})
    changed = any(old_bundled.get(k, {}).get('tag') != bundled[k]['tag'] for k in ('mower', 'maa'))
    changed |= adapter['sha256'] != previous.get('maa_python', {}).get('sha256')
    dependencies = None
    if mower_state['source'] == 'release':
        package = download(mower_state['asset'], ROOT/'artifacts/upstream-mower.zip')
        with zipfile.ZipFile(package) as archive:
            manifest = json.loads(archive.read('mower-android.json'))
            # Dependencies now travel with Mower; they no longer require an APK update.
            dependencies = b'<mower-managed-runtime>' if manifest.get('format') == 2 else archive.read('mower/requirements.txt')
    host_sha = host_digest(ROOT, dependencies)
    same_host = previous.get('apk', {}).get('host_sha256') == host_sha
    host_code = previous['apk'].get('host_version_code', previous['apk']['version_code']) if same_host else code
    result = {'host_sha256':host_sha, 'host_update':not same_host, 'host_version_code':host_code, 'format':1, 'tag':'v'+version, 'version':version, 'version_code':code,
              'publish':publish and (force or changed), 'changed':changed, 'bundled':bundled, 'adapter':adapter}
    output = ROOT/'artifacts/distribution.json'; output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(result, indent=2)+'\n')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
            out.write(f"publish={str(result['publish']).lower()}\nchanged={str(changed).lower()}\ntag={result['tag']}\n")
    print(json.dumps({'tag':result['tag'],'publish':result['publish'],'mower':mower['tag_name'],'maa':maa['tag_name'],'python':adapter['version']}))
    return result


def apply(metadata_only=False):
    data = json.loads((ROOT/'artifacts/distribution.json').read_text())
    gradle = ROOT/'android/app/build.gradle.kts'
    s = re.sub(r'versionCode = \d+', f"versionCode = {data['version_code']}", gradle.read_text())
    s = re.sub(r'versionName = "[^"]+"', f"versionName = \"{data['version']}\"", s)
    gradle.write_text(s)
    (ROOT/'runtime/mower_android/maa-python.json').write_text(json.dumps(data['adapter'],indent=2)+'\n')
    maa = data['bundled']['maa']; item = maa['asset']
    lock = {'version':maa['tag'],'url':item['browser_download_url'],'sha256':item['digest'][7:]}
    for target in ('scripts/maa-android.lock.json','runtime/mower_android/maa-component-lock.json'):
        (ROOT/target).write_text(json.dumps(lock,indent=2)+'\n')
    build_info = {'host_sha256':data['host_sha256'], 'host_version_code':data['host_version_code']}
    host_file = ROOT/'android/app/src/main/assets/host-build.json'
    host_file.parent.mkdir(parents=True, exist_ok=True)
    host_file.write_text(json.dumps(build_info)+'\n')
    if metadata_only: return
    mower = data['bundled']['mower']
    upstream = json.loads((ROOT/'UPSTREAM.json').read_text())
    upstream['maa'].update(asset=item['name'],sha256=lock['sha256'])
    upstream_runtime = ROOT/'artifacts/upstream-python-runtime.zip.xz'
    upstream_runtime.unlink(missing_ok=True)
    if mower['source'] == 'release':
        package = download(mower['asset'], ROOT/'artifacts/upstream-mower.zip')
        sys.path.insert(0,str(ROOT/'runtime'))
        from mower_android.mower_package import inspect
        meta = inspect(package, apk_code=data['version_code'])
        with zipfile.ZipFile(package) as archive:
            if not archive.read('mower/CHANGELOG.md').strip(): raise ValueError('Mower release CHANGELOG.md is empty')
        if meta['version'] != mower['tag'].removeprefix('v'): raise ValueError('Mower release version mismatch')
        runtime = ROOT/'runtime'
        # Only the shared application is replaced. Host/adapter and user data are never overlaid.
        shutil.rmtree(runtime/'arknights_mower')
        shutil.rmtree(runtime/'ui/dist', ignore_errors=True)
        with zipfile.ZipFile(package) as archive:
            if meta['format'] == 2:
                upstream_runtime.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(meta['runtime']['file']) as src, upstream_runtime.open('wb') as dst:
                    shutil.copyfileobj(src, dst, 1024*1024)
            for entry in archive.infolist():
                if not entry.filename.startswith('mower/') or entry.is_dir(): continue
                target = runtime/entry.filename.removeprefix('mower/')
                target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(archive.read(entry))
        shutil.copy2(runtime/'requirements.txt', runtime/'requirements.in')
        upstream['mower'] = {'url':f'https://github.com/{MOWER_REPO}','commit':meta['revision'],'release':mower['tag'],'source':'official Android release package'}
    (ROOT/'UPSTREAM.json').write_text(json.dumps(upstream,indent=2)+'\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('action',choices=['plan','apply'])
    parser.add_argument('--publish',action='store_true'); parser.add_argument('--force',action='store_true')
    parser.add_argument('--metadata-only',action='store_true'); args=parser.parse_args()
    if args.action == 'plan': plan(args.publish,args.force)
    else: apply(args.metadata_only)
