"""Deduplicated maintenance outside HTTP responses and task status polling."""

import logging
import threading

_lock = threading.Lock()
_jobs = {}


def submit(key, action):
    with _lock:
        if key in _jobs:
            return _jobs[key]

        def run():
            try:
                action()
            except Exception:
                logging.getLogger(__name__).warning(
                    "后台清理暂未完成，后续检查时重试", exc_info=True
                )
            finally:
                with _lock:
                    _jobs.pop(key, None)

        worker = threading.Thread(target=run, name="mower-backup-cleanup", daemon=True)
        _jobs[key] = worker
        worker.start()
        return worker
