"""WebUI discovery for the independently hot-loaded Android MAA adapter."""
import hashlib
import json
import os
import re
import tempfile
import threading
import time
from pathlib import Path
import requests
from mower_android import python_package

REPO = 'ALEXsun0/arknights-mower-android'
INTERVAL = 6 * 60 * 60
_lock = threading.RLock()


def settings_file():
    return Path(os.environ.get('MOWER_DATA_DIR', '/mower-data'))/'maa-python-update.json'


def state():
    try:
        value = json.loads(settings_file().read_text())
        return value if isinstance(value,dict) else {}
    except (OSError,ValueError): return {}


def save(value):
    target=settings_file(); target.parent.mkdir(parents=True,exist_ok=True)
    temporary=target.with_suffix('.new'); temporary.write_text(json.dumps(value)); temporary.replace(target)


def info():
    data=state()
    installed = python_package.info()
    latest = dict(data.get('latest', {}))
    if latest:
        # Manual import/reset can change the active adapter after the last check.
        latest['available'] = bool(latest.get('compatible') and latest.get('sha256') != installed.get('sha256'))
    return {'ok':True,'installed':installed,'auto_check':data.get('auto_check',True),'latest':latest}


def component_info():
    return {'label': 'MAA Python 兼容接口', 'endpoint': '/android/python-update',
            'check': True, 'combined_check': True,
            'hint': '与 MAA 一起检查，仅接口内容变化时提示更新。现有任务保持原接口，新实例使用新接口；手动导入请使用软件更新中的拖拽入口。'}


def fetch(item, limit):
    if not re.fullmatch('sha256:[a-f0-9]{64}',item.get('digest') or ''):
        raise ValueError('GitHub 更新包校验信息尚未就绪')
    url=item.get('browser_download_url','')
    if not url.startswith(f'https://github.com/{REPO}/releases/download/'):
        raise ValueError('更新包来源错误')
    size=0; chunks=[]
    with requests.get(url,stream=True,timeout=(10,30)) as response:
        response.raise_for_status()
        for chunk in response.iter_content(65536):
            size+=len(chunk)
            if size>limit: raise ValueError('更新包超过大小限制')
            chunks.append(chunk)
    data=b''.join(chunks)
    if size!=item['size'] or hashlib.sha256(data).hexdigest()!=item['digest'][7:]:
        raise ValueError('更新包大小或校验值不符')
    return data


def check():
    from datetime import datetime
    with _lock:
        response=requests.get(f'https://api.github.com/repos/{REPO}/releases',params={'per_page':100},timeout=(10,30))
        response.raise_for_status()
        candidates=[r for r in response.json() if not r.get('draft') and
                    re.fullmatch(r'v?\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.\d+)?',r.get('tag_name','')) and
                    any(a['name']=='android-release.json' for a in r.get('assets',[]))]
        if not candidates: raise ValueError('暂无可用的 Android 发行版')
        release=max(candidates,key=lambda r: datetime.fromisoformat(r['published_at']))
        descriptor=next(a for a in release['assets'] if a['name']=='android-release.json')
        meta=json.loads(fetch(descriptor,1024**2))['maa_python']
        if not re.fullmatch('[a-f0-9]{64}',meta.get('sha256','')):
            raise ValueError('此旧发行版尚未提供 Python 内容版本，当前接口可继续使用')
        item=next(a for a in release['assets'] if a['name']==meta['name'])
        compatible=meta.get('bridge_protocol')==1 and type(meta.get('min_apk')) is int and meta['min_apk']<=int(os.environ.get('MOWER_APK_CODE','5'))
        result={'version':meta['version'],'sha256':meta['sha256'],'available':compatible and meta['sha256']!=python_package.info().get('sha256'),
                'compatible':compatible,'compatibility':meta.get('compatibility',{}),'asset':item,
                'url':release['html_url'],'checked_at':time.time()}
        data=state(); data['latest']=result; save(data)
        return {**info(),'latest':result}


def install():
    with _lock:
        latest=state().get('latest',{})
        if not latest.get('compatible'): raise ValueError('请先检查兼容更新；必要时更新 APK')
        if latest['sha256']==python_package.info().get('sha256'):
            return {'ok':True,'message':'兼容接口内容没有变化，无需更新'}
        data=fetch(latest['asset'],3*1024**2)
        with tempfile.TemporaryDirectory() as temp:
            package=Path(temp)/'adapter.zip'; package.write_bytes(data)
            # Inspect before activating; online descriptor and archive must describe the same code.
            inspected=python_package.install(package,root=Path(temp)/'inspect')
            if inspected['sha256']!=latest['sha256'] or inspected['version']!=latest['version']:
                raise ValueError('Python 包与发行清单不一致')
            result=python_package.install(package)
        data=state(); data['latest']['available']=False; save(data)
        return {'ok':True,**result}


def register_routes(app):
    from flask import request,abort

    @app.after_request
    def attach_maa_components(response):
        # The Android launcher registers this provider after its authentication hook.
        # Desktop servers never import/register this extension.
        if request.path == '/maa-update/info' and response.status_code == 200 and response.is_json:
            data = response.get_json()
            if isinstance(data, dict) and data.get('ok'):
                data['component_updates'] = [component_info()]
                response.set_data(app.json.dumps(data))
        return response

    @app.route('/android/python-update',methods=['GET','POST'])
    def route():
        if request.method=='GET': return info()
        if request.headers.get('X-Mower-Update') != '1': abort(403)
        data=request.get_json(silent=True) or {}
        try:
            if data=={'action':'check'}: return check()
            if data=={'action':'install'}: return install()
            if data=={'action':'reset'}: return {'ok':True,**python_package.reset()}
            if set(data)=={'auto_check'} and type(data['auto_check']) is bool:
                with _lock:
                    current=state(); current.update(data); save(current)
                return info()
            raise ValueError('更新请求格式错误')
        except Exception as exc: return {'ok':False,'message':str(exc)},400


def auto_check_once(notify):
    with _lock:
        data=state()
        if not data.get('auto_check',True) or time.time()-data.get('latest',{}).get('checked_at',0)<INTERVAL: return
        result=check()['latest']
        data=state()
        if result['available'] and data.get('notified')!=result['sha256']:
            notify(result['version'])
            data['notified']=result['sha256']; save(data)


def start_auto_check():
    def run():
        from mower_android.bridge import Bridge
        from arknights_mower.utils.log import logger
        while True:
            try: auto_check_once(lambda version: Bridge(timeout=10).call('python_update_available',version=version))
            except Exception as exc: logger.debug(f'MAA Python 更新检查暂未完成：{exc}')
            time.sleep(INTERVAL)
    threading.Thread(target=run,name='maa-python-update',daemon=True).start()
