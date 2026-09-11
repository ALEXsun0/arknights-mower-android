import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
from mower_android import backup_cleanup, managed


class BackupCleanupTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.maa = self.root / "maa"
        self.maa.mkdir()
        self.old = self.root / "maa.old"
        self.old.mkdir()
        self.old_resource = self.maa / "resource.old"
        self.old_resource.mkdir()
        (self.maa / "config.json").write_text("keep user data")
        self.component = self.root / "component.zip"
        self.component.with_suffix(".sha256").write_text("a" * 64)
        for name, value in (("MAA_PATH", self.maa), ("COMPONENT", self.component)):
            context = patch.object(managed, name, value)
            context.start()
            self.addCleanup(context.stop)
        self.bridge_patch = patch("mower_android.bridge.Bridge")
        self.bridge = self.bridge_patch.start().return_value
        self.addCleanup(self.bridge_patch.stop)
        self.bridge.call.return_value = True

    def test_native_verified_version_removes_only_update_backups(self):
        self.assertTrue(backup_cleanup.confirm_task("a" * 64))
        self.bridge.call.assert_called_once_with(
            "maa_confirmed", component_hash="a" * 64
        )
        self.assertFalse(self.old.exists())
        self.assertFalse(self.old_resource.exists())
        self.assertEqual((self.maa / "config.json").read_text(), "keep user data")

    def test_pending_version_or_native_rejection_preserves_backups(self):
        self.assertFalse(backup_cleanup.confirm_task("b" * 64))
        self.bridge.call.assert_not_called()
        self.bridge.call.return_value = False
        self.assertFalse(backup_cleanup.confirm_task("a" * 64))
        self.assertTrue(self.old.exists())
        self.bridge.call.side_effect = RuntimeError("old APK")
        self.assertFalse(backup_cleanup.confirm_task("a" * 64))
        self.assertTrue(self.old.exists())

    def test_loaded_native_hash_must_match_current_component(self):
        self.bridge.call.return_value = {"maa_component_hash": "b" * 64}
        self.assertIsNone(backup_cleanup.capture_current())
        self.bridge.call.return_value = {"maa_component_hash": "a" * 64}
        self.assertEqual(backup_cleanup.capture_current(), "a" * 64)

    def test_resource_transaction_defers_cleanup_until_commit(self):
        entered, release = threading.Event(), threading.Event()

        @backup_cleanup.component_transaction
        def update():
            entered.set()
            release.wait(5)

        worker = threading.Thread(target=update)
        worker.start()
        try:
            self.assertTrue(entered.wait(2))
            self.assertFalse(backup_cleanup.confirm_task("a" * 64))
            self.assertTrue(self.old.exists())
            self.bridge.call.assert_not_called()
        finally:
            release.set()
            worker.join()
        self.assertTrue(backup_cleanup.confirm_task("a" * 64))

    def test_host_and_shared_updater_use_one_lock(self):
        from types import SimpleNamespace
        from arknights_mower.utils import maa_backup
        from unittest.mock import Mock

        server = SimpleNamespace(_run_maa_resource_update=Mock())
        with patch.object(maa_backup, "_update_lock"):
            backup_cleanup.install_hooks(server)
            self.assertIs(maa_backup._update_lock, backup_cleanup._component_lock)
            server._run_maa_resource_update()
