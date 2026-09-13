"""Android owns connection settings, including values imported from desktop backups."""
import hashlib
import json
import math
import os
import shutil
import tempfile
import zipfile
from pathlib import Path
from mower_android.backup_cleanup import component_transaction

MAA_PATH = Path('/mower-data/maa')
COMPONENT = Path('/mower-data/maa-component.zip')


def native_theme():
    try:
        value = json.loads((Path(os.environ.get('MOWER_DATA_DIR', '/mower-data')) / 'native-appearance.json').read_text())['theme']
        return value if value in ('light', 'dark') else 'light'
    except (OSError, ValueError, KeyError, TypeError):
        return 'light'


def screenshot_hours():
    """Default to memory-only previews; Android owns screenshot persistence."""
    try:
        path = Path(os.environ.get('MOWER_DATA_DIR', '/mower-data')) / 'native-screenshot.json'
        value = json.loads(path.read_text())['hours']
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            return 0.0
        return float(value)
    except (OSError, ValueError, KeyError, TypeError, OverflowError):
        return 0.0


def prepare_files():
    pending = COMPONENT.with_name('maa-bundled-pending')
    generation = pending.read_text().strip() if pending.is_file() else None
    marker = MAA_PATH / '.apk-bundle-generation'
    if (MAA_PATH / '.mower-android.json').is_file():
        if generation is None:
            return
        if marker.is_file() and marker.read_text() == generation:
            pending.unlink()
            return
    expected = COMPONENT.with_suffix('.sha256').read_text().strip()
    if hashlib.sha256(COMPONENT.read_bytes()).hexdigest() != expected: raise RuntimeError('MAA 组件校验失败')
    stage = MAA_PATH.with_name('maa-install'); shutil.rmtree(stage, ignore_errors=True); stage.mkdir()
    with zipfile.ZipFile(COMPONENT) as archive:
        for info in archive.infolist():
            path = stage / info.filename
            if not path.resolve().is_relative_to(stage.resolve()): raise ValueError('Invalid component path')
            if info.is_dir(): path.mkdir(parents=True, exist_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as source, path.open('wb') as output: shutil.copyfileobj(source, output)
    if generation is not None:
        (stage / '.apk-bundle-generation').write_text(generation)
    if MAA_PATH.exists():
        # Settings survive; cached hot resources must not override APK resources.
        for name in ('config', 'config.json'):
            source = MAA_PATH / name
            if source.is_dir(): shutil.copytree(source, stage / name, dirs_exist_ok=True)
            elif source.is_file(): shutil.copy2(source, stage / name)
        from arknights_mower.utils.maa_update import replace_with_backup
        with tempfile.TemporaryDirectory(prefix='.maa-bundle-', dir=MAA_PATH.parent) as work:
            replace_with_backup(stage, MAA_PATH, Path(work))
    else: stage.rename(MAA_PATH)
    if generation is not None: pending.unlink()


def normalize(data):
    """Return a copy so callers never mutate a saved desktop configuration in place."""
    result = dict(data)
    result['screenshot'] = screenshot_hours()
    result['theme'] = native_theme()
    result.update(adb='Android', maa_adb_path='Android', maa_path=str(MAA_PATH),
                  maa_conn_preset='Android', maa_touch_option='Android', touch_method='scrcpy',
                  close_simulator_when_idle=False, fix_mumu12_adb_disconnect=False)
    result['simulator'] = {'name':'', 'simulator_folder':'', 'index':'0', 'wait_time':0, 'hotkey':''}
    result['tap_to_launch_game'] = {'enable':False, 'mode':'adb', 'x':0, 'y':0, 'command':''}
    result['droidcast'] = {'enable':False, 'rotate':False}
    result['mumu12IPC'] = False
    result['custom_screenshot'] = {'enable':False, 'command':''}
    web = dict(result.get('webview', {}))
    if 'MOWER_WEB_TOKEN' in os.environ: web['token'] = os.environ['MOWER_WEB_TOKEN']
    if 'MOWER_WEB_PORT' in os.environ: web['port'] = int(os.environ['MOWER_WEB_PORT'])
    result['webview'] = web
    return result


@component_transaction
def pack_component(target, destination=COMPONENT):
    """Only official libraries/resources/metadata enter the privileged component store."""
    target = Path(target); destination = Path(destination)
    from mower_android.ocr_models import prepare_models
    prepare_models(target / 'resource')
    if (target / 'cache/resource').is_dir(): prepare_models(target / 'cache/resource')
    temporary = destination.with_suffix('.zip.new')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for file in target.rglob('*'):
            relative = file.relative_to(target)
            if file.name == 'inference.onnx' and file.parent.name in ('det', 'rec') and all((file.parent/f'{file.parent.name}.ncnn.{ext}').is_file() for ext in ('param','bin')):
                continue
            if file.is_file() and (relative.parts[0] in ('resource', 'cache') or (len(relative.parts) == 1 and (file.name.endswith('.so') or file.name == '.mower-android.json'))):
                archive.write(file, str(relative))
    digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
    temporary.replace(destination)
    destination.with_suffix('.sha256').write_text(digest+'\n')


def installed_version(target=MAA_PATH):
    try: return json.loads((Path(target)/'.mower-android.json').read_text())['version']
    except (OSError, ValueError, KeyError): return ''


@component_transaction
def install_update(target, callback=None, session=None, source='github', mirror_token='', system='android', machine=None, channel='stable'):
    from arknights_mower.utils import maa_update as updater
    import tempfile
    if Path(target) != MAA_PATH: raise updater.MaaUpdateError('安卓版自动管理 MAA 路径')
    if source not in ('github','mirrorchyan'): raise updater.MaaUpdateError('未知的 MAA 更新源')
    release = (get_mirror_release(mirror_token,session,channel) if source == 'mirrorchyan' else updater.get_latest_release(session, system='android', machine='arm64', channel=channel))
    if not release.runtime.sha256: raise updater.MaaUpdateError('官方组件缺少 SHA256，暂不安装')
    with tempfile.TemporaryDirectory(prefix='.maa-update-', dir=MAA_PATH.parent) as work:
        work = Path(work); package = work / release.runtime.name; stage = work / 'new'; stage.mkdir()
        downloaded = updater.download_asset(release.runtime, package, session=session, callback=callback,
                                            message='正在下载官方 Android ARM64 MAA 组件')
        updater.extract_linux_package(package, stage, callback=callback, android=True)
        (stage/'.mower-android.json').write_text(json.dumps({'version':release.tag,'asset_sha256':release.runtime.sha256,'platform':'android','arch':'arm64'}))
        preserved = updater.preserve_user_data(MAA_PATH, stage, callback=callback)
        # Finish packing before changing the active installation; current service keeps its loaded version.
        packed = work/'component.zip'; pack_component(stage, packed)
        backup = updater.replace_with_backup(stage, MAA_PATH, work)
        packed.replace(COMPONENT); packed.with_suffix('.sha256').replace(COMPONENT.with_suffix('.sha256'))
    return {'version':release.tag, 'source':source, 'channel':channel, 'operation':'update', 'platform':'android', 'arch':'arm64',
            'target':str(MAA_PATH), 'backup':str(backup), 'runtime_downloaded':downloaded,'python_downloaded':0,'python_full_size':0,
            'preserved':preserved, 'restart_required':True, 'message':'官方 Android MAA 已更新，停止并重新启动服务后生效'}


def get_release(session=None, channel='stable'):
    from arknights_mower.utils import maa_update as updater
    release = updater.get_latest_release(session, system='android', machine='arm64', channel=channel)
    if not release.runtime.sha256: raise updater.MaaUpdateError('官方组件缺少 SHA256 校验信息')
    return release


def get_mirror_release(token, session=None, channel='stable'):
    from arknights_mower.utils.maa_update import MaaUpdateError
    raise MaaUpdateError('Android 暂不支持 Mirror酱，请使用 GitHub 官方源')


@component_transaction
def import_component(package, filename, callback=None):
    """Import an official Android tarball using the same staged installer as online updates."""
    import re
    import tempfile
    from arknights_mower.utils import maa_update as updater
    match=re.fullmatch(r'MAAComponent-(v[0-9]+\.[0-9]+\.[0-9]+(?:-(?:beta|alpha|rc)\.[0-9]+)?)-android-arm64\.tar\.gz',filename)
    if not match: raise updater.MaaUpdateError('请保留官方 MAAComponent-v版本-android-arm64.tar.gz 文件名')
    version=match[1]
    with open(package,'rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
    # Bundled release can be imported offline; other versions verify GitHub's asset digest.
    lock_path=Path(__file__).with_name('maa-component-lock.json')
    lock=json.loads(lock_path.read_text()) if lock_path.exists() else {}
    if lock.get('version')==version:
        expected=lock['sha256']
    else:
        import requests
        try:
            response=requests.get('https://api.github.com/repos/MaaAssistantArknights/MaaAssistantArknights/releases/tags/'+version,timeout=30)
            response.raise_for_status()
            release=updater.parse_release(response.json(),system='android',machine='arm64')
            expected=release.runtime.sha256
        except Exception: raise updater.MaaUpdateError('无法读取该版本的官方校验值；请恢复 GitHub 连接后重试。内置版本可离线导入。') from None
    if not expected or digest!=expected:raise updater.MaaUpdateError('MAA 官方组件 SHA256 校验失败')
    with tempfile.TemporaryDirectory(prefix='.maa-import-',dir=MAA_PATH.parent) as temp:
        work=Path(temp);stage=work/'new';stage.mkdir()
        updater.extract_linux_package(package,stage,callback=callback,android=True)
        (stage/'.mower-android.json').write_text(json.dumps({'version':version,'asset_sha256':digest,'platform':'android','arch':'arm64'}))
        updater.preserve_user_data(MAA_PATH,stage,callback=callback)
        packed=work/'component.zip';pack_component(stage,packed)
        updater.replace_with_backup(stage,MAA_PATH,work)
        packed.replace(COMPONENT);packed.with_suffix('.sha256').replace(COMPONENT.with_suffix('.sha256'))
    return {'kind':'maa-core','version':version,'message':'官方 Android MAA 核心已导入，停止并重新启动服务后生效'}
