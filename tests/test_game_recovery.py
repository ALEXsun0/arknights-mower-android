import base64
import sys
import threading
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android.device import AndroidDevice
from arknights_mower.utils import config
from arknights_mower.utils.csleep import MowerExit


class GameRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.stop = threading.Event()
        for key, value in {
            'stop_mower': self.stop,
            'conf': SimpleNamespace(screenshot_interval=0),
            'screenshot_time': datetime.now() - timedelta(seconds=10),
            'screenshot_avg': None,
            'screenshot_count': 0,
        }.items():
            p = patch.object(config, key, value)
            p.start()
            self.addCleanup(p.stop)
        self.device = object.__new__(AndroidDevice)
        self.device.bridge = Mock()
        image = np.zeros((1080, 1920, 3), dtype=np.uint8)
        image[:, :960] = 220
        ok, png = cv2.imencode('.png', image)
        assert ok
        self.device.bridge.call.return_value = base64.b64encode(png).decode()
        p = patch('arknights_mower.utils.log.save_screenshot')
        p.start()
        self.addCleanup(p.stop)

    def test_solver_exit_is_not_eagerly_undone_but_next_capture_requests_recovery(self):
        self.device.exit()
        self.device.bridge.call.assert_called_once_with('exit_game')
        _, rgb, _ = self.device.screencap()
        self.assertEqual(self.device.bridge.call.call_args.args, ('screenshot',))
        self.assertTrue(self.device.bridge.call.call_args.kwargs['require_game'])
        self.assertEqual(rgb.shape, (1080, 1920, 3))

    def test_stop_prevents_recovery_and_capture(self):
        self.stop.set()
        with self.assertRaises(MowerExit):
            self.device.screencap()
        self.device.bridge.call.assert_not_called()

    def test_stop_during_capture_interval_does_not_relaunch(self):
        config.conf.screenshot_interval = 500
        config.screenshot_time = datetime.now()
        with patch('mower_android.device.time.sleep', side_effect=lambda _: self.stop.set()):
            with self.assertRaises(MowerExit):
                self.device.screencap()
        self.device.bridge.call.assert_not_called()

    def test_failed_recovery_never_replays_taps(self):
        self.device.bridge.call.side_effect = RuntimeError('recovery budget exhausted')
        with self.assertRaisesRegex(RuntimeError, 'budget exhausted'):
            self.device.screencap()
        self.device.bridge.call.assert_called_once_with('screenshot', require_game=True)
