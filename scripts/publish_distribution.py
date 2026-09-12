"""Publish complete, compatible assets atomically and close superseded adapter ranges."""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path
from release_common import ANDROID_REPO, releases, asset, download

ROOT=Path(__file__).resolve().parents[1]
START='<!-- maa-python-compatibility -->'
END='<!-- /maa-python-compatibility -->'


def compatibility(meta, until=None):
    p=meta['maa_python']; c=p['compatibility']
    end=until or c.get('until_android')
    interval=f"从 Android {c['since_android']} 起，至 {end} 之前" if end else f"从 Android {c['since_android']} 起，沿用至下一次 Python 兼容接口变更"
    return (f"{START}\nMAA Python **{p['version']}**：{interval}。\n\n"
            f"适用起点：Mower {c['mower_from']} / MAA {c['maa_from']}；本期构建兼容检查："
            f"Mower {meta['bundled']['mower']['tag']} / MAA {meta['bundled']['maa']['tag']}。\n"
            f"相同接口内容不提示更新；仅提高 APK、Mower 或 MAA 版本不会制造新的 Python 接口版本。\n{END}")


def notes(meta):
    m=meta['bundled']['mower']; maa=meta['bundled']['maa']
    text=f"Arknights Mower Android {meta['apk']['version']}（versionCode {meta['apk']['version_code']}）。\n\n"
    if m.get('source') == 'bundled' and m.get('branch') == 'alpha':
        text+=f"内置 Mower 更新至 **alpha `{m['revision'][:8]}`**（版本基线 {m['tag']}）；MAA 使用公测渠道最新发行 **{maa['tag']}**。\n"
    else:
        text+=f"内置 Mower 更新至 **{m['tag']}**，MAA 更新至 **{maa['tag']}**，均按公测渠道选择最新公测或正式发行。\n"
    text+='\n> 跑单设置提示：Android 推荐保持「跑单前置延时」5 分钟、「葛朗台缓冲时间」15 秒；前者为导航选人留余量，后者为确认入驻留余量。两项均可自行修改；设备较慢时可适当增加。\n'
    if m.get('note'): text+=f"Mower 打包说明：{m['note']}。\n"
    text+=('\n本期包含宿主功能或运行环境更新，可更新 APK。\n' if meta['apk']['host_update'] else '\n本期仅刷新内置组件，已安装相同宿主的用户无需更新 APK，可在 WebUI 分别更新组件。\n')
    text+='\n'+compatibility(meta)+'\n\n'
    if meta['apk']['host_update']:
        text+=(ROOT/'docs/android-release-notes.md').read_text()
    else:
        text+='沿用现有宿主功能；本期更新内容为上述内置组件版本。'
    text+='\n\n附件包含 APK、Python 兼容 ZIP、android-release.json 和 distribution.json。后两者记录版本、兼容范围与构建来源。GitHub 提供文件摘要，不另附 SHA256 文件。\n'
    text+='\nAPK 更新需系统安装确认；Mower、MAA 和 Python 接口可分别更新。内置资源随发行更新；需要较低组件版本时请自行使用对应更新包回退。\n'
    return text


def gh(*args):
    return subprocess.check_output(['gh',*args,'--repo',ANDROID_REPO],text=True)


def publish(directory, tag):
    directory=Path(directory)
    meta=json.loads((directory/'android-release.json').read_text())
    if tag!='v'+meta['apk']['version']: raise ValueError('Release tag differs from built APK')
    required=[meta['apk']['name'],meta['maa_python']['name'],'android-release.json','distribution.json']
    for name in required:
        if not (directory/name).is_file(): raise ValueError(f'Missing release asset: {name}')
    items=releases(ANDROID_REPO)
    existing=next((r for r in items if r['tag_name']==tag),None)
    if existing and not existing['draft']:
        old=json.loads(download(asset(existing,'android-release.json'),directory/'published.json',1024**2).read_text())
        if old!=meta: raise ValueError('Refusing to overwrite an existing public release')
        print('Matching release is already public'); return
    with tempfile.TemporaryDirectory() as temp:
        body=Path(temp)/'release.md'; body.write_text(notes(meta))
        if not existing:
            gh('release','create',tag,'--draft','--target',meta['source_commit'],'--title',f'Mower Android {tag}','--notes-file',str(body))
        else: gh('release','edit',tag,'--notes-file',str(body))
        gh('release','upload',tag,*[str(directory/n) for n in required],'--clobber')
        # Visibility changes only after all packages have uploaded successfully.
        gh('release','edit',tag,'--draft=false','--latest')
        # Only our generated compatibility block is edited; historic v0.1.0 is left intact.
        for release in items:
            oldbody=release.get('body') or ''
            if START not in oldbody or release['tag_name']==tag or release.get('draft'): continue
            old=json.loads(download(asset(release,'android-release.json'),Path(temp)/'old.json',1024**2).read_text())
            p=old.get('maa_python',{})
            if not p.get('compatibility') or p.get('sha256')==meta['maa_python']['sha256']: continue
            # Do not repeatedly move an already closed range.
            block=oldbody.split(START,1)[1].split(END,1)[0]
            if '下一次 Python 兼容接口变更' not in block: continue
            updated=oldbody.split(START,1)[0]+compatibility(old,tag)+oldbody.split(END,1)[1]
            body.write_text(updated)
            gh('release','edit',release['tag_name'],'--notes-file',str(body))
    print(f'Published {tag}')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--directory',default='release-assets');parser.add_argument('--tag',required=True)
    args=parser.parse_args();publish(args.directory,args.tag)
