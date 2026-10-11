import json
import sys
import threading
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest.mock import MagicMock, Mock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))

sys.modules.setdefault("arknights_mower.utils.skland", MagicMock())

from arknights_mower.solvers import base_schedule
from arknights_mower.utils import maa_backup


@pytest.fixture
def desktop(monkeypatch, tmp_path):
    monkeypatch.setenv("MOWER_ANDROID", "0")
    monkeypatch.syspath_prepend(str(tmp_path))
    native = Mock()
    native.get_version.return_value = "v6.test"
    native.connect.return_value = True
    asst = Mock(return_value=native)
    asst.load.return_value = True
    package = ModuleType("asst")
    package.__path__ = []
    module = ModuleType("asst.asst")
    module.Asst = asst
    options = ModuleType("asst.utils")
    options.InstanceOptionType = SimpleNamespace(touch_type=2)
    for name, value in (
        ("asst", package),
        ("asst.asst", module),
        ("asst.utils", options),
    ):
        monkeypatch.setitem(sys.modules, name, value)
    conf = SimpleNamespace(
        maa_path=str(tmp_path),
        maa_touch_option="maatouch",
        maa_adb_path="adb",
        maa_conn_preset="General",
    )
    monkeypatch.setattr(base_schedule.config, "conf", conf)
    monkeypatch.setattr(base_schedule.config, "stop_maa", threading.Event())
    monkeypatch.setattr(base_schedule, "save_exception", Mock())
    log = Mock()
    monkeypatch.setattr(base_schedule, "logger", log)
    response = MagicMock()
    response.__enter__.return_value = response
    response.content = b'{"YW-8": {"algorithm": "JustReturn"}}'
    monkeypatch.setattr(base_schedule.requests, "get", Mock(return_value=response))
    resource = tmp_path / "resource"
    resource.mkdir()
    (resource / "version.json").write_text(
        json.dumps({"last_updated": "2026-10-10 01:00:00.000"})
    )
    scheduler = object.__new__(base_schedule.BaseSchedulerSolver)
    scheduler.MAA = None
    scheduler.device = SimpleNamespace(
        client=SimpleNamespace(adb_bin="adb", device_id="127.0.0.1:5555")
    )
    yield SimpleNamespace(
        scheduler=scheduler,
        asst=asst,
        native=native,
        response=response,
        root=tmp_path,
        log=log,
    )
    if scheduler.MAA is not None:
        scheduler.MAA.stop()


@pytest.mark.parametrize("payload", [b"<html>error</html>", b"[]", b"{}"])
def test_invalid_download_preserves_existing_navigation(desktop, payload):
    cached = desktop.root / "cache/resource/tasks.json"
    cached.parent.mkdir(parents=True)
    previous = b'{"OLD": {"algorithm": "JustReturn"}}'
    cached.write_bytes(previous)
    desktop.response.content = payload
    desktop.scheduler.update_maa_navigation(desktop.root)
    assert cached.read_bytes() == previous
    desktop.log.error.assert_called_once()


def test_failed_atomic_write_preserves_existing_navigation(desktop, monkeypatch):
    from arknights_mower.utils import update_runtime

    cached = desktop.root / "cache/resource/tasks.json"
    cached.parent.mkdir(parents=True)
    previous = b'{"OLD": {"algorithm": "JustReturn"}}'
    cached.write_bytes(previous)
    monkeypatch.setattr(
        update_runtime, "replace_with_retry", Mock(side_effect=OSError("disk full"))
    )
    desktop.scheduler.update_maa_navigation(desktop.root)
    assert cached.read_bytes() == previous
    assert list(cached.parent.iterdir()) == [cached]
    desktop.log.error.assert_called_once()


@pytest.fixture
def android(desktop, monkeypatch):
    monkeypatch.setenv("MOWER_ANDROID", "1")
    package = ModuleType("mower_android")
    package.__path__ = []
    maa = ModuleType("mower_android.maa")
    maa.Asst = desktop.asst
    bridge = ModuleType("mower_android.bridge")
    bridge.Bridge = Mock()
    bridge.Bridge.return_value.call.return_value = True
    for name, value in (
        ("mower_android", package),
        ("mower_android.maa", maa),
        ("mower_android.bridge", bridge),
    ):
        monkeypatch.setitem(sys.modules, name, value)
    desktop.bridge = bridge.Bridge.return_value
    return desktop


def test_android_downloads_and_loads_navigation_before_connect(android):
    events = []
    base_schedule.requests.get.side_effect = lambda *a, **kw: (
        events.append("download") or android.response
    )
    android.bridge.call.side_effect = lambda *a, **kw: events.append("load") or True
    android.native.connect.side_effect = lambda: events.append("connect") or True
    android.scheduler.initialize_maa()
    assert events == ["download", "load", "connect"]
    android.bridge.call.assert_called_once_with(
        "maa_navigation", tasks={"YW-8": {"algorithm": "JustReturn"}}
    )
    android.asst.load.assert_not_called()


def test_android_load_failure_prevents_task_connection(android):
    android.bridge.call.return_value = False
    with pytest.raises(maa_backup.MaaCallError, match="资源加载失败"):
        android.scheduler.initialize_maa()
    android.native.connect.assert_not_called()
    android.native.append_task.assert_not_called()


def test_android_invalid_download_loads_previous_cached_navigation(android):
    cached = android.root / "cache/resource/tasks.json"
    cached.parent.mkdir(parents=True)
    cached.write_text('{"OLD": {"algorithm": "JustReturn"}}')
    android.response.content = b"invalid"
    android.scheduler.initialize_maa()
    android.bridge.call.assert_called_once_with(
        "maa_navigation", tasks={"OLD": {"algorithm": "JustReturn"}}
    )


def test_android_bridge_failure_prevents_task_submission(android):
    android.bridge.call.side_effect = RuntimeError("bridge disconnected")
    with pytest.raises(RuntimeError, match="bridge disconnected"):
        android.scheduler.initialize_maa()
    android.native.connect.assert_not_called()
    android.native.append_task.assert_not_called()


@pytest.mark.parametrize("stop_error", [None, RuntimeError("stop rejected")])
def test_failed_dispatch_clears_queue_and_preserves_original_error(
    desktop, monkeypatch, stop_error
):
    from datetime import datetime, timedelta

    scheduler = desktop.scheduler
    scheduler.MAA = desktop.native
    desktop.native.stop.side_effect = stop_error
    scheduler.last_execution = {"maa": None}
    scheduler.tasks = [SimpleNamespace(time=datetime.now() + timedelta(hours=1))]  # noqa: DTZ005
    scheduler.recog = Mock()
    scheduler.back_to_index = Mock()
    scheduler.initialize_maa = Mock()
    scheduler.append_maa_task = Mock(
        side_effect=maa_backup.MaaCallError(
            "append_task", task_type="Fight", stage="YW-8"
        )
    )
    scheduler._idle_sleep = Mock()
    base_schedule.config.conf.maa_gap = 4
    notify = Mock()
    monkeypatch.setattr(base_schedule, "send_message", notify)
    scheduler.maa_plan_solver()
    desktop.native.stop.assert_called_once()
    desktop.native.start.assert_not_called()
    assert scheduler.MAA is None
    assert scheduler.last_execution["maa"] is None
    assert any("YW-8" in str(call) for call in notify.call_args_list)
