"""Run screenshot/OCR checks with the installed app's own Python, without printing credentials."""
import argparse
import shlex
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
app = 'io.github.alexsun0.mower.android'
adb = ['adb', '-s', args.serial]
apk = subprocess.check_output(adb + ['shell', 'pm', 'path', app], text=True).strip().removeprefix('package:')
native = apk.rsplit('/', 1)[0] + '/lib/arm64'
files = '/data/user/0/' + app + '/files'
subprocess.run(adb + ['shell', 'run-as', app, 'sh', '-c', shlex.quote('cat > files/device_python_check.py')],
               input=(root / 'scripts/device_python_check.py').read_bytes(), check=True)
env = ['LD_LIBRARY_PATH=' + native + ':' + files + '/exec',
       'PROOT_TMP_DIR=/data/user/0/' + app + '/cache', 'PROOT_LOADER=' + native + '/libproot-loader.so']
command = [native + '/libproot.so', '--kill-on-exit', '-0', '-r', files + '/rootfs',
           '-b', '/dev', '-b', '/proc', '-b', '/sys', '-b', files + ':/android-files',
           '-b', files + '/mower-data:/mower-data', '-w', '/mower', '/usr/bin/env', '-i',
           'PATH=/usr/local/bin:/usr/bin:/bin', 'HOME=/mower-data', 'TZ=Asia/Shanghai',
           'PYTHONPATH=/mower', 'OPENBLAS_NUM_THREADS=2', 'OMP_NUM_THREADS=2',
           '/usr/local/bin/python', '/android-files/device_python_check.py']
# Android's env mistakes APK paths containing '=' for assignments, so export in sh.
shell = 'export ' + shlex.join(env) + '\nexec ' + shlex.join(command)
result = subprocess.run(adb + ['shell', 'run-as', app, 'sh', '-c', shlex.quote(shell)],
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
(root / 'artifacts').mkdir(exist_ok=True)
(root / 'artifacts/device-python-check.log').write_bytes(result.stdout)
print(result.stdout.decode(errors='replace'))
raise SystemExit(result.returncode)
