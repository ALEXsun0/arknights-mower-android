"""Capture the phone screen before scheduler initialization can change it."""

import functools
import importlib


def install_hooks():
    from mower_android.bridge import Bridge

    mower = importlib.import_module("arknights_mower.__main__")
    original = mower.main
    if getattr(original, "_android_task_screen", False) is True:
        return

    def notify(method):
        try:
            Bridge(timeout=5).call(method)
        except (OSError, RuntimeError):
            # Older APKs can run the same adapter without this optional hook.
            from arknights_mower.utils.log import logger

            logger.warning("记录任务屏幕状态失败，请检查服务连接或更新 Android APK")

    @functools.wraps(original)
    def run(*args, **kwargs):
        notify("task_screen_begin")
        try:
            return original(*args, **kwargs)
        finally:
            notify("task_screen_end")

    run._android_task_screen = True
    mower.main = run
