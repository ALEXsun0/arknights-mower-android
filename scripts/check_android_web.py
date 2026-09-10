"""Verify Android HTTP, cookies, settings ownership and WebSocket authentication.

Session credentials stay in memory and are never written to test output.
"""
import argparse
import base64
import http.cookiejar
import json
import os
import re
import socket
import subprocess
import urllib.error
import urllib.parse
import urllib.request

p = argparse.ArgumentParser(); p.add_argument('--serial', required=True); p.add_argument('--update-check', action='store_true'); args = p.parse_args()
adb = ['adb', '-s', args.serial]
session = json.loads(subprocess.check_output(adb + ['shell', 'run-as', 'io.github.alexsun0.mower.android', 'cat', 'files/runtime-session.json']))
parsed = urllib.parse.urlsplit(session['web_url']); token = urllib.parse.parse_qs(parsed.query)['token'][0]
port = int(subprocess.check_output(adb + ['forward', 'tcp:0', f'tcp:{parsed.port}']))
base = f'http://127.0.0.1:{port}'
jar = http.cookiejar.CookieJar(); client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.ProxyHandler({}))

def request(path, data=None, headers=None):
    req = urllib.request.Request(base + path, data=json.dumps(data).encode() if data is not None else None,
                                 headers={'Content-Type':'application/json', **(headers or {})})
    try:
        with client.open(req, timeout=120) as response: return response.status, response.read()
    except urllib.error.HTTPError as error: return error.code, error.read()

def websocket(cookie):
    with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
        key = base64.b64encode(os.urandom(16)).decode()
        headers = f'GET /log HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n'
        if cookie: headers += f'Cookie: mower_access={token}\r\n'
        connection.sendall((headers + '\r\n').encode())
        return connection.recv(1024).split(b'\r\n',1)[0].decode()

try:
    result = {}
    result['unauthenticated_config'] = request('/conf')[0]; assert result['unauthenticated_config'] == 401
    result['unauthenticated_websocket'] = websocket(False); assert '401' in result['unauthenticated_websocket']
    code, html = request('/?token=' + token); assert code == 200
    asset = re.search(rb'src="(/assets/[^"]+\.js)"', html).group(1).decode()
    result['cookie_static_asset'] = request(asset)[0]; assert result['cookie_static_asset'] == 200
    code, conf_bytes = request('/conf'); conf = json.loads(conf_bytes)
    result['managed_config'] = {key:conf.get(key) for key in ('runtime_platform','adb','maa_path','maa_conn_preset','maa_touch_option')}
    assert result['managed_config'] == dict(runtime_platform='android',adb='Android',maa_path='/mower-data/maa',maa_conn_preset='Android',maa_touch_option='Android')
    result['cross_origin_write'] = request('/conf', conf, {'Origin':'https://example.invalid'})[0]; assert result['cross_origin_write'] == 403
    result['authenticated_websocket'] = websocket(True); assert '101' in result['authenticated_websocket']
    code, info = request('/maa-update/info'); info=json.loads(info)
    result['update_info'] = {key:info.get(key) for key in ('platform','arch','installed_version','default_source')}
    assert info['platform'] == 'android' and info['arch'] == 'arm64'
    if args.update_check:
        code, checked = request('/maa-update/check', {'source':'github','channel':'stable','maa_path':'C:/desktop/MAA'})
        result['official_update_check'] = json.loads(checked)
        assert result['official_update_check']['ok'], result['official_update_check'].get('message')
    print(json.dumps(result, ensure_ascii=False, indent=2))
finally: subprocess.run(adb + ['forward', '--remove', f'tcp:{port}'], check=True)
