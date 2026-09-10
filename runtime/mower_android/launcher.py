"""Original mower WebUI with Android-owned connections and authenticated LAN access."""
import hmac
import os
import signal
from pathlib import Path


def main():
    source = Path(__file__).resolve().parents[1]
    os.chdir(source)
    os.environ['MOWER_ANDROID'] = '1'
    os.environ.setdefault('MOWER_DATA_DIR', '/mower-data')
    Path(os.environ['MOWER_DATA_DIR']).mkdir(parents=True, exist_ok=True)
    Path(os.environ['MOWER_DATA_DIR'], 'runtime-python.pid').write_text(str(os.getpid()))
    from mower_android.managed import prepare_files, MAA_PATH
    prepare_files()
    from arknights_mower.utils import path
    path._internal_dir = source; path._install_dir = source
    from arknights_mower.utils import config
    from arknights_mower.utils.log import init_file_logging
    init_file_logging()
    token = os.environ['MOWER_WEB_TOKEN']
    config.conf.webview.token = token
    config.conf.webview.port = int(os.environ.get('MOWER_WEB_PORT', '58000'))
    from mower_android.bridge import Bridge
    import server
    from flask import request, abort, make_response
    from werkzeug.datastructures import ImmutableMultiDict

    @server.app.before_request
    def android_routes():
        supplied = request.headers.get('token') or request.args.get('token') or request.cookies.get('mower_access', '')
        authorized = bool(supplied) and hmac.compare_digest(supplied, token)
        if not authorized:
            if request.path == '/':
                return '<!doctype html><meta name="viewport" content="width=device-width"><title>Mower</title><p>请使用手机「局域网」页面中带访问令牌的完整地址。</p>'
            abort(401)
        if request.method not in ('GET', 'HEAD', 'OPTIONS') and request.headers.get('Origin'):
            if request.headers['Origin'] != request.host_url.rstrip('/'):
                abort(403)
        # Original mower handlers accept the header; cookie supports authenticated assets/screenshots.
        request.environ['HTTP_TOKEN'] = token
        if request.path.startswith(('/maa-update/', '/maa-resource-update/')):
            values = request.args.to_dict(); values['maa_path'] = str(MAA_PATH); request.args = ImmutableMultiDict(values)
            if request.method not in ('GET', 'HEAD', 'OPTIONS') and request.is_json:
                data = request.get_json()
                if not isinstance(data, dict): abort(400)
                data['maa_path'] = str(MAA_PATH)
                if request.path.startswith('/maa-update/') and data.get('source', 'github') != 'github':
                    return {'ok':False, 'message':'Android 组件使用 MAA 官方 GitHub 更新源'}, 400
        if request.path in ('/check-maa', '/check-maa/status'):
            try:
                state = Bridge().call('prepare', package=config.conf.APPNAME)
                return {'status':'success', 'message':f"官方 Android MAA {state['maa_version']} 已连接后台游戏"}
            except Exception as exc:
                return {'status':'error', 'message':str(exc)}
        if request.path.startswith(('/software-update/', '/process-control/')) and request.method == 'POST':
            return {'ok':False, 'message':'应用更新请安装新版 APK，服务启停请使用手机顶部按钮'}, 409

    @server.app.after_request
    def android_response(response):
        if request.path == '/conf' and request.method == 'GET' and response.is_json:
            data = response.get_json(); data['runtime_platform'] = 'android'; response.set_data(server.app.json.dumps(data))
        if request.path == '/maa-update/info' and response.is_json:
            data = response.get_json(); data.update(platform='android', arch='arm64', default_source='github'); response.set_data(server.app.json.dumps(data))
        supplied = request.args.get('token', '')
        if supplied and hmac.compare_digest(supplied, token):
            response.set_cookie('mower_access', token, httponly=True, samesite='Strict')
        response.headers['Referrer-Policy'] = 'no-referrer'
        return response

    @server.app.get('/android/status')
    def android_status():
        return Bridge().call('status')

    def shutdown(*args):
        config.stop_mower.set(); config.stop_maa.set()
        try: Bridge(timeout=5).call('maa_stop')
        finally: raise SystemExit(0)

    signal.signal(signal.SIGTERM, shutdown)
    server.app.run(host=os.environ.get('MOWER_WEB_BIND', '127.0.0.1'), port=config.conf.webview.port, threaded=True, use_reloader=False)


if __name__ == '__main__': main()
