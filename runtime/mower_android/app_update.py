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
from mower_android import python_package, mower_package, mower_ota

REPO = 'ArkMowers/arknights-mower'
OTA_REPO = 'ArkMowers/MowerRelease'
OTA_INDEX_URL = f'https://raw.githubusercontent.com/{OTA_REPO}/main/version'
NIGHTLY_RE = re.compile(r'v\d+\.\d+\.\d+-alpha\.\d+\.g[0-9a-f]{8}\Z')
VERSION_RE = re.compile(r'v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+)(?:\.g([0-9a-f]{8}))?)?\Z')
MAX_UPLOAD = 768 * 1024**2
_lock = threading.Lock()
_plans = {}
_job = {'ok': True, 'status': 'idle'}
_last_check = {}


def version_key(value):
    match = VERSION_RE.fullmatch(value.split('+', 1)[0] if isinstance(value, str) else '')
    if not match:
        raise ValueError(f'无法识别版本号：{value}')
    major, minor, patch, stage, number, revision = match.groups()
    return (int(major), int(minor), int(patch),
            {'alpha': 0, 'beta': 1, 'rc': 2, None: 3}[stage], int(number or 0), bool(revision))


def folder():
    path = Path(os.environ.get('MOWER_DATA_DIR', '/mower-data')) / 'android-updates'
    path.mkdir(parents=True, exist_ok=True)
    return path


def automatic_restart():
    return os.environ.get('MOWER_SERVICE_RESTART') == '1'


def restart_folder():
    # /mower is the host's stable program mount across downloaded generations.
    key = hashlib.sha256(b'/mower').hexdigest()[:20]
    path = Path(os.environ.get('MOWER_DATA_DIR', '/mower-data'))/'tmp'/'updates'/key/'android-service'
    path.mkdir(parents=True, exist_ok=True)
    return path


def save_restart(job):
    temporary = restart_folder()/'service-restart.new'
    temporary.write_text(json.dumps(job))
    temporary.replace(restart_folder()/'service-restart.json')


def read_restart():
    try:
        value = json.loads((restart_folder()/'service-restart.json').read_text())
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def restore_restart_context():
    saved = read_restart()
    if saved.get('status') == 'running' and saved.get('target') == os.environ.get('MOWER_ACTIVE_ID'):
        os.environ['MOWER_RESTART_JOB'] = saved['id']


def cancel_resume():
    current = status()
    if current.get('status') == 'running' and current.get('phase') == 'restarting':
        temporary = restart_folder()/(uuid.uuid4().hex+'.cancel')
        try:
            temporary.write_text(current['id'])
            temporary.replace(restart_folder()/'service-restart-cancelled')
        finally:
            temporary.unlink(missing_ok=True)


def resume_tasks(server):
    with _lock:
        saved = read_restart()
        current = mower_package.state()
        if (saved.get('status') != 'running' or saved.get('target') != os.environ.get('MOWER_ACTIVE_ID') or
                current.get('id') != saved.get('target') or current.get('pending') or current.get('booting')):
            return
        try:
            cancelled = (restart_folder()/'service-restart-cancelled').read_text() == saved['id']
        except OSError:
            cancelled = False
        if cancelled:
            saved['resume_mower'] = False
        if saved.get('resume_mower'):
            if saved.get('resume_state') == 'starting':
                saved.update(status='failed', resume_state='failed', message='上次任务续接未能确认，请在 WebUI 手动启动。')
                save_restart(saved)
                return
            saved['resume_state'] = 'starting'
            save_restart(saved)
            try:
                # Start mode 0 loads the saved task queue without clearing its timestamps.
                if server.start.__wrapped__('0') != 'true':
                    raise ValueError('Mower 未能启动原任务')
            except Exception as exc:
                saved.update(status='failed', resume_state='failed', message=f'Mower 已更新，但任务续接失败：{exc}；请手动启动。')
                save_restart(saved)
                return
            saved['resume_state'] = 'succeeded'
        saved.update(status='succeeded', phase='completed', progress=100, restarted=True,
                     message=f"Mower {saved['version']} 已更新并重新启动" + ('，已续接原任务' if saved.get('resume_mower') else ''))
        save_restart(saved)


def restart_hint():
    return '更新包准备完成后自动重启 Mower 服务。' if automatic_restart() else '停止并重新启动手机服务后生效。'


def prepare_restart():
    import server
    from arknights_mower.utils import config
    with server.maa_maintenance_lock:
        if (server._job_running(server.maa_update_job) or server._job_running(server.maa_resource_update_job) or
                server.resource_update.running()):
            raise ValueError('其他组件正在更新，请等待完成后手动重启服务')
        running = server.mower_thread is not None and server.mower_thread.is_alive()
        if running:
            from arknights_mower.solvers.record import current_state, save_state_to_db
            saved = current_state()
        config.stop_maa.set()
        # Reuse /stop's bounded shutdown and saved-state wrapper inside this authenticated update.
        if server.stop.__wrapped__() != 'true':
            raise ValueError('Mower 任务尚未停止，未重启服务')
        if running and saved is not None and not save_state_to_db(saved):
            raise ValueError('任务状态保存失败，未重启服务')
        return running


def prefs():
    try: data = json.loads((folder()/'settings.json').read_text())
    except (OSError, ValueError): data = {}
    return {'channel': data.get('channel') if data.get('channel') in ('stable', 'beta', 'dev') else 'beta',
            'auto_check': bool(data.get('auto_check')), 'auto_update': False, 'background': False}


def save_settings(data):
    if not isinstance(data, dict) or set(data) - set(prefs()) or data.get('channel') not in ('stable', 'beta', 'dev'):
        raise ValueError('请选择正式版、公测版或开发版')
    if any(type(data.get(k, False)) is not bool for k in ('auto_check', 'auto_update', 'background')):
        raise ValueError('设置格式错误')
    if data.get('auto_update') or data.get('background'):
        raise ValueError('此安装器不支持自动更新或后台安装')
    current = {**prefs(), **data}
    temporary = folder()/'settings.new'; temporary.write_text(json.dumps(current)); temporary.replace(folder()/'settings.json')
    return {'ok': True}


def info():
    from arknights_mower import __version__
    return {'ok': True, 'deployment': 'release', 'platform': 'android', 'version': __version__,
            'settings': prefs(), 'blockers': [], 'instances': [{'name': '本机', 'running': True}],
            'force_supported': False, 'manual_supported': True, 'source_remotes': [],
            'capabilities': {'auto_update': False, 'silent_restart': False, 'dev_update': True, 'service_restart': automatic_restart()},
            'channels': [{'label': '正式版', 'value': 'stable', 'description': '最新正式 Release'},
                         {'label': '公测版', 'value': 'beta', 'description': '公测版和更新的正式版'},
                         {'label': '开发版', 'value': 'dev', 'description': 'Android Nightly、更新的公测版和正式版；APK 和 MAA 保持不变。'}],
            'releases_url': f'https://github.com/{OTA_REPO}/releases', 'last_check': _last_check,
            'manual_label': '点击或拖入 Mower 完整包、OTA 差异包、MAA 核心或兼容 Python 更新包',
            'manual_hint': 'OTA 差异包须从当前 Android Mower 版本出发；导入和校验无需连接 GitHub。',
            'install_label': '导入并安装', 'python': python_package.info(),
            'component_updates': [{'label':'内置 Mower 恢复','endpoint':'/android/mower-recovery','check':False,
                                  'hint':'恢复 APK 内置 Mower，停止并重新启动服务后生效。配置和 MAA 不受影响。'}]}


def status():
    result = dict(_job)
    saved = read_restart()
    if (saved.get('id') and
            (result.get('status') == 'idle' or result.get('id') == saved['id'])):
        result = saved
        current = mower_package.state()
        if (saved.get('target') == os.environ.get('MOWER_ACTIVE_ID') == current.get('id') and
                not current.get('pending') and not current.get('booting')):
            if saved.get('resume_state') == 'failed':
                result = {**saved, 'restarted': True}
            elif not saved.get('resume_mower') or saved.get('resume_state') == 'succeeded':
                result = {**saved, 'status': 'succeeded', 'phase': 'completed', 'progress': 100,
                          'restarted': True, 'message': saved.get('message') if saved.get('status') == 'succeeded'
                          else f"Mower {saved['version']} 已更新并重新启动"}
        elif current.get('rollback') or (current.get('id') != saved.get('target') and not current.get('pending')):
            result = {**saved, 'status': 'failed', 'message': '新版本未能启动，已恢复原版本；请查看手机诊断日志。'}
    return {**result, 'last_check': _last_check}


def release_index(channel):
    response = requests.get(f'{OTA_INDEX_URL}/{channel}.json', timeout=30)
    response.raise_for_status()
    index = response.json()
    if (not isinstance(index, dict) or index.get('schema') != 1 or
            not isinstance(index.get('version'), str) or
            not VERSION_RE.fullmatch(index['version']) or
            not isinstance(index.get('full_assets'), list) or
            not isinstance(index.get('ota_assets'), list) or
            any(not isinstance(item, dict) for item in index['full_assets'] + index['ota_assets'])):
        raise ValueError('MowerRelease 版本索引格式错误')
    actual = 'dev' if NIGHTLY_RE.fullmatch(index['version']) else 'beta' if '-' in index['version'] else 'stable'
    if actual != channel:
        raise ValueError('MowerRelease 版本索引与渠道不一致')
    return index


def choose_asset(release, *, repo=OTA_REPO):
    version = release.get('version') or release['tag_name']
    name = f"arknights-mower_{version.removeprefix('v')}_android_arm64.zip"
    asset = next((a for a in release.get('full_assets', release.get('assets', []))
                  if a.get('name') == name), None)
    digest = asset.get('digest') if asset else None
    url = (asset.get('url') or asset.get('browser_download_url')) if asset else None
    if (not asset or not isinstance(digest, str) or not re.fullmatch(r'sha256:[a-f0-9]{64}', digest) or
            not isinstance(url, str) or not url.startswith(f'https://github.com/{repo}/releases/download/') or
            type(asset.get('size')) is not int or asset['size'] <= 0):
        raise ValueError('此发行版缺少可校验的 Android Mower 更新包')
    return {**asset, 'browser_download_url': url}


def latest_release_index(channel):
    selected = release_index(channel)
    for promoted in {'dev': ('beta', 'stable'), 'beta': ('stable',)}.get(channel, ()):
        try:
            candidate = release_index(promoted)
            if version_key(candidate['version']) <= version_key(selected['version']):
                continue
            choose_asset(candidate)
        except (requests.RequestException, ValueError):
            continue  # Optional channels may have no package or index yet.
        selected = candidate
    return selected


def choose_ota_asset(target, current, full_size, release=None):
    active = mower_package.state()
    base_id = active.get('id')
    if (not mower_package.valid_id(base_id) or active.get('pending') or
            active.get('version') != current or
            not (mower_package.folder()/base_id/'mower-android.json').is_file()):
        return None
    name = f"arknights-mower-ota_{current.removeprefix('v')}_to_{target.removeprefix('v')}_android_arm64.zip"
    if release is None:
        try:
            response = requests.get(f'https://api.github.com/repos/{OTA_REPO}/releases/tags/{target}', timeout=10)
            response.raise_for_status()
            release = response.json()
        except (requests.RequestException, ValueError):
            return None
    if not isinstance(release, dict) or release.get('draft') or (release.get('version') or release.get('tag_name')) != target:
        return None
    asset = next((item for item in release.get('ota_assets', release.get('assets', [])) if item.get('name') == name), None)
    if (not asset or not re.fullmatch(r'sha256:[a-f0-9]{64}', asset.get('digest') or '') or
            not isinstance(asset.get('size'), int) or not 0 < asset['size'] < full_size or
            not (asset.get('url') or asset.get('browser_download_url') or '').startswith(
                f'https://github.com/{OTA_REPO}/releases/download/{target}/')):
        return None
    return {'asset': {**asset, 'browser_download_url': asset.get('url') or asset.get('browser_download_url')},
            'base_id': base_id, 'from_version': current}


def download_asset(asset, label):
    global _job
    digest = asset['digest'][7:]
    path = folder()/(digest+'.zip')
    size = 0; sha = hashlib.sha256()
    try:
        with requests.get(asset['browser_download_url'], stream=True, timeout=(15, 60)) as response:
            response.raise_for_status()
            with path.open('wb') as out:
                for chunk in response.iter_content(256*1024):
                    size += len(chunk)
                    if size > MAX_UPLOAD: raise ValueError('安装包超过大小限制')
                    out.write(chunk); sha.update(chunk)
                    _job.update(progress=min(99, size*100//max(1, asset['size'])),
                                current=size, total=asset['size'], message=f'正在下载 {label}')
        if size != asset['size'] or sha.hexdigest() != digest:
            raise ValueError(f'{label} 更新包 SHA256 或大小不一致')
        return path
    except Exception:
        path.unlink(missing_ok=True)
        raise


def check(channel):
    global _last_check
    from arknights_mower.utils.software_update import choose_release
    if channel not in ('stable', 'beta', 'dev'): raise ValueError('请选择正式版、公测版或开发版')
    try:
        index = latest_release_index(channel)
    except (requests.RequestException, ValueError) as error:
        if channel != 'stable':
            raise ValueError('MowerRelease 版本索引暂时不可用，请稍后重试') from error
        index = None  # The current stable Latest has no installable package or index.
    if index is not None:
        release = {'tag_name': index['version'], 'html_url': index['source_release'],
                   'body': index.get('notes') or '',
                   'assets': [{**item, 'browser_download_url': item.get('url')} for item in index['full_assets']]}
    else:
        endpoint = f'https://api.github.com/repos/{REPO}/releases'
        response = requests.get(endpoint+'/latest', timeout=30)
        response.raise_for_status()
        releases = [response.json()]
        releases = [r for r in releases if not re.search(r'dev|nightly|snapshot', r.get('tag_name', ''), re.I)]
        releases = [r for r in releases if any(a['name'] == f"arknights-mower_{r['tag_name'].removeprefix('v')}_android_arm64.zip" for a in r.get('assets', []))]
        if not releases: raise ValueError('主仓库尚未发布此渠道的 Android Mower 更新包，当前版本可继续使用')
        release = choose_release(releases, channel)
    asset = choose_asset(release, repo=OTA_REPO if index is not None else REPO)
    ident = uuid.uuid4().hex
    from arknights_mower import __version__ as current
    _plans[ident] = {'asset': asset, 'version': release['tag_name']}
    target_version = release['tag_name'].removeprefix('v')
    current_version = current.split('+', 1)[0].removeprefix('v')
    available = (version_key(target_version) > version_key(current_version) or
                 channel == 'dev' and version_key(target_version) == version_key(current_version)
                 and target_version != current_version)
    if available:
        ota = choose_ota_asset(release['tag_name'], current, asset['size'], release=index)
        if ota: _plans[ident]['ota'] = ota
    _last_check = {'ok': True, 'check_id': ident, 'channel': channel, 'checked_at': time.time(),
        'version': release['tag_name'], 'available': available,
        'downgrade': False, 'url': release['html_url'], 'notes': release.get('body', ''),
        'confirm_title': '确认下载安装？', 'confirm_message': '下载并校验 Mower 更新包。'+restart_hint()+'APK 和 MAA 保持不变。'}
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
                downgrade = version_key(meta['version']) < version_key(__version__)
                kind, version = 'mower', meta['version']
                message = '更新 Mower。'+restart_hint()+'APK 和 MAA 保持不变。'
            elif 'ota.json' in names:
                with zipfile.ZipFile(target) as archive:
                    if archive.getinfo('ota.json').file_size > 16 * 1024**2:
                        raise ValueError('OTA 清单过大')
                    meta = json.loads(archive.read('ota.json'))
                from arknights_mower import __version__
                active = mower_package.state()
                base_id = active.get('id')
                if (not isinstance(meta, dict) or meta.get('kind') != 'mower-ota' or
                        meta.get('format') not in (1, 2) or meta.get('platform') != 'android' or
                        meta.get('arch') != 'arm64' or not isinstance(meta.get('to'), str) or
                        not VERSION_RE.fullmatch(meta['to']) or
                        meta.get('from') != __version__.split('+', 1)[0].removeprefix('v') or
                        not mower_package.valid_id(base_id) or active.get('pending') or
                        active.get('version') != __version__ or
                        not (mower_package.folder()/base_id/'mower-android.json').is_file()):
                    raise ValueError('OTA 差异包起点、平台或当前 Mower 安装不匹配')
                downgrade = version_key(meta['to']) < version_key(__version__)
                kind, version = 'mower-ota', 'v' + meta['to']
                message = '从本地 OTA 差异包重建 Mower，校验失败时保留当前版本；无需连接 GitHub。'+restart_hint()
            else: raise ValueError('请选择 Android Mower 更新包、官方 MAA 核心或兼容 Python 包')
        _plans[ident] = {'path': target, 'sha256': digest, 'filename': upload.filename, 'kind': kind, 'version': version, 'downgrade': downgrade}
        if kind == 'mower-ota':
            _plans[ident].update(base_id=base_id, from_version=meta['from'])
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
    current = status()
    if current.get('status') == 'running' and current.get('phase') == 'restarting':
        raise ValueError('Mower 服务正在重启，请等待完成')
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
        reconstructed = None
        restarting = False
        try:
            result = None
            if plan.get('kind') == 'mower-ota':
                active = mower_package.state()
                if active.get('id') != plan['base_id'] or active.get('pending'):
                    raise ValueError('本地 Mower 版本已改变，请重新导入 OTA')
                with path.open('rb') as stream:
                    if hashlib.file_digest(stream, 'sha256').hexdigest() != plan['sha256']:
                        raise ValueError('OTA 内容已改变，请重新导入')
                reconstructed = folder()/(uuid.uuid4().hex+'.reconstructed.zip')
                mower_ota.reconstruct(path, mower_package.folder()/plan['base_id'],
                                      reconstructed, from_version=plan['from_version'],
                                      to_version=plan['version'])
                result = mower_package.install(reconstructed)
            if 'asset' in plan:
                if 'ota' in plan:
                    try:
                        ota = plan['ota']
                        active = mower_package.state()
                        if active.get('id') != ota['base_id'] or active.get('pending'):
                            raise ValueError('本地 Mower 版本已改变')
                        path = download_asset(ota['asset'], 'Mower OTA')
                        reconstructed = folder()/(uuid.uuid4().hex+'.reconstructed.zip')
                        mower_ota.reconstruct(
                            path, mower_package.folder()/ota['base_id'], reconstructed,
                            from_version=ota['from_version'], to_version=plan['version'])
                        result = mower_package.install(reconstructed)
                    except Exception as exc:
                        _job.update(message=f'OTA 不可用（{exc}），改用完整 Mower 包')
                        if path is not None: path.unlink(missing_ok=True)
                        path = None
                        if reconstructed is not None: reconstructed.unlink(missing_ok=True)
                        reconstructed = None
                if result is None:
                    path = download_asset(plan['asset'], 'Mower')
                    plan.update(kind='mower', sha256=plan['asset']['digest'][7:])
            if result is None:
                with path.open('rb') as stream: actual = hashlib.file_digest(stream, 'sha256').hexdigest()
                if actual != plan['sha256']: raise ValueError('更新包内容已改变，请重新导入')
            if result is None and plan['kind'] == 'mower':
                result = mower_package.install(path)
            elif result is None and plan['kind'] == 'python': result = python_package.install(path)
            elif result is None:
                import server
                from mower_android.managed import import_component
                with server.maa_maintenance_lock:
                    if server._job_running(server.maa_update_job) or server._job_running(server.maa_resource_update_job):
                        raise ValueError('MAA 正在更新，请等待完成后重试')
                    result = import_component(path, plan['filename'])
            if (plan.get('kind') in ('mower', 'mower-ota') or 'asset' in plan) and automatic_restart():
                target = mower_package.state()['id']
                _job.update(phase='restarting', progress=100, target=target, version=result['version'],
                            message='更新包已准备完成，正在重新启动 Mower 服务')
                restarting = True
                _job['resume_mower'] = prepare_restart()
                save_restart(_job)
                Bridge(timeout=10).call('mower_restart', id=target)
            else:
                _job.update(status='succeeded', progress=100, message=result.get('message', '已完成'))
        except Exception as exc:
            _job.update(status='failed', message=(f'更新包已准备完成，但自动重启未完成：{exc}；请在手机停止并重新启动服务。'
                        if restarting else str(exc)))
            if restarting: save_restart(_job)
        finally:
            if path is not None and plan.get('kind') != 'apk': path.unlink(missing_ok=True)
            if reconstructed is not None: reconstructed.unlink(missing_ok=True)
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
