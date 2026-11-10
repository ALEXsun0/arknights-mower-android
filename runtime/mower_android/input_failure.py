"""Classify native input delivery at the selected Mower core's recovery boundary."""

from arknights_mower.utils.device import recovery

try:
    from arknights_mower.utils.device.touch_backend import TouchFailure
except ModuleNotFoundError as exc:
    if exc.name != 'arknights_mower.utils.device.touch_backend':
        raise
    # Bundled older cores propagate DeviceRecoveryError without input replay.
    TouchFailure = recovery.DeviceRecoveryError


class AndroidTouchFailure(TouchFailure):
    def __init__(self, cause, *, delivery_unknown):
        self.backend = self.transport = 'android'
        self.delivery_unknown = delivery_unknown
        self.phase = 'delivery' if delivery_unknown else 'probe'
        self.retryable = not delivery_unknown
        reconcile = getattr(recovery, 'input_reconciliation', None)
        self.reconciliation = reconcile() if reconcile is not None else 'task'
        self.cleanup_failed = getattr(cause, 'cleanup_failed', False)
        self.code = 'touch_result_unknown' if delivery_unknown else 'touch_probe_failed'
        self.alternatives = []
        reason = '输入结果待核实，不会重放原输入' if delivery_unknown else '输入连接失败，尚未发送输入'
        recovery.DeviceRecoveryError.__init__(self, f'Android 原生触控{reason}：{cause}')

    def to_dict(self):
        return {
            'code': self.code, 'message': str(self), 'action': 'configure',
            'fields': [], 'backend': self.backend, 'transport': self.transport,
            'alternatives': [], 'delivery_unknown': self.delivery_unknown,
            'phase': self.phase, 'retryable': self.retryable,
            'reconciliation': self.reconciliation,
        }
