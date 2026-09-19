"""Bounded native device alerts through Mower's existing notification preferences."""
import threading
import time

MESSAGES = {
    'game_exit': '后台游戏意外退出，Mower 已停止任务，请检查后手动启动。',
    'backend_exit': '后台服务连接中断，Mower 已停止任务，请恢复连接后手动启动。',
    'low_fps': '后台游戏持续低帧率，请检查省电设置或设备温度。',
}
_lock = threading.Lock()
_last = {}


def report(event, sender, now=None):
    if event not in MESSAGES:
        raise ValueError('Unknown device event')
    now = time.monotonic() if now is None else now
    with _lock:
        if event in _last and now - _last[event] < 60:
            return False
        _last[event] = now
    sender(body=MESSAGES[event], subject='Android 后台运行提醒',
           level='WARNING' if event == 'low_fps' else 'ERROR')
    return True
