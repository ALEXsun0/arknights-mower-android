import os
import sys
import tempfile
import threading
import unittest
from contextlib import ExitStack
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest.mock import MagicMock, Mock, patch

from flask import Flask, request

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
from mower_android import launcher, task_startup


class TaskStartupTests(unittest.TestCase):
    def launch(
        self,
        *,
        automatic=True,
        resource=False,
        settings="resource_update",
        during_serve=None,
        install=None,
        check_error=None,
        binding_error=False,
        selected_source=False,
    ):
        with ExitStack() as stack:
            root = Path(stack.enter_context(tempfile.TemporaryDirectory()))
            source = root / ("hot-loaded-mower" if selected_source else "bundled-mower")
            source.mkdir()
            stack.callback(os.chdir, Path.cwd())
            original_path = sys.path[:]
            stack.callback(lambda: sys.path.__setitem__(slice(None), original_path))
            events = []
            config = SimpleNamespace(
                conf=SimpleNamespace(
                    start_automatically=automatic,
                    webview=SimpleNamespace(token="", port=58000),
                    **{
                        settings: SimpleNamespace(enable=resource, auto_update=resource)
                    },
                ),
                stop_mower=threading.Event(),
                stop_maa=threading.Event(),
            )
            utils = ModuleType("arknights_mower.utils")
            utils.config = config
            utils.path = SimpleNamespace()
            mower = ModuleType("arknights_mower")
            mower.utils = utils
            logs = ModuleType("arknights_mower.utils.log")
            logs.init_file_logging = Mock()
            logs.logger = Mock()
            app = Flask(__name__)
            app.testing = True
            active = [False]
            workers = []

            def start(mode):
                self.assertEqual(request.headers["token"], "test-token")
                events.append(("start", mode))
                config.stop_mower.clear()
                active[0] = True
                return "true"

            def stop():
                # Reproduce bundled Mower's no-worker early return.
                if active[0]:
                    config.stop_mower.set()
                active[0] = False
                return "true"

            def check():
                events.append("check resources")
                if check_error:
                    raise OSError("offline")
                return {"update_available": True}

            def install_resources():
                self.assertEqual(request.headers["token"], "test-token")
                events.append("install resources")
                if install:
                    return install(client, config)
                return {"ok": True}

            server = SimpleNamespace(
                app=app,
                start=Mock(side_effect=start),
                stop=stop,
                active_job=Mock(return_value=False),
                get_resource_version=Mock(side_effect=check),
                install_resource=Mock(side_effect=install_resources),
                resource_update=SimpleNamespace(
                    running=Mock(return_value=False), thread=Mock()
                ),
            )
            app.add_url_rule("/start/<mode>", view_func=start)
            app.add_url_rule("/stop", view_func=stop)
            app.add_url_rule("/", endpoint="index", view_func=lambda: "WebUI")
            app.add_url_rule(
                "/status",
                endpoint="status",
                view_func=lambda: {
                    "status": "working" if active[0] else "stopped",
                    "auto_start_handled": False,
                    "plan_condition": [],
                },
            )
            client = app.test_client()
            listener = MagicMock()

            def bind(*args, **kwargs):
                self.assertIs(args[2], app)
                self.assertTrue(kwargs["threaded"])
                self.assertEqual(workers, [])
                events.append("bound")
                if binding_error:
                    raise OSError("address already in use")
                return listener

            def make_thread(**kwargs):
                self.assertEqual(kwargs["name"], "android-task-startup")
                self.assertTrue(kwargs["daemon"])
                workers.append(kwargs["target"])
                return Mock(start=Mock())

            def serve():
                self.assertEqual(events[0], "bound")
                if during_serve:
                    during_serve(client, workers, config, server, events)
                else:
                    for worker in workers:
                        worker()

            listener.__enter__.return_value.serve_forever.side_effect = serve
            patches = [
                patch.dict(
                    os.environ,
                    MOWER_DATA_DIR=str(root),
                    MOWER_WEB_TOKEN="test-token",
                    MOWER_WEB_PORT="58000",
                ),
                patch.dict(
                    sys.modules,
                    {
                        "server": server,
                        "arknights_mower": mower,
                        "arknights_mower.utils": utils,
                        "arknights_mower.utils.log": logs,
                    },
                ),
                patch("mower_android.mower_package.select_source", return_value=source),
                patch("mower_android.mower_package.mark_ready"),
                patch("mower_android.managed.prepare_files"),
                patch("mower_android.backup_cleanup.install_hooks"),
                patch("mower_android.python_updates.register_routes"),
                patch("mower_android.python_updates.start_auto_check"),
                patch("faulthandler.dump_traceback_later"),
                patch("faulthandler.cancel_dump_traceback_later"),
                patch("mower_android.launcher.signal.signal"),
                patch("werkzeug.serving.make_server", side_effect=bind),
                patch.object(task_startup, "Thread", side_effect=make_thread),
                patch("builtins.print"),
            ]
            for item in patches:
                stack.enter_context(item)
            if binding_error:
                with self.assertRaisesRegex(OSError, "address already in use"):
                    launcher.main()
                server.start.assert_not_called()
                self.assertEqual(workers, [])
            else:
                launcher.main()
                self.assertTrue(app.extensions["android_task_startup"])
                listener.__exit__.assert_called_once()
                # A late startup worker stays cancelled after the listener exits.
                for worker in workers:
                    worker()
            self.assertTrue(config.stop_mower.is_set())
            self.assertTrue(config.stop_maa.is_set())
            return events, server

    def status(self, client):
        response = client.get("/status", headers={"token": "test-token"})
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json["auto_start_handled"])
        self.assertTrue(response.json["desktop_startup_handled"])
        return response.json["status"]

    def test_service_start_needs_no_page_and_each_new_process_starts_once(self):
        for selected_source in (False, True):
            with self.subTest(selected_source=selected_source):
                events, server = self.launch(selected_source=selected_source)
                self.assertEqual(events, ["bound", ("start", "0")])
                server.start.assert_called_once_with("0")

    def test_disabled_startup_cannot_be_enabled_by_later_page_load(self):
        def serve(client, workers, config, server, events):
            self.assertEqual(workers, [])
            config.conf.start_automatically = True
            for _ in range(3):
                self.assertEqual(self.status(client), "stopped")
            task_startup.start_tasks(server, config, "test-token")
            self.assertEqual(workers, [])

        events, server = self.launch(automatic=False, during_serve=serve)
        self.assertEqual(events, ["bound"])
        server.start.assert_not_called()

    def test_stop_then_refresh_and_reopen_stay_stopped(self):
        def serve(client, workers, config, server, events):
            self.assertEqual(len(workers), 1)
            workers[0]()
            self.assertEqual(self.status(client), "working")
            client.get("/stop", headers={"token": "test-token"})
            for _ in range(3):
                client.get("/", headers={"token": "test-token"})
                self.assertEqual(self.status(client), "stopped")
            task_startup.start_tasks(server, config, "test-token")
            self.assertEqual(len(workers), 1)

        _, server = self.launch(during_serve=serve)
        server.start.assert_called_once_with("0")

    def test_stop_before_worker_exists_cancels_pending_admission_permanently(self):
        def serve(client, workers, config, server, events):
            client.get("/stop", headers={"token": "test-token"})
            self.assertTrue(config.stop_mower.is_set())
            # A later manual Start can clear the shared cancellation event;
            # it must not rearm the already-cancelled automatic startup.
            client.get("/start/1", headers={"token": "test-token"})
            client.get("/stop", headers={"token": "test-token"})
            config.stop_mower.clear()
            workers[0]()
            self.assertEqual(self.status(client), "stopped")

        events, server = self.launch(during_serve=serve)
        self.assertEqual(events, ["bound", ("start", "1")])
        server.start.assert_not_called()

    def test_unauthenticated_stop_does_not_cancel_automatic_start(self):
        def serve(client, workers, config, server, events):
            self.assertEqual(client.get("/stop").status_code, 401)
            self.assertFalse(config.stop_mower.is_set())
            workers[0]()

        events, server = self.launch(during_serve=serve)
        self.assertEqual(events, ["bound", ("start", "0")])
        server.start.assert_called_once_with("0")

    def test_resource_update_precedes_start_for_legacy_and_current_settings(self):
        for settings in ("hot_update", "resource_update"):
            with self.subTest(settings=settings):
                events, _ = self.launch(resource=True, settings=settings)
                self.assertEqual(
                    events,
                    ["bound", "check resources", "install resources", ("start", "0")],
                )

    def test_resource_check_and_install_failures_keep_task_start(self):
        events, _ = self.launch(resource=True, check_error=True)
        self.assertEqual(events, ["bound", "check resources", ("start", "0")])
        events, _ = self.launch(
            resource=True, install=lambda client, config: {"ok": False}
        )
        self.assertEqual(
            events, ["bound", "check resources", "install resources", ("start", "0")]
        )

    def test_stop_during_resources_cancels_start(self):
        def install(client, config):
            client.get("/stop", headers={"token": "test-token"})
            return {"ok": True}

        events, server = self.launch(resource=True, install=install)
        self.assertEqual(events, ["bound", "check resources", "install resources"])
        server.start.assert_not_called()

    def test_resource_automation_is_independent_of_task_startup(self):
        events, server = self.launch(automatic=False, resource=True)
        self.assertEqual(events, ["bound", "check resources", "install resources"])
        server.start.assert_not_called()

    def test_existing_resource_install_finishes_before_task_start(self):
        def serve(client, workers, config, server, events):
            server.resource_update.running.return_value = True
            server.resource_update.thread.join.side_effect = lambda: events.append(
                "existing resources finished"
            )
            workers[0]()

        events, server = self.launch(resource=True, during_serve=serve)
        self.assertEqual(
            events,
            [
                "bound",
                "check resources",
                "install resources",
                "existing resources finished",
                ("start", "0"),
            ],
        )
        server.resource_update.thread.join.assert_called_once_with()

    def test_stop_while_waiting_for_process_job_cancels_start(self):
        def serve(client, workers, config, server, events):
            def active_job():
                client.get("/stop", headers={"token": "test-token"})
                return True

            server.active_job.side_effect = active_job
            workers[0]()

        events, server = self.launch(resource=True, during_serve=serve)
        self.assertEqual(events, ["bound"])
        server.get_resource_version.assert_not_called()
        server.start.assert_not_called()

    def test_bind_failure_never_starts_tasks(self):
        events, _ = self.launch(binding_error=True)
        self.assertEqual(events, ["bound"])
