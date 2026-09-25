"""Reconstruct a complete Android Mower package from a published direct OTA."""

import hashlib
import json
import stat
import zipfile
from pathlib import Path, PurePosixPath

MAX_FILES = 50000
MAX_BYTES = 2 * 1024**3


def _safe_name(name):
    if not isinstance(name, str) or len(name) > 1024:
        raise ValueError('OTA 文件路径无效')
    path = PurePosixPath(name)
    if (path.is_absolute() or path.as_posix() != name or '..' in path.parts or
            '\\' in name or ':' in name or not
            (name.startswith('mower/') or name in ('mower-android.json', 'python-runtime.zip.xz'))):
        raise ValueError('OTA 包含非法文件路径')
    return Path(*path.parts)


def reconstruct(package, base, output, *, from_version, to_version):
    """Write and verify a full ZIP without changing the active Mower version."""
    base, output = Path(base), Path(output)
    with zipfile.ZipFile(package) as delta:
        entries = delta.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)) or len(names) > MAX_FILES + 1 or 'ota.json' not in names:
            raise ValueError('OTA 文件目录无效')
        if delta.getinfo('ota.json').file_size > 16 * 1024**2:
            raise ValueError('OTA 清单过大')
        meta = json.loads(delta.read('ota.json'))
        expected = {'kind': 'mower-ota', 'format': 1,
                    'from': from_version.removeprefix('v'), 'to': to_version.removeprefix('v'),
                    'platform': 'android', 'arch': 'arm64'}
        if not isinstance(meta, dict) or any(meta.get(k) != v for k, v in expected.items()):
            raise ValueError('OTA 起点、目标或平台不匹配')
        files, changed = meta.get('files'), meta.get('changed')
        if (not isinstance(files, dict) or not files or len(files) > MAX_FILES or
                not isinstance(changed, list) or len(changed) != len(set(changed)) or
                any(name not in files for name in changed) or
                not {'mower-android.json', 'python-runtime.zip.xz'}.issubset(files)):
            raise ValueError('OTA 文件清单无效')
        for name, info in files.items():
            _safe_name(name)
            if (not isinstance(info, dict) or set(info) != {'type', 'sha256', 'mode'} or
                    info['type'] != 'file' or not isinstance(info['sha256'], str) or
                    len(info['sha256']) != 64 or any(c not in '0123456789abcdef' for c in info['sha256']) or
                    type(info['mode']) is not int or not 0 <= info['mode'] <= 0o777):
                raise ValueError('OTA 文件摘要或权限无效')
        payload = {'payload/' + name for name in changed}
        if set(names) != payload | {'ota.json'}:
            raise ValueError('OTA 文件内容与清单不一致')
        if sum(delta.getinfo(name).file_size for name in payload) > MAX_BYTES:
            raise ValueError('OTA 解压后超过限制')
        if any(entry.flag_bits & 1 or stat.S_ISLNK(entry.external_attr >> 16) for entry in entries):
            raise ValueError('OTA 含不支持的 ZIP 条目')

        total = 0
        try:
            with zipfile.ZipFile(output, 'w') as complete:
                for name, info in sorted(files.items()):
                    if name in changed:
                        source = delta.open('payload/' + name)
                    else:
                        path = base / _safe_name(name)
                        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(base.resolve()):
                            raise ValueError('本地 Mower 与 OTA 起点不一致')
                        source = path.open('rb')
                    entry = zipfile.ZipInfo(name)
                    entry.external_attr = (info['mode'] | 0o100000) << 16
                    entry.compress_type = zipfile.ZIP_STORED if name == 'python-runtime.zip.xz' else zipfile.ZIP_DEFLATED
                    digest = hashlib.sha256()
                    with source, complete.open(entry, 'w', force_zip64=True) as target:
                        while chunk := source.read(1024 * 1024):
                            total += len(chunk)
                            if total > MAX_BYTES:
                                raise ValueError('OTA 重建包超过 2 GiB')
                            digest.update(chunk)
                            target.write(chunk)
                    if digest.hexdigest() != info['sha256']:
                        raise ValueError('OTA 目标文件 SHA-256 校验失败')
        except Exception:
            output.unlink(missing_ok=True)
            raise
    return output
