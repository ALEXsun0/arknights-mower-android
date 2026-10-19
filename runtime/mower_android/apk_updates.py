"""Download host APK updates through Mower's current network settings."""

import hashlib
import json
import os
import re
import threading
import time
import uuid
from pathlib import Path

import requests

REPO = 'ALEXsun0/arknights-mower-android'
MAX_APK = 768 * 1024**2
_lock = threading.RLock()
_plan = {}
_job = {'ok': True, 'status': 'idle'}


def folder():
    key = hashlib.sha256(b'/mower').hexdigest()[:20]
    path = Path(os.environ.get('MOWER_DATA_DIR', '/mower-data'))/'tmp/updates'/key/'android-apk'
    path.mkdir(parents=True, exist_ok=True)
    return path


def candidates(url, file_download):
    from arknights_mower.utils import github_download, network_settings

    settings = network_settings.get_effective_settings()
    if not file_download:
        return (url,)
    if hasattr(github_download, 'download_urls'):
        return github_download.download_urls(url, settings['github_proxy'])
    # Older OTA programs have only download_url; keep their host updater usable.
    primary = github_download.download_url(url, settings['github_proxy'])
    if settings['github_proxy']:
        return (primary,)
    return (url, github_download.download_url(url, 'https://ghfast.top/'))


def receive(url, limit, *, expected=None, output=None, file_download=True, progress=None):
    urls = candidates(url, file_download)
    deadline = time.monotonic() + (30 * 60 if output else 90)
    for index, candidate in enumerate(urls):
        response = None
        if time.monotonic() >= deadline:
            raise ValueError('更新下载超过时间限制')
        try:
            response = requests.get(candidate, stream=True, timeout=(10, 30))
            response.raise_for_status()
            digest = hashlib.sha256()
            size = 0
            chunks = []
            stream = output.open('wb') if output else None
            try:
                for chunk in response.iter_content(256 * 1024):
                    size += len(chunk)
                    if size > limit or time.monotonic() > deadline:
                        raise ValueError('更新下载超过大小或时间限制')
                    digest.update(chunk)
                    if stream:
                        stream.write(chunk)
                    else:
                        chunks.append(chunk)
                    if progress:
                        progress(size)
            finally:
                if stream:
                    stream.close()
            if expected and (size != expected['size'] or digest.hexdigest() != expected['digest'][7:]):
                raise ValueError('更新文件大小或 SHA256 校验失败')
            return b''.join(chunks) if output is None else None
        except requests.RequestException as exc:
            retryable = isinstance(exc, (requests.ConnectionError, requests.Timeout, requests.exceptions.ChunkedEncodingError)) or (
                isinstance(exc, requests.HTTPError) and response is not None and
                (response.status_code in (403, 429) or response.status_code >= 500))
            if index + 1 == len(urls) or not retryable:
                raise
        finally:
            if response is not None:
                response.close()


def asset(release, name, limit):
    matches = [item for item in release.get('assets', []) if item.get('name') == name]
    if len(matches) != 1:
        raise ValueError('发行版缺少唯一的更新文件')
    item = matches[0]
    if (not isinstance(item.get('browser_download_url'), str) or
            not item['browser_download_url'].startswith(f'https://github.com/{REPO}/releases/download/') or
            not re.fullmatch(r'sha256:[a-f0-9]{64}', item.get('digest') or '') or
            type(item.get('size')) is not int or not 0 < item['size'] <= limit):
        raise ValueError('发行文件来源、大小或校验信息无效')
    return item


def check():
    global _plan
    with _lock:
        if _job.get('status') == 'running':
            raise ValueError('APK 正在下载，请等待完成')
        # API requests use the global network proxy, never a file-proxy prefix.
        release = json.loads(receive(f'https://api.github.com/repos/{REPO}/releases/latest',
                                     2 * 1024**2, file_download=False))
        if release.get('draft') or not re.fullmatch(r'v?\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.\d+)?', release.get('tag_name', '')):
            raise ValueError('没有可用的正式或公测 APK 发行版')
        descriptor = asset(release, 'android-release.json', 1024**2)
        metadata = json.loads(receive(descriptor['browser_download_url'], 1024**2, expected=descriptor))
        apk = metadata['apk']
        if (metadata.get('format') != 1 or not re.fullmatch(r'[a-f0-9]{64}', apk.get('host_sha256', '')) or
                type(apk.get('host_version_code')) is not int or apk['host_version_code'] <= 0 or
                type(apk.get('version_code')) is not int or apk['version_code'] <= 0 or
                not isinstance(apk.get('name'), str) or not apk['name'].endswith('.apk') or
                not re.fullmatch(r'\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.\d+)?', apk.get('version', ''))):
            raise ValueError('APK 发行清单无效')
        package = asset(release, apk['name'], MAX_APK)
        _plan = {'id': uuid.uuid4().hex, 'apk': apk, 'asset': package, 'checked_at': time.monotonic()}
        return {'ok': True, 'check_id': _plan['id'], 'apk': apk}


def status():
    with _lock:
        return dict(_job)


def download(check_id):
    global _job
    with _lock:
        if _job.get('status') == 'running':
            raise ValueError('APK 正在下载，请等待完成')
        if not _plan or _plan['id'] != check_id or time.monotonic() - _plan['checked_at'] > 3600:
            raise ValueError('请重新检查 APK 更新')
        plan = dict(_plan)
        job = {'ok': True, 'id': uuid.uuid4().hex, 'status': 'running', 'progress': 0,
               'version': plan['apk']['version'], 'version_code': plan['apk']['version_code']}
        _job = job

        def run():
            item = plan['asset']
            temporary = None
            try:
                root = folder()
                for stale in root.glob('*.part'):
                    stale.unlink(missing_ok=True)
                temporary = root/(job['id']+'.part')
                def progress(size):
                    with _lock:
                        job['progress'] = min(99, size * 100 // item['size'])
                receive(item['browser_download_url'], MAX_APK, expected=item, output=temporary, progress=progress)
                ident = item['digest'][7:]
                target = root/(ident+'.apk')
                temporary.replace(target)
                for previous in root.glob('*.apk'):
                    if previous != target:
                        previous.unlink(missing_ok=True)
                with _lock:
                    job.update(status='succeeded', progress=100, sha256=ident)
            except Exception:
                # Network errors can contain proxy addresses; keep the UI error generic.
                with _lock:
                    job.update(status='failed', message='APK 下载或校验失败，请检查 Mower 网络代理设置后重新检查更新')
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)

        try:
            threading.Thread(target=run, name='android-apk-download', daemon=True).start()
        except RuntimeError:
            job.update(status='failed', message='无法启动 APK 下载，请重试')
            raise
        return dict(job)


def register_routes(app):
    from flask import abort, request

    @app.route('/android/apk-update', methods=['GET', 'POST'])
    def route():
        if request.method == 'GET':
            return status()
        if request.headers.get('X-Mower-Update') != '1':
            abort(403)
        data = request.get_json(silent=True)
        try:
            if data == {'action': 'check'}:
                return check()
            if isinstance(data, dict) and set(data) == {'action', 'check_id'} and data['action'] == 'download':
                return download(data['check_id'])
            raise ValueError('APK 更新请求格式错误')
        except ValueError as exc:
            return {'ok': False, 'message': str(exc)}, 400
        except Exception:
            return {'ok': False, 'message': 'APK 更新检查失败，请检查 Mower 网络代理设置后重试'}, 400
