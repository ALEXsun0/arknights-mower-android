import importlib
import json
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest.mock import MagicMock, Mock, patch

RUNTIME = Path(__file__).resolve().parents[1] / "runtime"
sys.path.insert(0, str(RUNTIME))


class MaaNavigationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from arknights_mower.utils import path

        # CI runs from the repository root; templates belong to the runtime.
        cls.enterClassContext(patch.object(path, "_internal_dir", RUNTIME))
        cls.enterClassContext(patch.object(path, "_install_dir", RUNTIME))
        cls.enterClassContext(
            patch.dict("sys.modules", {"arknights_mower.utils.skland": MagicMock()})
        )
        cls.base_schedule = importlib.import_module(
            "arknights_mower.solvers.base_schedule"
        )
        cls.maa_backup = importlib.import_module("arknights_mower.utils.maa_backup")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.enterContext(patch.dict("os.environ", {"MOWER_ANDROID": "0"}))
        self.native = Mock()
        self.native.get_version.return_value = "v6.test"
        self.native.connect.return_value = True
        self.asst = Mock(return_value=self.native)
        self.asst.load.return_value = True
        package = ModuleType("asst")
        package.__path__ = []
        module = ModuleType("asst.asst")
        module.Asst = self.asst
        options = ModuleType("asst.utils")
        options.InstanceOptionType = SimpleNamespace(touch_type=2)
        self.enterContext(
            patch.dict(
                "sys.modules",
                {"asst": package, "asst.asst": module, "asst.utils": options},
            )
        )
        conf = SimpleNamespace(
            maa_path=str(self.root),
            maa_touch_option="maatouch",
            maa_adb_path="adb",
            maa_conn_preset="General",
            maa_gap=4,
        )
        self.enterContext(patch.object(self.base_schedule.config, "conf", conf))
        for name in ("stop_maa", "stop_mower"):
            self.enterContext(
                patch.object(self.base_schedule.config, name, threading.Event())
            )
        self.enterContext(patch.object(self.base_schedule, "save_exception", Mock()))
        self.log = self.enterContext(patch.object(self.base_schedule, "logger"))
        self.response = MagicMock()
        self.response.__enter__.return_value = self.response
        self.response.content = b'{"YW-8": {"algorithm": "JustReturn"}}'
        self.download = self.enterContext(
            patch.object(self.base_schedule.requests, "get", return_value=self.response)
        )
        resource = self.root / "resource"
        resource.mkdir()
        (resource / "version.json").write_text(
            json.dumps({"last_updated": "2026-10-10 01:00:00.000"})
        )
        self.scheduler = object.__new__(self.base_schedule.BaseSchedulerSolver)
        self.scheduler.MAA = None
        self.scheduler.device = SimpleNamespace(
            client=SimpleNamespace(adb_bin="adb", device_id="127.0.0.1:5555")
        )
        self.addCleanup(self.stop)

    def stop(self):
        if self.scheduler.MAA is not None:
            self.scheduler.MAA.stop()

    def android(self):
        self.enterContext(patch.dict("os.environ", {"MOWER_ANDROID": "1"}))
        # Retain other adapter modules already imported by integration tests.
        maa = ModuleType("mower_android.maa")
        maa.Asst = self.asst
        bridge = ModuleType("mower_android.bridge")
        bridge.Bridge = Mock()
        bridge.Bridge.return_value.call.return_value = True
        self.enterContext(
            patch.dict(
                "sys.modules",
                {"mower_android.maa": maa, "mower_android.bridge": bridge},
            )
        )
        self.bridge = bridge.Bridge.return_value

    def cached(self):
        cached = self.root / "cache/resource/tasks.json"
        cached.parent.mkdir(parents=True, exist_ok=True)
        cached.write_bytes(b'{"OLD": {"algorithm": "JustReturn"}}')
        return cached

    def test_invalid_download_preserves_existing_navigation(self):
        for payload in (b"<html>error</html>", b"[]", b"{}"):
            with self.subTest(payload=payload):
                cached = self.cached()
                previous = cached.read_bytes()
                self.response.content = payload
                self.log.reset_mock()
                self.scheduler.update_maa_navigation(self.root)
                self.assertEqual(cached.read_bytes(), previous)
                self.log.error.assert_called_once()

    def test_failed_atomic_write_preserves_existing_navigation(self):
        from arknights_mower.utils import update_runtime

        cached = self.cached()
        previous = cached.read_bytes()
        with patch.object(
            update_runtime, "replace_with_retry", side_effect=OSError("disk full")
        ):
            self.scheduler.update_maa_navigation(self.root)
        self.assertEqual(cached.read_bytes(), previous)
        self.assertEqual(list(cached.parent.iterdir()), [cached])
        self.log.error.assert_called_once()

    def test_android_downloads_and_loads_navigation_before_connect(self):
        self.android()
        events = []
        self.download.side_effect = lambda *a, **kw: (
            events.append("download") or self.response
        )
        self.bridge.call.side_effect = lambda *a, **kw: events.append("load") or True
        self.native.connect.side_effect = lambda: events.append("connect") or True
        self.scheduler.initialize_maa()
        self.assertEqual(events, ["download", "load", "connect"])
        self.bridge.call.assert_called_once_with(
            "maa_navigation", tasks={"YW-8": {"algorithm": "JustReturn"}}
        )
        self.asst.load.assert_not_called()

    def test_android_load_failure_prevents_task_connection(self):
        self.android()
        self.bridge.call.return_value = False
        with self.assertRaisesRegex(self.maa_backup.MaaCallError, "资源加载失败"):
            self.scheduler.initialize_maa()
        self.native.connect.assert_not_called()
        self.native.append_task.assert_not_called()

    def test_android_invalid_download_loads_previous_cached_navigation(self):
        self.android()
        self.cached()
        self.response.content = b"invalid"
        self.scheduler.initialize_maa()
        self.bridge.call.assert_called_once_with(
            "maa_navigation", tasks={"OLD": {"algorithm": "JustReturn"}}
        )

    def test_android_bridge_failure_prevents_task_submission(self):
        self.android()
        self.bridge.call.side_effect = RuntimeError("bridge disconnected")
        with self.assertRaisesRegex(RuntimeError, "bridge disconnected"):
            self.scheduler.initialize_maa()
        self.native.connect.assert_not_called()
        self.native.append_task.assert_not_called()

    def test_failed_dispatch_clears_queue_and_preserves_original_error(self):
        for stop_error in (None, RuntimeError("stop rejected")):
            with self.subTest(stop_error=stop_error):
                self.native.reset_mock()
                scheduler = self.scheduler
                scheduler.MAA = self.native
                self.native.stop.side_effect = stop_error
                scheduler.last_execution = {"maa": None}
                scheduler.tasks = [
                    SimpleNamespace(time=datetime.now() + timedelta(hours=1))  # noqa: DTZ005
                ]
                scheduler.recog = Mock()
                scheduler.back_to_index = Mock()
                scheduler.initialize_maa = Mock()
                scheduler.append_maa_task = Mock(
                    side_effect=self.maa_backup.MaaCallError(
                        "append_task", task_type="Fight", stage="YW-8"
                    )
                )
                scheduler._idle_sleep = Mock()
                with patch.object(self.base_schedule, "send_message") as notify:
                    scheduler.maa_plan_solver()
                self.native.stop.assert_called_once()
                self.native.start.assert_not_called()
                self.assertIsNone(scheduler.MAA)
                self.assertIsNone(scheduler.last_execution["maa"])
                self.assertFalse(self.base_schedule.config.stop_mower.is_set())
                self.assertTrue(
                    any("YW-8" in str(call) for call in notify.call_args_list)
                )


if __name__ == "__main__":
    unittest.main()
