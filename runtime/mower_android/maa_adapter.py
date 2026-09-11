import json
from mower_android.bridge import Bridge


class Asst:
    """Subset of the official Asst Python API used by this pinned mower version."""

    def __init__(self, callback=None):
        self.bridge = Bridge()
        self.bridge.call('prepare')
        self.callback = callback
        self.cursor = self.bridge.call('maa_reset')

    @classmethod
    def load(cls, **kwargs):
        return True

    @classmethod
    def get_version(cls):
        return Bridge().call('status')['maa_version']

    def connect(self, *args, **kwargs):
        return self.bridge.call('maa_connected')

    def set_instance_option(self, key, value):
        return self.bridge.call('maa_option', key=int(key), value=str(value))

    def append_task(self, task_type, params=None):
        return self.bridge.call('maa_append', type=task_type, params=params or {})

    def set_task_params(self, task_id, params):
        return self.bridge.call('maa_params', id=int(task_id), params=params)

    def start(self):
        return self.bridge.call('maa_start')

    def _events(self):
        result = self.bridge.call('maa_events', after=self.cursor)
        for event in result['events']:
            if self.callback:
                self.callback(event['msg'], event['details'].encode(), None)
            self.cursor = event['id']

    def running(self):
        running = self.bridge.call('maa_running')
        self._events()
        return running

    def stop(self):
        result = self.bridge.call('maa_stop')
        self._events()
        return result

    def get_tasks_list(self):
        return self.bridge.call('maa_tasks')
