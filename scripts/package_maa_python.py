import hashlib
import json
import zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
code=(root/'runtime/mower_android/maa_adapter.py').read_bytes()
meta={'kind':'mower-maa-python','format':1,'version':'1.0.0','channel':'stable','bridge_protocol':1,'min_apk':5,'sha256':hashlib.sha256(code).hexdigest()}
output=root/'artifacts/mower-maa-python-1.0.0.zip';output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('maa-python.json',json.dumps(meta));z.writestr('maa.py',code)
output.with_suffix('.zip.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n')
print(output.name)
