"""Bounded startup progress shared with the Android host; no credentials or polling."""
import json
import os
import time
from pathlib import Path


class StartupProgress:
    def __init__(self):
        self.path = Path(os.environ.get('MOWER_DATA_DIR', '/mower-data')) / 'runtime-startup.json'
        self.started = time.monotonic()
        self.last = None

    def report(self, stage, percent=-1):
        # Installation updates at most once per five percent, rather than per file.
        value = (stage, max(-1, min(100, percent)) // 5 * 5 if percent >= 0 else -1)
        if value == self.last:
            return
        self.last = value
        data = {'stage': value[0], 'percent': value[1]}
        temp = self.path.with_suffix('.new')
        temp.write_text(json.dumps(data))
        temp.replace(self.path)
        suffix = f' {value[1]}%' if value[1] >= 0 else ''
        print(f'[mower-startup] {time.monotonic() - self.started:.1f}s {stage}{suffix}', flush=True)
