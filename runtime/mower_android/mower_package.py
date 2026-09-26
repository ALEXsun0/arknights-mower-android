"""Transactional Mower updates, optionally including their Python runtime."""
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tempfile
import zipfile
import functools
import threading
import logging
from pathlib import Path, PurePosixPath

RUNTIME_API = 1
REQUIRED = {'mower/server.py', 'mower/arknights_mower/__init__.py', 'mower/ui/dist/index.html', 'mower/requirements.txt'}
_lock = threading.RLock()


def serialized(function):
    @functools.wraps(function)
    def locked(*args, **kwargs):
        with _lock:
            return function(*args, **kwargs)
    return locked


def folder():
    root = Path(os.environ.get('MOWER_DATA_DIR', '/mower-data')) / 'mower-programs'
    root.mkdir(parents=True, exist_ok=True)
    return root


def state():
    try:
        value = json.loads((folder()/'active.json').read_text())
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError): return {}


def save(value):
    value = dict(value)
    generation = os.environ.get('MOWER_APK_GENERATION')
    if generation:
        value.setdefault('apk_generation', generation)
    root = folder()
    temp = root/'active.new'; temp.write_text(json.dumps(value)); temp.replace(root/'active.json')


def valid_id(value):
    return isinstance(value, str) and re.fullmatch(r'[a-f0-9]{64}', value) is not None


def inspect(package, *, apk_code=None):
    with zipfile.ZipFile(package) as z:
        entries = z.infolist()
        names = {i.filename for i in entries}
        if len(names) != len(entries) or len(entries) > 30000 or sum(i.file_size for i in entries) > 1024*1024**2:
            raise ValueError('Mower 更新包文件重复或超过大小限制')
        if not REQUIRED.issubset(names) or 'mower-android.json' not in names:
            raise ValueError('请选择主仓库的 Android Mower 更新包')
        if z.getinfo('mower-android.json').file_size > 65536: raise ValueError('更新清单过大')
        meta = json.loads(z.read('mower-android.json'))
        if not isinstance(meta, dict) or (meta.get('kind'), meta.get('platform'), meta.get('arch')) != ('mower-android', 'android', 'arm64') or meta.get('format') not in (1, 2):
            raise ValueError('Mower 更新包格式不兼容')
        if meta.get('runtime_api') != RUNTIME_API or (meta['format'] == 1 and meta.get('python') != '3.12'):
            raise ValueError('此 Mower 需要新的宿主运行环境，请先更新 APK')
        if meta['format'] == 2:
            runtime = meta.get('runtime')
            minimum = meta.get('min_apk')
            if (type(minimum) is not int or minimum < 29 or
                    not re.fullmatch(r'3\.\d+', str(meta.get('python', ''))) or
                    not isinstance(runtime, dict) or runtime.get('file') != 'python-runtime.zip.xz' or
                    not valid_id(runtime.get('sha256')) or type(runtime.get('unpacked_size')) is not int or
                    not 0 < runtime['unpacked_size'] <= 2 * 1024**3):
                raise ValueError('Python 运行环境清单无效')
            host = int(os.environ.get('MOWER_APK_CODE', '0')) if apk_code is None else apk_code
            if host < minimum:
                raise ValueError(f'此更新包包含 Python 运行环境，请先更新 Android APK（最低版本代码 {minimum}）')
            if runtime['file'] not in names:
                raise ValueError('更新包缺少 Python 运行环境')
            with z.open(runtime['file']) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != runtime['sha256']:
                    raise ValueError('Python 运行环境校验失败')
        if not re.fullmatch(r'\d+\.\d+\.\d+(?:-alpha\.\d+(?:\.g[0-9a-f]{8})?)?', str(meta.get('version', ''))) or not re.fullmatch(r'[a-f0-9]{40}', str(meta.get('revision', ''))):
            raise ValueError('Mower 版本清单无效')
        for entry in entries:
            name = entry.filename; parts = PurePosixPath(name).parts
            if name.startswith('/') or '\\' in name or any(p in ('..', '.') for p in name.split('/')) or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError('Mower 更新包包含非法路径')
            if name != 'mower-android.json' and not (meta['format'] == 2 and name == 'python-runtime.zip.xz'):
                if len(parts) < 2 or parts[0] != 'mower' or parts[1] not in {'arknights_mower','ui','server.py','LICENSE','CHANGELOG.md','logo.png','requirements.txt'}:
                    raise ValueError('更新包不能覆盖宿主或用户文件')
                if parts[1] == 'ui' and not (
                    name.startswith('mower/ui/dist/') or
                    name == 'mower/ui/src/pages/basement_skill/skill.json'
                ):
                    raise ValueError('更新包仅允许已构建的 WebUI 与基建技能数据')
            if entry.flag_bits & 1: raise ValueError('不支持加密的更新包')
        version_source = z.read('mower/arknights_mower/__init__.py').decode()
        if f'__version__ = "{meta["version"]}"' not in version_source: raise ValueError('程序版本与清单不一致')
        for entry in entries:
            if entry.filename.endswith('.py') and meta['python'] == f'{sys.version_info.major}.{sys.version_info.minor}':
                compile(z.read(entry), entry.filename, 'exec')
        return meta


@serialized
def install(package):
    package = Path(package); meta = inspect(package)
    with package.open('rb') as stream: ident = hashlib.file_digest(stream, 'sha256').hexdigest()
    root = folder(); target = root/ident
    if not target.exists():
        with tempfile.TemporaryDirectory(prefix='.install-', dir=root) as temp:
            stage = Path(temp)
            with zipfile.ZipFile(package) as z: z.extractall(stage)
            stage.rename(target)
    old = state()
    previous = os.environ.get('MOWER_ACTIVE_ID') or (old.get('id') if not old.get('pending') else old.get('previous'))
    save({'id':ident, 'version':meta['version'], 'previous':previous, 'pending':True})
    return {**meta, 'message':'Mower 更新已准备完成，停止并重新启动手机服务后生效；配套 Python 环境将自动解压，配置和 MAA 保留。' if meta['format'] == 2 else 'Mower 更新已准备完成，停止并重新启动手机服务后生效。'}


@serialized
def select_source(bundled):
    # New hosts choose the program AND interpreter before starting Python.
    # Do not treat their freshly written booting marker as a previous failure.
    if os.environ.get('MOWER_SOURCE_SELECTED') == '1':
        ident = os.environ.get('MOWER_ACTIVE_ID')
        if ident:
            if not valid_id(ident):
                raise ValueError('原生启动器选择的 Mower 版本无效')
            return folder()/ident/'mower'
        os.environ.pop('MOWER_ACTIVE_ID', None)
        return bundled
    current = state()
    generation = os.environ.get('MOWER_APK_GENERATION')
    if generation and current.get('apk_generation') != generation:
        previous = current.get('previous') if current.get('pending') or current.get('booting') else current.get('id')
        current = {'id': None, 'previous': previous, 'pending': True, 'apk_generation': generation}
        save(current)
    ident = current.get('id')
    # An earlier launch which never served the WebUI is rolled back on restart.
    if current.get('booting'):
        ident = current.get('previous'); current = {'id':ident, 'rollback':True}
        save(current)
    if valid_id(ident) and (folder()/ident/'mower/server.py').is_file():
        if current.get('pending'):
            current['booting'] = True; save(current)
        os.environ['MOWER_ACTIVE_ID'] = ident
        return folder()/ident/'mower'
    if current.get('pending'):
        current['booting'] = True; save(current)
    os.environ.pop('MOWER_ACTIVE_ID', None)
    return bundled


@serialized
def mark_ready():
    current = state()
    active = os.environ.get('MOWER_ACTIVE_ID')
    if current.get('id') != active:
        return # A newer update is staged while this older server is still running.
    if current.get('booting') or (not current.get('pending') and 'previous' in current):
        current.pop('booting', None); current.pop('pending', None)
        current.pop('previous', None); save(current)
    if current.get('pending'):
        return
    from mower_android.background_cleanup import submit
    submit("mower-programs", lambda: cleanup_ready(active))


@serialized
def cleanup_ready(active):
    current = state()
    # Recheck after queueing: an update may have been staged in the meantime.
    if current.get('id') != active or current.get('pending') or current.get('booting'):
        return
    for candidate in folder().iterdir():
        if valid_id(candidate.name) and candidate.name != active and not candidate.is_symlink():
            try:
                if candidate.is_dir(): shutil.rmtree(candidate)
            except OSError:
                logging.getLogger(__name__).warning('Mower 已启动；旧程序目录暂未清理，下次启动检查时重试')


@serialized
def reset():
    save({})
