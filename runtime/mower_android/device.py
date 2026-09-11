import base64
import time
from datetime import datetime
from types import SimpleNamespace

from mower_android.bridge import Bridge


class AndroidDevice:
    """Mower's Device contract backed by the engine's 1920×1080 virtual display."""

    def __init__(self, *args, **kwargs):
        self.bridge = Bridge()
        self.client = SimpleNamespace(device_id='Android')
        self.device_id = 'Android'
        self.control = SimpleNamespace(mumu12IPC=None, scrcpy=None, maatouch=None)
        self.start()

    @classmethod
    def create(cls, **kwargs):
        return cls()

    def start(self, **kwargs):
        from arknights_mower.utils import config
        self.bridge.call('prepare', package=config.conf.APPNAME)

    def launch(self):
        self.bridge.call('launch')

    def exit(self):
        self.bridge.call('exit_game')

    def return_home(self):
        # The game has its own display; never navigate away from the phone's UI.
        self.exit()

    def send_keyevent(self, keycode):
        self.bridge.call('key', code=int(keycode))

    def send_text(self, text):
        self.bridge.call('text', text=str(text))

    def is_app_running_in_background(self):
        return bool(self.bridge.call('game_status')['alive'])

    def bring_to_foreground(self):
        self.launch()

    def current_focus(self):
        from arknights_mower.utils import config
        state = self.bridge.call('game_status')
        return config.conf.APPNAME + '/virtual-display' if state['on_display'] else ''

    def check_current_focus(self):
        if self.current_focus():
            return False
        self.launch()
        time.sleep(2)
        return True

    def display_frames(self):
        return 1920, 1080, 0

    def check_resolution(self):
        return self.bridge.call('status')['resolution'] == [1920, 1080]

    def screencap(self):
        import cv2
        import numpy as np
        from arknights_mower.utils import config
        delay = config.conf.screenshot_interval / 1000 - (datetime.now() - config.screenshot_time).total_seconds()
        if delay > 0:
            time.sleep(delay)
        started = time.monotonic()
        png = base64.b64decode(self.bridge.call('screenshot'), validate=True)
        bgr = cv2.imdecode(np.frombuffer(png, dtype=np.uint8), cv2.IMREAD_COLOR)
        if bgr is None or bgr.shape[:2] != (1080, 1920):
            raise RuntimeError('后台画面不是 1920×1080，请重新启动后台游戏')
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        from arknights_mower.utils.log import save_screenshot
        save_screenshot(png)
        config.screenshot_time = datetime.now()
        elapsed = (time.monotonic() - started) * 1000
        config.screenshot_avg = elapsed if config.screenshot_avg is None else config.screenshot_avg * .9 + elapsed * .1
        config.screenshot_count += 1
        return png, rgb, gray

    def tap(self, point):
        self.bridge.call('tap', x=int(point[0]), y=int(point[1]))

    def swipe(self, start, end, duration=100):
        self.swipe_ext([start, end], [duration], up_wait=0)

    def swipe_ext(self, points, durations, up_wait=200):
        self.bridge.call('swipe', points=[[int(x), int(y)] for x, y in points],
                         durations=[int(d) for d in durations], up_wait=int(up_wait))

    def close(self):
        # The Android foreground service owns the display and engine lifetime.
        pass

    def reconnect(self, **kwargs):
        self.start()

    def recover(self, func, **kwargs):
        # Do not replay taps after an uncertain IPC result.
        return func()
