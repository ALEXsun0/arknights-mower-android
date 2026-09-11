import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
from mower_android import background_cleanup, mower_package, python_package


class BackgroundCleanupTests(unittest.TestCase):
    def test_slow_deletion_is_deduplicated_and_does_not_block_caller(self):
        entered, release = threading.Event(), threading.Event()

        def slow():
            entered.set()
            release.wait(5)

        worker = background_cleanup.submit("test-slow", slow)
        try:
            self.assertTrue(entered.wait(2))
            self.assertIs(
                worker,
                background_cleanup.submit("test-slow", lambda: self.fail("duplicate")),
            )
            self.assertTrue(worker.is_alive())
        finally:
            release.set()
            worker.join(3)

    def test_queued_program_cleanup_cannot_delete_new_pending_version(self):
        with (
            tempfile.TemporaryDirectory() as root,
            patch.dict(
                "os.environ", {"MOWER_DATA_DIR": root, "MOWER_ACTIVE_ID": "a" * 64}
            ),
        ):
            active, pending = (
                mower_package.folder() / ("a" * 64),
                mower_package.folder() / ("b" * 64),
            )
            active.mkdir()
            pending.mkdir()
            mower_package.save({"id": "a" * 64, "booting": True})
            with patch.object(background_cleanup, "submit") as queue:
                mower_package.mark_ready()
            self.assertTrue(pending.exists())
            mower_package.save({"id": "b" * 64, "previous": "a" * 64, "pending": True})
            queue.call_args.args[1]()
            self.assertTrue(active.exists())
            self.assertTrue(pending.exists())

    def test_verified_adapter_retires_history_but_never_pending_or_unknown_files(self):
        with (
            tempfile.TemporaryDirectory() as root,
            patch.object(python_package, "ROOT", Path(root)),
        ):
            root = Path(root)
            for name in (
                "a" * 64 + ".py",
                "b" * 64 + ".py",
                "user.py",
                "previous.json",
            ):
                (root / name).write_text("keep")
            with patch.object(
                python_package,
                "info",
                return_value={"sha256": "b" * 64, "bundled": False},
            ):
                self.assertFalse(python_package.retire_adapters("a" * 64))
                self.assertTrue((root / ("a" * 64 + ".py")).exists())
                self.assertTrue(python_package.retire_adapters("b" * 64))
                self.assertFalse((root / ("a" * 64 + ".py")).exists())
                self.assertTrue((root / ("b" * 64 + ".py")).exists())
                self.assertTrue((root / "user.py").exists())
                self.assertFalse((root / "previous.json").exists())

    def test_bundled_adapter_does_not_attempt_to_import_an_absent_update_file(self):
        from mower_android.maa_adapter import Asst

        with (
            patch.object(
                python_package,
                "info",
                return_value={"bundled": True, "sha256": "a" * 64},
            ),
            patch.object(
                python_package.importlib.util, "spec_from_file_location"
            ) as importer,
        ):
            self.assertIs(python_package.adapter_class(), Asst)
            importer.assert_not_called()
