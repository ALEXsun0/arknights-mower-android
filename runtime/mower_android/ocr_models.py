"""Convert official OCR models for Android's NCNN backend, including after updates.

The FP32 input shapes follow MAA-Meow's documented Android conversion recipe.
Model sources remain the ONNX files in the verified official MAA component.
"""
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


def prepare_models(resource):
    import importlib.util
    spec = importlib.util.find_spec('pnnx')
    if spec is None: raise RuntimeError('缺少 Android OCR 模型转换器')
    executable = Path(spec.origin).parent / 'pnnx'
    for model in sorted(Path(resource).rglob('inference.onnx')):
        kind = model.parent.name
        if kind not in ('det', 'rec'): continue
        identity = hashlib.sha256(model.read_bytes()).hexdigest() + '-pnnx20260526-fp32-v1'
        marker = model.parent / '.mower-ncnn.json'
        outputs = [model.parent / f'{kind}.ncnn.{ext}' for ext in ('param', 'bin')]
        if marker.exists() and marker.read_text() == identity and all(p.is_file() for p in outputs): continue
        with tempfile.TemporaryDirectory(prefix='mower-ocr-') as work:
            source = Path(work) / f'{kind}.onnx'; shutil.copyfile(model, source)
            shape = '[1,3,640,640]' if kind == 'det' else '[1,3,48,320]'
            result = subprocess.run([str(executable), source.name, f'inputshape={shape}', 'fp16=0'],
                                    cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
            if result.returncode: raise RuntimeError(f'Android OCR 模型转换失败：{model.parent.name}')
            for output in outputs:
                generated = Path(work) / f'{kind}.ncnn.{output.suffix[1:]}'
                if not generated.is_file() or not generated.stat().st_size: raise RuntimeError('OCR 模型转换未生成完整结果')
                shutil.copyfile(generated, output)
            marker.write_text(identity)
