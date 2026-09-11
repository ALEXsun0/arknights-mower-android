"""Android Release installer behind Mower's shared software-update contract."""
import hashlib
import json
import os
import re
import tempfile
import threading
import time
import uuid
import zipfile
from pathlib import Path

import requests
from mower_android.bridge import Bridge
from mower_android import python_package

REPO = 'ALEXsun0/arknights-mower-android'
MAX_UPLOAD = 768 * 1024**2
_lock = threading.Lock()
_plans = {}
_job = {'ok': True, 'status': 'idle'}
_last_check = {}


def folder():
    path = Path(os.environ.get('MOWER_DATA_DIR', '/mower-data')) / 'android-updates'
    path.mkdir(parents=True, exist_ok=True)
    return path


def prefs():
    try: data = json.loads((folder()/'settings.json').read_text())
    except (OSError, ValueError): data = {}
    return {'channel': data.get('channel') if data.get('channel') in ('stable', 'beta') else 'beta',
            'auto_check': bool(data.get('auto_check')), 'auto_update': False, 'background': False}


def save_settings(data):
    if not isinstance(data, dict) or set(data) - set(prefs()) or data.get('channel') not in ('stable', 'beta'):
        raise ValueError('Release 安装版仅支持正式版和公测版')
    if any(type(data.get(k, False)) is not bool for k in ('auto_check', 'auto_update', 'background')):
        raise ValueError('设置格式错误')
    if data.get('auto_update') or data.get('background'):
        raise ValueError('APK 安装需要在手机系统安装器中确认')
    current = {**prefs(), **data}
    temporary = folder()/'settings.new'; temporary.write_text(json.dumps(current)); temporary.replace(folder()/'settings.json')
    return {'ok': True}


def info():
    engine = Bridge().call('status')
    return {'ok': True, 'deployment': 'release', 'platform': 'android', 'version': engine['apk_version'],
            'settings': prefs(), 'blockers': [], 'instances': [{'name': '本机', 'running': True}],
            'force_supported': False, 'manual_supported': True, 'source_remotes': [],
            'capabilities': {'auto_update': False, 'silent_restart': False},
            'channels': [{'label': '正式版', 'value': 'stable', 'description': '最新正式 Release'},
                         {'label': '公测版', 'value': 'beta', 'description': '最新公测 Release'}],
            'releases_url': f'https://github.com/{REPO}/releases', 'last_check': _last_check,
            'manual_label': '点击或拖入 APK、MAA 核心或兼容 Python 包',
            'manual_hint': 'APK 交给手机系统安装器；MAA 使用官方 Android ARM64 包，Python 使用本发行配套兼容包。',
            'install_label': '导入并安装', 'python': python_package.info()}


def status():
    return {**_job, 'last_check': _last_check}


def check(channel):
    global _last_check
    from arknights_mower.utils.software_update import choose_release, version_key
    if channel not in ('stable', 'beta'): raise ValueError('Release 安装版仅支持正式版和公测版')
    endpoint = f'https://api.github.com/repos/{REPO}/releases'
    if channel == 'stable':
        response = requests.get(endpoint+'/latest', timeout=30); response.raise_for_status(); releases = [response.json()]
    else:
        releases = []
        for page in range(1, 101):
            response = requests.get(endpoint, params={'per_page': 100, 'page': page}, timeout=30); response.raise_for_status()
            batch = response.json(); releases.extend(batch)
            if len(batch) < 100: break
    releases = [r for r in releases if not re.search(r'dev|nightly|snapshot', r.get('tag_name', ''), re.I)]
    release = choose_release(releases, channel)
    asset = next((a for a in release['assets'] if a['name'].endswith('.apk')), None)
    if not asset or not re.fullmatch(r'sha256:[a-f0-9]{64}', asset.get('digest') or ''):
        raise ValueError('此发行版缺少可校验的 APK')
    ident = uuid.uuid4().hex
    _plans[ident] = {'asset': asset, 'version': release['tag_name']}
    current = Bridge().call('status')['apk_version']
    _last_check = {'ok': True, 'check_id': ident, 'channel': channel, 'checked_at': time.time(),
        'version': release['tag_name'], 'available': version_key(release['tag_name']) > version_key(current),
        'downgrade': False, 'url': release['html_url'], 'notes': release.get('body', ''),
        'confirm_title': '确认下载安装？', 'confirm_message': '下载并校验 APK 后，在手机系统安装器确认安装。应用数据保留。'}
    return _last_check


def request_auto_check():
    if prefs()['auto_check']: return check(prefs()['channel'])
    return {'ok': True}


def inspect_upload(upload):
    if upload is None: raise ValueError('请选择更新包')
    ident = uuid.uuid4().hex; target = folder()/(ident+'.package')
    try:
        size = 0
        with target.open('wb') as out:
            while chunk := upload.stream.read(256*1024):
                size += len(chunk)
                if size > MAX_UPLOAD: raise ValueError('更新包超过 768 MiB')
                out.write(chunk)
        with target.open('rb') as stream: digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        with target.open('rb') as stream: header = stream.read(2)
        if header == b'\x1f\x8b':
            match = re.fullmatch(r'MAAComponent-(v[0-9][A-Za-z0-9.\-]*)-android-arm64\.tar\.gz', upload.filename or '')
            if not match: raise ValueError('请选择官方 Android ARM64 MAAComponent 原包')
            kind, version = 'maa-core', 'MAA '+match[1]
            message = '校验官方核心并准备资源，完成后停止并重新启动服务生效。'
        else:
            with zipfile.ZipFile(target) as z: names = set(z.namelist())
            if names == {'maa-python.json', 'maa.py'}:
                with tempfile.TemporaryDirectory(dir=folder()) as temp:
                    meta = python_package.install(target, root=temp)
                kind, version = 'python', 'MAA Python '+meta['version']
                message = '导入兼容 Python 接口；现有任务保持原接口，下一次创建 MAA 实例时生效。'
            elif {'AndroidManifest.xml', 'classes.dex'}.issubset(names):
                apk = folder()/(digest+'.apk'); target.replace(apk); target = apk
                meta = Bridge().call('apk_info', id=digest)
                kind, version = 'apk', meta['version']
                message = 'APK 已通过包名、签名和版本检查，接下来在手机系统安装器确认安装。应用数据保留。'
            else: raise ValueError('请选择本发行的 APK、官方 Android MAA 核心或兼容 Python 包')
        _plans[ident] = {'path': target, 'sha256': digest, 'filename': upload.filename, 'kind': kind, 'version': version}
        return {'ok': True, 'manual': True, 'check_id': ident, 'version': version, 'downgrade': False,
                'confirm_title': '确认导入更新包？', 'confirm_message': message}
    except Exception:
        target.unlink(missing_ok=True)
        raise


def discard_upload(check_id):
    plan = _plans.pop(check_id, None)
    if plan and 'path' in plan: plan['path'].unlink(missing_ok=True)
    return {'ok': True}


def submit(check_id, background=False, force=False, confirm_downgrade=False):
    global _job
    if background or force or confirm_downgrade: raise ValueError('此 Release 安装器不支持所选操作')
    plan = _plans.pop(check_id, None)
    if plan is None: raise ValueError('请重新检查或导入更新包')
    if not _lock.acquire(blocking=False): raise ValueError('已有更新正在进行')
    ident = uuid.uuid4().hex
    _job = {'ok': True, 'id': ident, 'status': 'running', 'phase': 'installing', 'progress': 0,
            'message': '正在准备更新包', 'cancellable': False}
    def run():
        path = plan.get('path')
        try:
            if 'asset' in plan:
                asset = plan['asset']; digest = asset['digest'][7:]; path = folder()/(digest+'.apk')
                size = 0; sha = hashlib.sha256()
                with requests.get(asset['browser_download_url'], stream=True, timeout=(15, 60)) as response:
                    response.raise_for_status()
                    with path.open('wb') as out:
                        for chunk in response.iter_content(256*1024):
                            size += len(chunk)
                            if size > MAX_UPLOAD: raise ValueError('安装包超过大小限制')
                            out.write(chunk); sha.update(chunk)
                            _job.update(progress=min(99, size*100//max(1, asset['size'])), current=size, total=asset['size'], message='正在下载 APK')
                if size != asset['size'] or sha.hexdigest() != digest: raise ValueError('APK SHA256 或大小不一致')
                plan.update(kind='apk', sha256=digest)
            with path.open('rb') as stream: actual = hashlib.file_digest(stream, 'sha256').hexdigest()
            if actual != plan['sha256']: raise ValueError('更新包内容已改变，请重新导入')
            if plan['kind'] == 'apk':
                result = Bridge().call('apk_install', id=plan['sha256'])
            elif plan['kind'] == 'python': result = python_package.install(path)
            else:
                import server
                from mower_android.managed import import_component
                with server.maa_maintenance_lock:
                    if server._job_running(server.maa_update_job) or server._job_running(server.maa_resource_update_job):
                        raise ValueError('MAA 正在更新，请等待完成后重试')
                    result = import_component(path, plan['filename'])
            _job.update(status='succeeded', progress=100, message=result.get('message', '已完成'))
        except Exception as exc: _job.update(status='failed', message=str(exc))
        finally:
            if path is not None and plan.get('kind') != 'apk': path.unlink(missing_ok=True)
            _lock.release()
    threading.Thread(target=run, daemon=True).start()
    return dict(_job)


def __getattr__(name):
    # Source-only routes must not fall through to the desktop Git implementation.
    if name in {'remember_source_remote', 'source_history', 'check_source_version', 'source_pulls',
                'check_source_pull', 'check_source_pulls', 'upload_package', 'cancel'}:
        def unsupported(*args, **kwargs): raise ValueError('此 Release 安装器不支持该操作')
        return unsupported
    raise AttributeError(name)
