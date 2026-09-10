import json
import socket
import struct
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android.bridge import Bridge, BridgeError
from mower_android.maa import Asst
from unittest.mock import patch


class TransportTests(unittest.TestCase):
    def exchange(self, response, length=None):
        server = socket.socket()
        server.bind(('127.0.0.1', 0)); server.listen(1)
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
        thread = threading.Thread(target=serve); thread.start()
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
            bridge.call('tap', x=10, y=20)
        worker.join()

    def test_oversized_frame_is_rejected(self):
        bridge, worker, _ = self.exchange({}, length=100_000_000)
        with self.assertRaisesRegex(BridgeError, 'length'):
            bridge.call('status')
        worker.join()

    def test_truncated_frame_is_rejected(self):
        bridge, worker, _ = self.exchange({}, length=25)
        with self.assertRaisesRegex(BridgeError, 'disconnected'):
            bridge.call('status')
        worker.join()


class MaaTests(unittest.TestCase):
    @patch('mower_android.maa.Bridge')
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
