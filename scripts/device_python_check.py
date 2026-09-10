"""Runs INSIDE the installed app's Python runtime (read-only game recognition)."""
import json
import os
import platform
import time
from pathlib import Path

session = json.loads(Path('/android-files/runtime-session.json').read_text())
os.environ.update(MOWER_ANDROID='1', MOWER_DATA_DIR='/mower-data',
                  MOWER_BRIDGE_PORT=str(session['port']), MOWER_BRIDGE_TOKEN=session['token'])
from arknights_mower.utils.device.device import Device
from arknights_mower.utils.recognize import Recognizer
from arknights_mower.utils import rapidocr
from mower_android.maa import Asst

device = Device.create()
started = time.monotonic()
png, rgb, gray = device.screencap()
recognizer = Recognizer(device, png)
rapidocr.initialize_ocr()
ocr, _ = rapidocr.engine(rgb)
assert rgb.shape == (1080, 1920, 3) and gray.shape == (1080, 1920)
assert gray.std() > 1
result = {'python': platform.python_version(), 'machine': platform.machine(), 'proot_virtual_uid': os.getuid(),
          'device_adapter': type(device).__name__, 'maa_version': Asst.get_version(),
          'rgb_shape': list(rgb.shape), 'ocr_regions': len(ocr or []),
          'mower_home_scene': bool(recognizer.detect_index_scene()),
          'seconds': round(time.monotonic() - started, 2)}
print(json.dumps(result, indent=2))
Path('/android-files/python-check-result.json').write_text(json.dumps(result, indent=2))
