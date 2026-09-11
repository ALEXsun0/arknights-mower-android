"""Reject production WebUI builds with missing or external API base URLs."""
import argparse
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('dist', nargs='?', type=Path,
                    default=Path(__file__).resolve().parents[1] / 'runtime/ui/dist')
args = parser.parse_args()
html = (args.dist / 'index.html').read_text()
scripts = re.findall(r'<script\b[^>]*\bsrc=["\']([^"\']+)', html)
if not scripts:
    raise SystemExit('WebUI has no JavaScript entry point')
for script in scripts:
    if not script.startswith('/assets/') or not (args.dist / script.lstrip('/')).is_file():
        raise SystemExit('WebUI entry point is missing or is not served from this app')
source = '\n'.join(file.read_text() for file in (args.dist / 'assets').glob('*.js'))
for endpoint in ('conf', 'shop', 'item', 'operator', 'status', 'plan', 'weekly-plans'):
    if f'undefined/{endpoint}' in source:
        raise SystemExit(f'WebUI API base URL is undefined: {endpoint}')
    if not any(f'{quote}/{endpoint}{quote}' in source for quote in ('"', "'", '`')):
        raise SystemExit(f'WebUI API must use the current origin: {endpoint}')
print('WebUI production entry point and same-origin startup API URLs verified.')
