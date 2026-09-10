FROM python:3.12-slim-bookworm
ENV DEBIAN_FRONTEND=noninteractive PIP_DISABLE_PIP_VERSION_CHECK=1
RUN apt-get update && apt-get install -y --no-install-recommends libglib2.0-0 libgl1 libzbar0 ca-certificates git && rm -rf /var/lib/apt/lists/*
COPY runtime/requirements.in /tmp/requirements.in
RUN python - <<'PY'
from pathlib import Path
s=Path('/tmp/requirements.in').read_text(encoding='utf-8-sig')
exclude=('pywebview','pystray','PyAutoGUI')
s='\n'.join(l for l in s.splitlines() if not l.startswith(exclude))
s=s.replace('opencv-python==','opencv-python-headless==').replace('ddddocr>=1.4.7','ddddocr==1.5.6')
Path('/tmp/android-requirements.txt').write_text(s)
PY
RUN pip install --no-cache-dir -r /tmp/android-requirements.txt
RUN mkdir -p /mower /mower-data /bridge /host-dev /host-proc && chmod 1777 /tmp
WORKDIR /mower
