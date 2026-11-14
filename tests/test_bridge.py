import json
import socket
import struct
import sys
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from arknights_mower.utils.device import recovery
from arknights_mower.utils.device.recovery import DeviceRecoveryError
from mower_android.bridge import Bridge, BridgeError
from mower_android.device import AndroidDevice
from mower_android.input_failure import AndroidTouchFailure
from mower_android.maa import Asst


class TransportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from arknights_mower.utils import path

        runtime = Path(__file__).resolve().parents[1] / 'runtime'
        cls.enterClassContext(patch.object(path, '_internal_dir', runtime))
        cls.enterClassContext(patch.object(path, '_install_dir', runtime))

    def exchange(self, response, length=None):
        server = socket.socket()
        server.settimeout(3)
        server.bind(('127.0.0.1', 0))
        server.listen(1)
        requests = []
        def serve():
            with server, server.accept()[0] as client:
                size, = struct.unpack('!I', Bridge._read(client, 4))
                requests.append(json.loads(Bridge._read(client, size)))
                payload = json.dumps(response).encode()
                client.sendall(struct.pack('!I', length if length is not None else len(payload)))
                if length is None:
                    # Deliberately fragment the frame at arbitrary byte boundaries.
                    for byte in payload:
                        client.sendall(bytes([byte]))
        thread = threading.Thread(target=serve)
        thread.start()
        bridge = Bridge(server.getsockname()[1], 'test-secret', timeout=2)
        return bridge, thread, requests

    def test_fragmented_response_and_authentication(self):
        bridge, worker, requests = self.exchange({'ok': True, 'result': {'version': '6.17.3'}})
        self.assertEqual(bridge.call('status'), {'version': '6.17.3'})
        worker.join()
        self.assertEqual(requests, [{'token': 'test-secret', 'method': 'status', 'params': {}}])

    def test_engine_error_is_not_success(self):
        bridge, worker, _ = self.exchange({'ok': False, 'error': 'MAA is busy'})
        with self.assertRaisesRegex(BridgeError, 'MAA is busy'):
            bridge.call('maa_start')
        worker.join()

    def test_input_rejection_leaves_navigation_retry_and_is_not_replayed(self):
        operations = {
            'tap': lambda device: device.tap((10, 20)),
            'swipe': lambda device: device.swipe((10, 20), (30, 40)),
            'key': lambda device: device.send_keyevent(4),
            'text': lambda device: device.send_text('test'),
        }
        for method, operation in operations.items():
            with self.subTest(method=method):
                bridge, worker, requests = self.exchange({'ok': False, 'error': 'Check failed.'})
                device = object.__new__(AndroidDevice)
                device.bridge = bridge
                try:
                    with self.assertRaisesRegex(DeviceRecoveryError, 'Check failed') as caught:
                        operation(device)
                    self.assertIsInstance(caught.exception, AndroidTouchFailure)
                    self.assertIsInstance(caught.exception.__cause__, BridgeError)
                    self.assertTrue(caught.exception.delivery_unknown)
                    self.assertFalse(caught.exception.retryable)
                finally:
                    worker.join(timeout=3)
                self.assertFalse(worker.is_alive())
                self.assertEqual(len(requests), 1)
                self.assertEqual(requests[0]['method'], method)

    def test_native_error_reports_its_transport_and_preserves_original_details(self):
        failure = AndroidTouchFailure(
            RuntimeError('Android 触控按下失败：display=7, x=10, y=20'),
            delivery_unknown=True,
        )
        status = failure.to_dict()
        self.assertEqual(status['code'], 'touch_result_unknown')
        self.assertEqual(status['backend'], 'android')
        self.assertEqual(status['transport'], 'android')
        self.assertEqual(status['phase'], 'delivery')
        self.assertEqual(status['alternatives'], [])
        self.assertEqual(status['fields'], [])
        self.assertIn('display=7, x=10, y=20', status['message'])

    def test_input_disconnect_and_timeout_do_not_replay(self):
        bridge, worker, requests = self.exchange({}, length=25)
        try:
            with self.assertRaisesRegex(DeviceRecoveryError, 'disconnected'):
                bridge.call('tap', x=10, y=20)
        finally:
            worker.join(timeout=3)
        self.assertEqual(len(requests), 1)
        with patch('mower_android.bridge.socket.create_connection', side_effect=TimeoutError('timed out')) as connect, self.assertRaisesRegex(AndroidTouchFailure, 'timed out') as caught:
            bridge.call('tap', x=10, y=20)
        self.assertFalse(caught.exception.delivery_unknown)
        self.assertTrue(caught.exception.retryable)
        self.assertEqual(caught.exception.code, 'touch_probe_failed')
        connect.assert_called_once()

    @unittest.skipUnless(hasattr(recovery, 'input_reconciliation_scope'), 'Core has no scene reconciliation')
    def test_native_input_uses_core_scene_and_task_reconciliation_without_replay(self):
        from arknights_mower.utils import config, graph
        from arknights_mower.utils.scene import Scene

        for side_effect in (False, True):
            with self.subTest(side_effect=side_effect):
                bridge, worker, requests = self.exchange({'ok': False, 'error': 'Check failed.'})
                device = object.__new__(AndroidDevice)
                device.bridge = bridge
                device.reconnect = unittest.mock.Mock()
                device.exit = unittest.mock.Mock()
                device.launch = unittest.mock.Mock()
                solver = object.__new__(graph.SceneGraphSolver)
                solver.device = device
                solver.recog = SimpleNamespace(update=unittest.mock.Mock())
                solver.check_current_focus = unittest.mock.Mock()
                solver.sleep = unittest.mock.Mock(side_effect=AssertionError('recognition retry'))
                state = {'scene': Scene.INFRA_ARRANGE_CONFIRM if side_effect else Scene.INDEX}
                solver.scene = lambda state=state: state['scene']
                target = Scene.INFRA_DETAILS if side_effect else Scene.INFRA_MAIN
                calls = []

                def index_to_infra(current, calls=calls, state=state, side_effect=side_effect):
                    calls.append('input')
                    if not side_effect:
                        state['scene'] = Scene.NAVIGATION_BAR
                    current.device.tap((10, 20))

                index_to_infra.__name__ = 'infra_arrange_confirm' if side_effect else 'index_to_infra'

                def nav_index(current, calls=calls, state=state, target=target):
                    calls.append('observed route')
                    state['scene'] = target

                routes = type(graph.DG)()
                routes.add_edge(state['scene'], target, transition=index_to_infra, weight=1)
                if not side_effect:
                    routes.add_edge(Scene.NAVIGATION_BAR, target, transition=nav_index, weight=1)
                try:
                    with patch.object(graph, 'DG', routes), patch.object(graph, 'csleep'), patch.object(config, 'stop_mower', threading.Event()):
                        if side_effect:
                            with self.assertRaises(AndroidTouchFailure) as caught:
                                solver.scene_graph_navigation(target)
                            self.assertEqual(caught.exception.reconciliation, 'task')
                            device.reconnect.assert_not_called()
                        else:
                            solver.scene_graph_navigation(target)
                            device.reconnect.assert_called_once_with()
                finally:
                    worker.join(timeout=3)
                self.assertEqual(calls, ['input'] if side_effect else ['input', 'observed route'])
                self.assertEqual(len(requests), 1)
                device.exit.assert_not_called()
                device.launch.assert_not_called()
                solver.sleep.assert_not_called()

    def test_oversized_frame_is_rejected(self):
        bridge, worker, _ = self.exchange({}, length=100_000_000)
        with self.assertRaisesRegex(BridgeError, 'length'):
            bridge.call('status')
        worker.join()

    @unittest.skipUnless(hasattr(recovery, 'input_reconciliation_scope'), 'Core has no scene reconciliation')
    def test_core_game_restart_confirms_exit_and_launch_before_refreshing_frame(self):
        from arknights_mower.utils.solver import BaseSolver

        for failed_operation in (None, 'exit_game', 'launch'):
            with self.subTest(failed_operation=failed_operation):
                events = []
                device = object.__new__(AndroidDevice)

                def call(method, events=events, failed_operation=failed_operation):
                    events.append(method)
                    if method == failed_operation:
                        raise BridgeError('game state was not confirmed')
                    return True

                device.bridge = unittest.mock.Mock()
                device.bridge.call.side_effect = call
                solver = object.__new__(BaseSolver)
                solver.device = device
                solver.recog = SimpleNamespace(
                    _network_wait_episode='old wait',
                    update=lambda events=events: events.append('fresh frame'),
                )
                if failed_operation is None:
                    solver.restart_game()
                    self.assertEqual(events, ['exit_game', 'launch', 'fresh frame'])
                else:
                    with self.assertRaisesRegex(BridgeError, 'not confirmed'):
                        solver.restart_game()
                    self.assertEqual(events, ['exit_game'] if failed_operation == 'exit_game' else ['exit_game', 'launch'])
                self.assertNotIn('_network_wait_episode', solver.recog.__dict__)

    def test_truncated_frame_is_rejected(self):
        bridge, worker, _ = self.exchange({}, length=25)
        with self.assertRaisesRegex(BridgeError, 'disconnected'):
            bridge.call('status')
        worker.join()


class MaaTests(unittest.TestCase):
    @patch('mower_android.maa_adapter.Bridge')
    def test_completion_callbacks_drained_before_returning_idle(self, factory):
        callbacks = []
        factory.return_value.call.side_effect = [{}, 10, False, {'events': [
            {'id': 11, 'msg': 3, 'details': '{"what":"StageDrops"}'},
            {'id': 12, 'msg': 10002, 'details': '{}'}]}]
        asst = Asst(lambda *args: callbacks.append(args))
        self.assertFalse(asst.running())
        self.assertEqual(len(callbacks), 2)
        self.assertEqual(callbacks[0], (3, b'{"what":"StageDrops"}', None))
        self.assertEqual(asst.cursor, 12)


if __name__ == '__main__':
    unittest.main()
