"""Test the installed debug APK; credentials stay in memory and are never printed."""
import argparse
import base64
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'runtime'))
from mower_android.bridge import Bridge

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--launch', action='store_true', help='Open the game on its private display')
parser.add_argument('--startup', action='store_true', help='Run the MAA StartUp/login task (no farming)')
args = parser.parse_args()
adb = ['adb', '-s', args.serial]
session = json.loads(subprocess.check_output(adb + ['shell', 'run-as', 'io.github.alexsun0.mower.android', 'cat', 'files/runtime-session.json']))
port = int(subprocess.check_output(adb + ['forward', 'tcp:0', f'tcp:{session["port"]}'], text=True).strip())
try:
    bridge = Bridge(port, session['token'])
    result = {'status': bridge.call('status')}
    if args.launch or args.startup:
        result['prepared'] = bridge.call('prepare', package='com.hypergryph.arknights')
        result['launched'] = bridge.call('launch')
        time.sleep(2)
    if args.startup:
        cursor = bridge.call('maa_reset')
        bridge.call('maa_append', type='StartUp', params={'client_type': 'Official', 'start_game_enabled': False})
        bridge.call('maa_start')
        deadline = time.monotonic() + 150
        messages = []
        while time.monotonic() < deadline:
            running = bridge.call('maa_running')
            events = bridge.call('maa_events', after=cursor)['events']
            for event in events:
                messages.append(event['msg']); cursor = event['id']
            if not running:
                break
            time.sleep(1)
        else:
            bridge.call('maa_stop')
            raise TimeoutError('StartUp did not complete in 150 seconds')
        result['maa_startup_messages'] = messages
        result['maa_startup_completed'] = 10002 in messages and 10000 not in messages
        assert result['maa_startup_completed'], 'MAA StartUp did not report successful completion'
    if bridge.call('status')['prepared']:
        png = base64.b64decode(bridge.call('screenshot'))
        if png[:8] != b'\x89PNG\r\n\x1a\n':
            raise ValueError('Invalid PNG')
        import struct
        width, height = struct.unpack('!II', png[16:24])
        assert (width, height) == (1920, 1080)
        result['frame'] = {'width': width, 'height': height, 'bytes': len(png)}
        (ROOT / 'artifacts/game-frame.png').write_bytes(png)
        result['game'] = bridge.call('game_status')
    print(json.dumps(result, indent=2))
    (ROOT / 'artifacts/android-smoke.json').write_text(json.dumps(result, indent=2))
finally:
    subprocess.run(adb + ['forward', '--remove', f'tcp:{port}'], check=True)
