"""Stable facade. Each new task can use a newly imported compatible Python adapter."""
from mower_android.python_package import adapter_class

class Asst:
    def __new__(cls, *args, **kwargs):
        return adapter_class()(*args, **kwargs)

    @staticmethod
    def CallBackType(callback):
        # The Android bridge delivers Python callbacks directly, without a ctypes trampoline.
        return callback

    @classmethod
    def load(cls, **kwargs):
        return adapter_class().load(**kwargs)

    @classmethod
    def get_version(cls):
        return adapter_class().get_version()
