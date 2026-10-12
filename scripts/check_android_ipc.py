"""Check Python IPC using temporary files, without starting Mower or the game."""
import argparse
import io
import shlex
import subprocess
import tarfile
import uuid
from pathlib import Path

CHECK = '''import json
import multiprocessing
import os

def child(queue):
    queue.put('temporary-ipc-ok')

if __name__ == '__main__':
    context = multiprocessing.get_context('spawn')
    queue = context.Queue()
    process = context.Process(target=child, args=(queue,))
    try:
        process.start()
        assert queue.get(timeout=10) == 'temporary-ipc-ok'
        process.join(10)
        assert process.exitcode == 0
        assert os.stat('/dev/shm').st_mode & 0o777 == 0o700
        print(json.dumps({'spawn_queue': 'passed', 'shared_memory_mode': '0700'}))
    finally:
        if process.is_alive():
            process.terminate()
            process.join(5)
        queue.close()
        queue.join_thread()
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--native-deps', type=Path,
                        help='Optional temporary directory containing the pinned libandroid-shmem.so')
    args = parser.parse_args()
    app = 'io.github.alexsun0.mower.android'
    adb = ['adb', '-s', args.serial]
    apks = subprocess.check_output(adb + ['shell', 'pm', 'path', app], text=True).splitlines()
    apk = next(line.removeprefix('package:') for line in apks if line.endswith('/base.apk'))
    native = apk.rsplit('/', 1)[0] + '/lib/arm64'
    home = '/data/user/0/' + app
    stage = home + '/cache/ipc-check-' + uuid.uuid4().hex

    def shell(command, **kwargs):
        return subprocess.run(adb + ['shell', 'run-as', app, 'sh', '-c', shlex.quote(command)],
                              check=True, **kwargs)

    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode='w') as tar:
        script = CHECK.encode()
        info = tarfile.TarInfo('check.py')
        info.size = len(script)
        tar.addfile(info, io.BytesIO(script))
        if args.native_deps:
            tar.add(args.native_deps / 'libandroid-shmem.so', arcname='native/libandroid-shmem.so')
    try:
        shell('umask 077; mkdir -p ' + shlex.join([stage + '/shm', stage + '/native', stage + '/tmp'])
              + ' && cd ' + shlex.quote(stage) + ' && tar -xf -', input=archive.getvalue(), timeout=30)
        shell('ln -s ' + shlex.join([native + '/libtalloc.so', stage + '/native/libtalloc.so.2']), timeout=15)
        env = ['LD_LIBRARY_PATH=' + stage + '/native:' + native,
               'PROOT_TMP_DIR=' + stage + '/tmp', 'PROOT_LOADER=' + native + '/libproot-loader.so']
        command = ['timeout', '60', native + '/libproot.so', '--kill-on-exit', '-0',
                   '-r', home + '/files/rootfs', '-b', '/dev', '-b', '/proc', '-b', '/sys',
                   '-b', stage + '/shm:/dev/shm', '-b', stage + ':/ipc-check', '-w', '/ipc-check',
                   '/usr/bin/env', '-i', 'PATH=/usr/local/bin:/usr/bin:/bin',
                   'HOME=/ipc-check/tmp', 'TMPDIR=/ipc-check/tmp', 'LANG=C.UTF-8',
                   'PYTHONDONTWRITEBYTECODE=1', '/usr/local/bin/python', '-B', 'check.py']
        print('Temporary IPC check; staged native dependency:', bool(args.native_deps), flush=True)
        shell('export ' + shlex.join(env) + '\n' + shlex.join(command), timeout=90)
    finally:
        shell('rm -rf ' + shlex.quote(stage), timeout=30)


if __name__ == '__main__':
    main()
