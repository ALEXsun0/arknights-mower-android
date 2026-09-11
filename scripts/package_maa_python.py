"""Build a reproducible adapter ZIP: identical code and metadata produce identical bytes."""
import hashlib
import json
import zipfile
from pathlib import Path


def build(root):
    root = Path(root)
    code = (root/'runtime/mower_android/maa_adapter.py').read_bytes()
    meta = json.loads((root/'runtime/mower_android/maa-python.json').read_text())
    if meta['sha256'] != hashlib.sha256(code).hexdigest():
        raise ValueError('Run prepare_distribution.py to version the changed Python adapter first')
    output = root/'artifacts'/f"mower-maa-python-{meta['version']}.zip"
    output.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in [('maa-python.json', json.dumps(meta, sort_keys=True).encode()), ('maa.py', code)]:
            entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    output.with_suffix('.zip.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n')
    return output


if __name__ == '__main__':
    print(build(Path(__file__).resolve().parents[1]).name)
