import json
import os
import socket
import struct


class BridgeError(RuntimeError):
    pass


class Bridge:
    def __init__(self, port=None, token=None, timeout=90):
        self.port = int(port or os.environ['MOWER_BRIDGE_PORT'])
        self.token = token or os.environ['MOWER_BRIDGE_TOKEN']
        self.timeout = timeout

    @staticmethod
    def _read(sock, length):
        data = bytearray()
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                raise BridgeError('Android bridge disconnected')
            data.extend(chunk)
        return bytes(data)

    def call(self, method, **params):
        payload = json.dumps(dict(token=self.token, method=method, params=params)).encode()
        with socket.create_connection(('127.0.0.1', self.port), self.timeout) as sock:
            sock.settimeout(self.timeout)
            sock.sendall(struct.pack('!I', len(payload)) + payload)
            size, = struct.unpack('!I', self._read(sock, 4))
            if not 0 < size <= 32 * 1024 * 1024:
                raise BridgeError('Invalid Android bridge response length')
            response = json.loads(self._read(sock, size))
        if not response.get('ok'):
            raise BridgeError(response.get('error', 'Android operation failed'))
        return response.get('result')
