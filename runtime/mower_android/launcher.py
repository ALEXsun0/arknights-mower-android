"""Run the original Flask/WebSocket WebUI inside the Android app runtime."""
import os
import signal
from pathlib import Path


def main():
    source = Path(__file__).resolve().parents[1]
    os.chdir(source)
    os.environ['MOWER_ANDROID'] = '1'
    os.environ.setdefault('MOWER_DATA_DIR', '/mower-data')
    from arknights_mower.utils import path
    path._internal_dir = source
    path._install_dir = source
    from arknights_mower.utils import config
    from arknights_mower.utils.log import init_file_logging
    init_file_logging()
    # Separate WebUI credential from engine IPC credential.
    config.conf.webview.token = os.environ['MOWER_WEB_TOKEN']
    config.conf.webview.port = int(os.environ.get('MOWER_WEB_PORT', '58000'))
    from mower_android.bridge import Bridge
    import server

    @server.app.get('/android/status')
    def android_status():
        from flask import request, abort
        if request.headers.get('token') != config.conf.webview.token and request.args.get('token') != config.conf.webview.token:
            abort(401)
        return Bridge().call('status')

    def shutdown(*args):
        config.stop_mower.set()
        config.stop_maa.set()
        try:
            Bridge(timeout=5).call('maa_stop')
        finally:
            raise SystemExit(0)

    signal.signal(signal.SIGTERM, shutdown)
    # Bind only to the device loopback, even when the UI has a token.
    server.app.run(host='127.0.0.1', port=config.conf.webview.port, threaded=True, use_reloader=False)


if __name__ == '__main__':
    main()
