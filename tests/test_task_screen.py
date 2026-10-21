import sys
import unittest
from pathlib import Path
from types import ModuleType
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
from mower_android.task_screen import install_hooks


class TaskScreenTests(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.mower = ModuleType("arknights_mower.__main__")
        self.mower.main = Mock(side_effect=self.run_task)
        self.original = self.mower.main
        self.modules = patch.dict(sys.modules, {"arknights_mower.__main__": self.mower})
        self.modules.start()
        self.addCleanup(self.modules.stop)
        self.bridge_patch = patch("mower_android.bridge.Bridge")
        self.bridge = self.bridge_patch.start().return_value
        self.addCleanup(self.bridge_patch.stop)
        self.bridge.call.side_effect = self.events.append

    def run_task(self, saved, restart=False):
        self.events.append("initialize")
        return saved

    def test_screen_is_captured_before_initialization_and_released_after_run(self):
        install_hooks()
        result = self.mower.main({"tasks": []}, restart=True)
        self.assertEqual(result, {"tasks": []})
        self.original.assert_called_once_with({"tasks": []}, restart=True)
        self.assertEqual(self.events, ["task_screen_begin", "initialize", "task_screen_end"])

    def test_failed_run_releases_snapshot_and_next_run_captures_again(self):
        install_hooks()
        self.original.side_effect = ValueError("initialization failed")
        with self.assertRaisesRegex(ValueError, "initialization failed"):
            self.mower.main({})
        self.original.side_effect = self.run_task
        self.mower.main({})
        self.assertEqual(self.events, ["task_screen_begin", "task_screen_end",
                                       "task_screen_begin", "initialize", "task_screen_end"])

    def test_repeated_install_does_not_duplicate_hooks(self):
        install_hooks()
        install_hooks()
        self.mower.main({})
        self.assertEqual(self.events, ["task_screen_begin", "initialize", "task_screen_end"])

    def test_old_host_does_not_prevent_task_execution(self):
        self.bridge.call.side_effect = RuntimeError("unknown method")
        logs = ModuleType("arknights_mower.utils.log")
        logs.logger = Mock()
        with patch.dict(sys.modules, {"arknights_mower.utils.log": logs}):
            install_hooks()
            self.assertEqual(self.mower.main({}), {})
        self.original.assert_called_once_with({})
        self.assertEqual(logs.logger.warning.call_count, 2)
