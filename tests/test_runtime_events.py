import sys
import unittest
from pathlib import Path
from unittest.mock import Mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android import runtime_events as events

class RuntimeEventsTest(unittest.TestCase):
    def setUp(self):
        events._last.clear()
    def test_unknown_event_rejected_without_sending(self):
        sender = Mock()
        with self.assertRaises(ValueError): events.report('custom', sender)
        sender.assert_not_called()
    def test_repeated_event_is_rate_limited(self):
        sender = Mock()
        self.assertTrue(events.report('game_exit', sender, 1))
        self.assertFalse(events.report('game_exit', sender, 2))
        self.assertTrue(events.report('game_exit', sender, 61))
        self.assertEqual(sender.call_count, 2)
        self.assertEqual(sender.call_args.kwargs['level'], 'ERROR')
    def test_low_fps_is_warning(self):
        sender = Mock()
        events.report('low_fps', sender, 1)
        self.assertEqual(sender.call_args.kwargs['level'], 'WARNING')
