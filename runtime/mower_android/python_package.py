"""Hot-update only the Android-compatible MAA Python adapter; never native libraries."""
import ast
import hashlib
import importlib.util
import json
import os
import re
import shutil
import tempfile
import threading
import zipfile
from pathlib import Path

API_METHODS = {'load','get_version','connect','set_instance_option','append_task','set_task_params','start','running','stop','get_tasks_list'}
ROOT = Path(os.environ.get('MOWER_DATA_DIR', '/mower-data')) / 'maa-python'
BUNDLED_META = Path(__file__).with_name('maa-python.json')
BUNDLED_VERSION = json.loads(BUNDLED_META.read_text())['version']


def info():
    try: return {**json.loads((ROOT/'active.json').read_text()), 'bundled': False}
    except (OSError,ValueError):
        return {**json.loads(BUNDLED_META.read_text()), 'bundled':True}


_install_lock = threading.RLock()

def install(path, root=None):
    with _install_lock:
        return _install(path, root)

def _install(path, root=None):
    root = Path(root or ROOT); root.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(path) as z:
        entries=z.infolist()
        if len(entries) != 2 or {i.filename for i in entries} != {'maa.py','maa-python.json'} or any(i.file_size > 1024**2 for i in entries):
            raise ValueError('请选择 Android 兼容 MAA Python 包，不接受官方 ctypes Python 包或源码压缩包')
        meta=json.loads(z.read('maa-python.json')); code=z.read('maa.py')
    if (meta.get('kind')!='mower-maa-python' or meta.get('format')!=1 or meta.get('bridge_protocol')!=1
            or type(meta.get('min_apk')) is not int or meta['min_apk']>int(os.environ.get('MOWER_APK_CODE','5'))):
        raise ValueError('MAA Python 接口与当前 Android 桥接不兼容，请先更新 APK')
    if meta.get('channel') not in ('stable','beta'):
        raise ValueError('Android 仅允许正式版和公测版更新')
    if not isinstance(meta.get('version'),str) or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9.]+)?',meta['version']):
        raise ValueError('Python 包版本格式无效')
    digest=hashlib.sha256(code).hexdigest()
    if meta.get('sha256')!=digest: raise ValueError('Python 包 SHA256 校验失败')
    tree=ast.parse(code)
    cls=next((n for n in tree.body if isinstance(n,ast.ClassDef) and n.name=='Asst'),None)
    if cls is None or not API_METHODS.issubset({n.name for n in cls.body if isinstance(n,ast.FunctionDef)}):
        raise ValueError('Python 包缺少 Mower 所需的 MAA 接口')
    compile(code,'maa.py','exec')
    target=root/(digest+'.py')
    temporary=root/'adapter.new';temporary.write_bytes(code);temporary.replace(target)
    previous=root/'active.json'
    if previous.exists(): shutil.copy2(previous,root/'previous.json')
    state={k:meta[k] for k in ('version','bridge_protocol','sha256')}
    temporary=root/'active.new';temporary.write_text(json.dumps(state));temporary.replace(previous)
    return {**state,'message':'兼容 Python 接口已导入；现有任务保持原接口，下一次创建 MAA 实例时生效'}


def reset():
    with _install_lock:
        (ROOT/'active.json').unlink(missing_ok=True)
    return {'message':'已恢复 APK 内置的 MAA Python 接口，下一次创建实例时生效'}


_cache={}
def adapter_class():
    from mower_android.maa_adapter import Asst as Bundled
    state=info()
    if state.get('bundled'): return Bundled
    digest=state.get('sha256','')
    if not re.fullmatch('[a-f0-9]{64}',digest): return Bundled
    if digest in _cache: return _cache[digest]
    try:
        file=ROOT/(digest+'.py')
        if hashlib.sha256(file.read_bytes()).hexdigest()!=digest: raise ValueError('Python adapter checksum mismatch')
        spec=importlib.util.spec_from_file_location('mower_maa_adapter_'+digest,file)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        cls=module.Asst
        if not all(callable(getattr(cls,name,None)) for name in API_METHODS): raise ValueError('Adapter interface missing')
        _cache[digest]=cls;return cls
    except Exception:
        # Invalid adapters must not break the scheduler or hide the recovery path.
        reset()
        from arknights_mower.utils.log import logger
        logger.error('导入的 MAA Python 接口加载失败，已恢复 APK 内置接口')
        return Bundled


def capture_adapter(instance):
    from mower_android.maa_adapter import Asst as Bundled
    if type(instance) is Bundled:
        return "bundled"
    with _install_lock:
        return next((digest for digest, cls in _cache.items() if type(instance) is cls), None)


def retire_adapters(verified):
    if verified is None:
        return False
    with _install_lock:
        current = info()
        active = "bundled" if current.get("bundled") else current.get("sha256")
        if active != verified:
            return False
        for path in ROOT.glob("*.py"):
            if re.fullmatch(r"[a-f0-9]{64}\.py", path.name) and path.stem != active:
                path.unlink(missing_ok=True)  # unlink never follows a symlink
        (ROOT / "previous.json").unlink(missing_ok=True)
        for digest in list(_cache):
            if digest != active:
                del _cache[digest]  # Existing instances still retain their own class.
        return True
