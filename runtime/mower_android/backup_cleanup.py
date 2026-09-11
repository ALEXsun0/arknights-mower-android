"""Android backups survive installation, then retire after a verified use."""

import functools
import logging
import re
import shutil
import threading

_component_lock = threading.RLock()


def component_transaction(function):
    @functools.wraps(function)
    def locked(*args, **kwargs):
        with _component_lock:
            return function(*args, **kwargs)

    return locked


def install_hooks(server):
    try:
        from arknights_mower.utils.maa_backup import configure_update_lock
    except ImportError:
        pass  # Older Mower packages remain usable.
    else:
        configure_update_lock(_component_lock)
    # The shared resource worker replaces resource/ and repacks the Android
    # component in two steps. Keep cleanup outside that entire transaction.
    server._run_maa_resource_update = component_transaction(
        server._run_maa_resource_update
    )


def capture_current():
    from mower_android.bridge import Bridge
    from mower_android.managed import COMPONENT

    component_hash = COMPONENT.with_suffix(".sha256").read_text().strip()
    return (
        component_hash
        if Bridge().call("status").get("maa_component_hash") == component_hash
        else None
    )


def confirm_task(component_hash):
    if not isinstance(component_hash, str) or not re.fullmatch(
        r"[a-f0-9]{64}", component_hash
    ):
        return False
    # An update can run while an old task finishes. Never delay task callbacks
    # on downloads, nor discard a backup for a version that has not run yet.
    if not _component_lock.acquire(blocking=False):
        return False
    try:
        from mower_android.managed import COMPONENT, MAA_PATH

        if COMPONENT.with_suffix(".sha256").read_text().strip() != component_hash:
            return False
        from mower_android.bridge import Bridge

        if not Bridge().call("maa_confirmed", component_hash=component_hash):
            return False
        for backup in (
            MAA_PATH.with_name(MAA_PATH.name + ".old"),
            MAA_PATH / "resource.old",
        ):
            if backup.is_symlink():
                backup.unlink()
            elif backup.is_dir():
                shutil.rmtree(backup)
        return True
    except (OSError, RuntimeError):
        logging.getLogger(__name__).warning(
            "MAA 已验证；旧备份暂未清理，将在后续任务状态检查时重试"
        )
        return False
    finally:
        _component_lock.release()
