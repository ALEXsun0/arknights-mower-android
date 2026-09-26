"""Reconstruct a complete Android Mower package from a published direct OTA."""

import hashlib
import io
import json
import lzma
import shutil
import stat
import tempfile
import zipfile
from contextlib import ExitStack
from pathlib import Path, PurePosixPath

MAX_FILES = 50000
MAX_BYTES = 2 * 1024**3
MAX_RUNTIME_FILES = 100000
MAX_RUNTIME_ARCHIVE = 3 * 1024**3


def _safe_name(name):
    if not isinstance(name, str) or len(name) > 1024:
        raise ValueError('OTA 文件路径无效')
    path = PurePosixPath(name)
    if (path.is_absolute() or path.as_posix() != name or '..' in path.parts or
            '\\' in name or ':' in name or not
            (name.startswith('mower/') or name in ('mower-android.json', 'python-runtime.zip.xz'))):
        raise ValueError('OTA 包含非法文件路径')
    return Path(*path.parts)


def _runtime_name(name, directory=False):
    if not isinstance(name, str) or not name or len(name) > 1024:
        raise ValueError('OTA 运行时路径无效')
    path = PurePosixPath(name)
    if (path.is_absolute() or path.as_posix() != name.rstrip('/') or
            '..' in path.parts or '\\' in name or ':' in name or
            name.endswith('/') != directory or
            (name.startswith(('mower/', 'mower-data/')) and not directory) or
            (name in ('mower', 'mower-data') and not directory)):
        raise ValueError('OTA 运行时包含非法路径')
    return name


def _runtime_manifest(spec):
    if not isinstance(spec, dict) or set(spec) != {'files', 'changed'}:
        raise ValueError('OTA 运行时清单无效')
    files, changed = spec['files'], spec['changed']
    if (not isinstance(files, dict) or not files or len(files) > MAX_RUNTIME_FILES or
            not isinstance(changed, list) or len(changed) != len(set(changed))):
        raise ValueError('OTA 运行时清单无效')
    total = 0
    for name, item in files.items():
        if not isinstance(item, dict) or item.get('type') not in ('dir', 'file'):
            raise ValueError('OTA 运行时文件清单无效')
        _runtime_name(name, item['type'] == 'dir')
        if type(item.get('mode')) is not int or not 0 <= item['mode'] <= 0o777:
            raise ValueError('OTA 运行时文件权限无效')
        if item['type'] == 'dir':
            if set(item) != {'type', 'mode'}:
                raise ValueError('OTA 运行时目录清单无效')
        else:
            if (set(item) != {'type', 'mode', 'size', 'sha256'} or
                    type(item['size']) is not int or not 0 <= item['size'] <= MAX_BYTES or
                    not isinstance(item['sha256'], str) or len(item['sha256']) != 64 or
                    any(c not in '0123456789abcdef' for c in item['sha256'])):
                raise ValueError('OTA 运行时文件摘要无效')
            total += item['size']
            if total > MAX_BYTES:
                raise ValueError('OTA 运行时解压后过大')
    if (any(name not in files or files[name]['type'] != 'file' for name in changed) or
            files.get('.symlinks.json', {}).get('type') != 'file'):
        raise ValueError('OTA 运行时变更清单无效')
    return files, set(changed), total


def _rebuild_runtime(delta, base, destination, spec, expected_size):
    files, changed, total = _runtime_manifest(spec)
    if total != expected_size:
        raise ValueError('OTA 运行时大小与目标版本不一致')
    base = Path(base)
    if base.is_symlink() or not base.is_file():
        raise ValueError('本地 Python 运行时不存在')
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        old_zip = Path(temporary) / 'old.zip'
        new_zip = Path(temporary) / 'new.zip'
        with lzma.open(base, 'rb') as source, old_zip.open('wb') as output:
            expanded = 0
            while chunk := source.read(1024 * 1024):
                expanded += len(chunk)
                if expanded > MAX_RUNTIME_ARCHIVE:
                    raise ValueError('本地 Python 运行时压缩包过大')
                output.write(chunk)
        with zipfile.ZipFile(old_zip) as original, zipfile.ZipFile(new_zip, 'w') as rebuilt:
            names = original.namelist()
            if len(names) != len(set(names)) or len(names) > MAX_RUNTIME_FILES:
                raise ValueError('本地 Python 运行时目录无效')
            for name, item in sorted(files.items()):
                entry = zipfile.ZipInfo(name)
                entry.external_attr = (item['mode'] | (0o040000 if item['type'] == 'dir' else 0o100000)) << 16
                entry.compress_type = zipfile.ZIP_STORED
                if item['type'] == 'dir':
                    rebuilt.writestr(entry, b'')
                    continue
                source = (delta.open('runtime/' + name) if name in changed
                          else original.open(name))
                digest = hashlib.sha256()
                size = 0
                with source, rebuilt.open(entry, 'w', force_zip64=True) as output:
                    while chunk := source.read(1024 * 1024):
                        size += len(chunk)
                        if size > item['size']:
                            raise ValueError('OTA 运行时文件超过清单大小')
                        digest.update(chunk)
                        output.write(chunk)
                if size != item['size'] or digest.hexdigest() != item['sha256']:
                    raise ValueError('OTA 运行时文件 SHA-256 校验失败')
        with new_zip.open('rb') as source, lzma.open(destination, 'wb', preset=6) as output:
            shutil.copyfileobj(source, output, 1024 * 1024)
    with destination.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


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
        format_version = meta.get('format') if isinstance(meta, dict) else None
        expected = {'kind': 'mower-ota',
                    'from': from_version.removeprefix('v'), 'to': to_version.removeprefix('v'),
                    'platform': 'android', 'arch': 'arm64'}
        if format_version not in (1, 2) or any(meta.get(k) != v for k, v in expected.items()):
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
        runtime = meta.get('runtime') if format_version == 2 else None
        if format_version == 2:
            runtime_files, runtime_changed, _ = _runtime_manifest(runtime)
            if 'python-runtime.zip.xz' in changed or 'mower-android.json' not in changed:
                raise ValueError('OTA 运行时外层清单无效')
        else:
            runtime_changed = set()
        payload = {'payload/' + name for name in changed}
        runtime_payload = {'runtime/' + name for name in runtime_changed}
        if set(names) != payload | runtime_payload | {'ota.json'}:
            raise ValueError('OTA 文件内容与清单不一致')
        if sum(delta.getinfo(name).file_size for name in payload | runtime_payload) > MAX_BYTES:
            raise ValueError('OTA 解压后超过限制')
        if any(entry.flag_bits & 1 or stat.S_ISLNK(entry.external_attr >> 16) for entry in entries):
            raise ValueError('OTA 含不支持的 ZIP 条目')

        total = 0
        try:
            with ExitStack() as stack:
                runtime_path = None
                manifest_data = None
                if format_version == 2:
                    temporary = Path(stack.enter_context(tempfile.TemporaryDirectory(dir=output.parent)))
                    original_manifest = delta.read('payload/mower-android.json')
                    if hashlib.sha256(original_manifest).hexdigest() != files['mower-android.json']['sha256']:
                        raise ValueError('OTA Mower 清单 SHA-256 校验失败')
                    target_meta = json.loads(original_manifest)
                    if (not isinstance(target_meta, dict) or target_meta.get('version') != to_version.removeprefix('v') or
                            not isinstance(target_meta.get('runtime'), dict) or
                            target_meta['runtime'].get('sha256') != files['python-runtime.zip.xz']['sha256']):
                        raise ValueError('OTA Mower 清单与目标版本不一致')
                    runtime_path = temporary / 'python-runtime.zip.xz'
                    runtime_sha = _rebuild_runtime(
                        delta, base / 'python-runtime.zip.xz', runtime_path, runtime,
                        target_meta['runtime'].get('unpacked_size'))
                    if runtime_sha == files['python-runtime.zip.xz']['sha256']:
                        manifest_data = original_manifest
                    else:
                        target_meta['runtime']['sha256'] = runtime_sha
                        manifest_data = json.dumps(target_meta, ensure_ascii=False, sort_keys=True).encode()
                complete = stack.enter_context(zipfile.ZipFile(output, 'w'))
                for name, info in sorted(files.items()):
                    if name == 'python-runtime.zip.xz' and runtime_path is not None:
                        source = runtime_path.open('rb')
                    elif name == 'mower-android.json' and manifest_data is not None:
                        source = io.BytesIO(manifest_data)
                    elif name in changed:
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
                    expected_sha = (hashlib.sha256(manifest_data).hexdigest()
                                    if name == 'mower-android.json' and manifest_data is not None else
                                    runtime_sha if name == 'python-runtime.zip.xz' and runtime_path is not None else
                                    info['sha256'])
                    if digest.hexdigest() != expected_sha:
                        raise ValueError('OTA 目标文件 SHA-256 校验失败')
        except Exception:
            output.unlink(missing_ok=True)
            raise
    return output
