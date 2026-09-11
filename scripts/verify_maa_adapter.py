"""Fail publication if the component lacks the native API used by this Python bridge."""
import ast
import json
import re
import shutil
import subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def verify():
    adapter=ast.parse((ROOT/'runtime/mower_android/maa_adapter.py').read_text())
    cls=next(n for n in adapter.body if isinstance(n,ast.ClassDef) and n.name=='Asst')
    available={n.name for n in cls.body if isinstance(n,ast.FunctionDef)}
    facade=ast.parse((ROOT/'runtime/mower_android/maa.py').read_text())
    available.update(n.name for node in facade.body if isinstance(node,ast.ClassDef) for n in node.body if isinstance(n,ast.FunctionDef))
    # Every Asst method referenced by the shipped Mower must exist in the adapter.
    required=set()
    for file in (ROOT/'runtime/arknights_mower').rglob('*.py'):
        if 'tests' in file.parts: continue
        tree=ast.parse(file.read_text(encoding='utf-8-sig'))
        for node in ast.walk(tree):
            if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute):
                value=node.func.value
                if isinstance(value,ast.Name) and value.id=='Asst': required.add(node.func.attr)
                if isinstance(value,ast.Attribute) and value.attr in ('asst','MAA'): required.add(node.func.attr)
    missing=required-available
    if missing: raise ValueError(f'Mower requires new Python adapter methods: {sorted(missing)}')
    lock=json.loads((ROOT/'scripts/maa-android.lock.json').read_text())
    stage=ROOT/'artifacts'/('maa-android-'+lock['sha256'][:12])
    core=next(stage.rglob('libMaaCore.so'))
    reader=shutil.which('readelf') or shutil.which('llvm-readelf')
    if reader:
        command=[reader,'--dyn-syms','--wide',str(core)]
    else:
        reader=shutil.which('llvm-objdump')
        if reader is None: raise ValueError('Install binutils or add the Android NDK llvm-objdump to PATH')
        command=[reader,'--dynamic-syms',str(core)]
    symbols=subprocess.check_output(command,text=True)
    kotlin=(ROOT/'android/app/src/main/java/com/aliothmoon/maameow/mower/AndroidMaaCore.kt').read_text()
    required_native=set(re.findall(r'\.(Asst[A-Z][A-Za-z0-9_]+)\(',kotlin))
    missing=[s for s in required_native if not re.search(r'\b'+re.escape(s)+r'\b',symbols)]
    if missing: raise ValueError(f'MAA core requires a bridge update: {missing}')
    ui=''.join(p.read_text() for p in (ROOT/'runtime/ui/dist/assets').glob('*.js'))
    if 'component_updates' not in ui: raise ValueError('Mower WebUI lacks host component updates; await the compatibility PR release')
    print(f'Verified {len(required)} Mower adapter calls and {len(required_native)} native MAA symbols for {lock["version"]}')


if __name__=='__main__': verify()
