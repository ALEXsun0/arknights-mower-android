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
from mower_android import python_package, mower_package

REPO = 'ArkMowers/arknights-mower'
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
        raise ValueError('更新后需在手机停止并重新启动 Mower 服务')
    current = {**prefs(), **data}
    temporary = folder()/'settings.new'; temporary.write_text(json.dumps(current)); temporary.replace(folder()/'settings.json')
    return {'ok': True}


def info():
    from arknights_mower import __version__
    return {'ok': True, 'deployment': 'release', 'platform': 'android', 'version': __version__,
            'settings': prefs(), 'blockers': [], 'instances': [{'name': '本机', 'running': True}],
            'force_supported': False, 'manual_supported': True, 'source_remotes': [],
            'capabilities': {'auto_update': False, 'silent_restart': False},
            'channels': [{'label': '正式版', 'value': 'stable', 'description': '最新正式 Release'},
                         {'label': '公测版', 'value': 'beta', 'description': '最新公测 Release'}],
            'releases_url': f'https://github.com/{REPO}/releases', 'last_check': _last_check,
            'manual_label': '点击或拖入 Mower、MAA 核心或兼容 Python 更新包',
            'manual_hint': 'Mower 使用主仓库 Android 更新包；MAA 使用官方 Android ARM64 包，Python 使用本发行兼容包。',
            'install_label': '导入并安装', 'python': python_package.info(),
            'component_updates': [{'label':'MAA Python 兼容接口','endpoint':'/android/python-update','check':True,
                                  'hint':'仅接口内容变化时提示更新；现有任务保持原接口，新实例使用新接口。可在下方拖入兼容 ZIP。'},
                                 {'label':'内置 Mower 恢复','endpoint':'/android/mower-recovery','check':False,
                                  'hint':'恢复 APK 内置 Mower，停止并重新启动服务后生效。配置和 MAA 不受影响。'}]}


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
    releases = [r for r in releases if any(a['name'] == f"arknights-mower_{r['tag_name'].removeprefix('v')}_android_arm64.zip" for a in r.get('assets', []))]
    if not releases: raise ValueError('主仓库尚未发布此渠道的 Android Mower 更新包，当前版本可继续使用')
    release = choose_release(releases, channel)
    asset = next((a for a in release['assets'] if a['name'] == f"arknights-mower_{release['tag_name'].removeprefix('v')}_android_arm64.zip"), None)
    if not asset or not re.fullmatch(r'sha256:[a-f0-9]{64}', asset.get('digest') or ''):
        raise ValueError('此发行版缺少可校验的 Android Mower 更新包')
    ident = uuid.uuid4().hex
    _plans[ident] = {'asset': asset, 'version': release['tag_name']}
    from arknights_mower import __version__ as current
    _last_check = {'ok': True, 'check_id': ident, 'channel': channel, 'checked_at': time.time(),
        'version': release['tag_name'], 'available': version_key(release['tag_name']) > version_key(current),
        'downgrade': False, 'url': release['html_url'], 'notes': release.get('body', ''),
        'confirm_title': '确认下载安装？', 'confirm_message': '下载并校验 Mower 更新包，停止并重启手机服务后生效。APK 和 MAA 保持不变。'}
    return _last_check


def request_auto_check():
    if prefs()['auto_check']: return check(prefs()['channel'])
    return {'ok': True}


def inspect_upload(upload):
    if upload is None: raise ValueError('请选择更新包')
    ident = uuid.uuid4().hex; target = folder()/(ident+'.package')
    try:
        downgrade = False
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
            elif 'mower-android.json' in names:
                meta = mower_package.inspect(target)
                from arknights_mower import __version__
                from arknights_mower.utils.software_update import version_key
                downgrade = version_key(meta['version']) < version_key(__version__)
                kind, version = 'mower', meta['version']
                message = '更新内置 Mower；停止并重新启动手机服务后生效，APK 和 MAA 保持不变。'
            else: raise ValueError('请选择 Android Mower 更新包、官方 MAA 核心或兼容 Python 包')
        _plans[ident] = {'path': target, 'sha256': digest, 'filename': upload.filename, 'kind': kind, 'version': version, 'downgrade': downgrade}
        return {'ok': True, 'manual': True, 'check_id': ident, 'version': version, 'downgrade': downgrade,
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
    if background or force: raise ValueError('此 Release 安装器不支持所选操作')
    plan = _plans.get(check_id)
    if plan is None: raise ValueError('请重新检查或导入更新包')
    if plan.get('downgrade') and not confirm_downgrade: raise ValueError('请确认回退 Mower 版本')
    if not _lock.acquire(blocking=False): raise ValueError('已有更新正在进行')
    _plans.pop(check_id, None)
    ident = uuid.uuid4().hex
    _job = {'ok': True, 'id': ident, 'status': 'running', 'phase': 'installing', 'progress': 0,
            'message': '正在准备更新包', 'cancellable': False}
    def run():
        path = plan.get('path')
        try:
            if 'asset' in plan:
                asset = plan['asset']; digest = asset['digest'][7:]; path = folder()/(digest+'.zip')
                size = 0; sha = hashlib.sha256()
                with requests.get(asset['browser_download_url'], stream=True, timeout=(15, 60)) as response:
                    response.raise_for_status()
                    with path.open('wb') as out:
                        for chunk in response.iter_content(256*1024):
                            size += len(chunk)
                            if size > MAX_UPLOAD: raise ValueError('安装包超过大小限制')
                            out.write(chunk); sha.update(chunk)
                            _job.update(progress=min(99, size*100//max(1, asset['size'])), current=size, total=asset['size'], message='正在下载 Mower')
                if size != asset['size'] or sha.hexdigest() != digest: raise ValueError('Mower 更新包 SHA256 或大小不一致')
                plan.update(kind='mower', sha256=digest)
            with path.open('rb') as stream: actual = hashlib.file_digest(stream, 'sha256').hexdigest()
            if actual != plan['sha256']: raise ValueError('更新包内容已改变，请重新导入')
            if plan['kind'] == 'mower':
                result = mower_package.install(path)
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
