"""Shared release selection and verified transport for distribution CI."""
from datetime import datetime
import hashlib
import json
import os
import re
import urllib.request
from pathlib import Path

ANDROID_REPO = 'ALEXsun0/arknights-mower-android'
MOWER_REPO = 'ArkMowers/arknights-mower'
MAA_REPO = 'MaaAssistantArknights/MaaAssistantArknights'


def get_json(url):
    headers = {'Accept':'application/vnd.github+json'}
    # Never forward the Actions token to an asset CDN.
    if url.startswith('https://api.github.com/') and os.environ.get('GH_TOKEN'):
        headers['Authorization'] = 'Bearer '+os.environ['GH_TOKEN']
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        return json.load(response)


def releases(repo):
    result = []
    for page in range(1, 11):
        batch = get_json(f'https://api.github.com/repos/{repo}/releases?per_page=100&page={page}')
        result.extend(batch)
        if len(batch) < 100: break
    return result


def version_key(tag):
    match = re.fullmatch(r'v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?', tag)
    if not match: return None
    major, minor, patch, stage, number = match.groups()
    return (int(major), int(minor), int(patch), {None:3,'rc':2,'beta':1,'alpha':0}[stage], int(number or 0))


def latest_beta(items):
    candidates = [r for r in items if not r.get('draft') and version_key(r['tag_name']) is not None]
    if not candidates: raise ValueError('No public beta/stable release available')
    return max(candidates, key=lambda r: (datetime.fromisoformat(r['published_at']), version_key(r['tag_name'])))


def asset(release, name):
    item = next((a for a in release.get('assets', []) if a['name'] == name), None)
    if not item: raise ValueError(f"{release['tag_name']} has not published {name} yet")
    if not re.fullmatch('sha256:[a-f0-9]{64}', item.get('digest') or ''):
        raise ValueError(f'{name}: GitHub digest is not ready')
    return item


def download(item, target, limit=768*1024**2):
    target = Path(target); target.parent.mkdir(parents=True, exist_ok=True)
    digest = item['digest'].removeprefix('sha256:')
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == digest: return target
    url = item['browser_download_url']
    if not url.startswith('https://github.com/'): raise ValueError('Unexpected asset origin')
    temporary = target.with_suffix(target.suffix+'.part')
    try:
        total = 0; sha = hashlib.sha256()
        with urllib.request.urlopen(url, timeout=90) as response, temporary.open('wb') as output:
            while chunk := response.read(256*1024):
                total += len(chunk)
                if total > limit: raise ValueError('Asset exceeds size limit')
                output.write(chunk); sha.update(chunk)
        if total != item['size'] or sha.hexdigest() != digest: raise ValueError('Asset digest/size mismatch')
        temporary.replace(target)
    finally: temporary.unlink(missing_ok=True)
    return target
