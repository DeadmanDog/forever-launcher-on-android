#!/usr/bin/env python3
"""Read-only preflight: pinned assets, profile versions and current bundled constants must agree."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import tarfile
import zipfile


def validate_profile(profile, name, code, kind, asset):
    if profile.get('versionName') != name or profile.get('versionCode') != code or profile.get('type') != kind:
        raise ValueError(f'{asset}: expected {kind} {name} / code {code}, got '
                         f'{profile.get("type")} {profile.get("versionName")} / code {profile.get("versionCode")}')


def inspect(root):
    source = (root / 'app/src/main/java/app/gamenative/ui/screen/wow/WowBundledComponents.kt').read_text()
    names = dict(re.findall(r'const val (\w+_NAME) = "([^"]+)"', source))
    codes = {key: int(value) for key, value in re.findall(r'const val (\w+_VERSION_CODE) = (\d+)', source)}
    pattern = r'Bundle\("([^"]+)",\s*(\w+_NAME),\s*"([0-9a-f]{64})",\s*(?:ContentProfile\.ContentType\.CONTENT_TYPE_(\w+)|(null))(?:,\s*\w+(?:\s*=\s*\w+)?)*\)'
    bundles = re.findall(pattern, source)
    if len(bundles) != 4:
        raise ValueError('Expected four bundled component declarations (VKD3D, Proton, DXVK 3.1.1 only, TurnRIP); update this preflight if the schema changes.')
    assets = root / 'app/src/main/assets/bundled_components'
    actual = {p.name for p in assets.iterdir() if p.suffix in ('.wcp', '.zip')}
    expected = {b[0] for b in bundles}
    if actual != expected:
        raise ValueError(f'Bundled file list mismatch: missing={expected-actual}, unlisted={actual-expected}')
    result = []
    for asset, name_key, digest, content_type, _ in bundles:
        path = assets / asset
        with path.open('rb') as f:
            if hashlib.file_digest(f, 'sha256').hexdigest() != digest:
                raise ValueError(f'{asset}: pinned SHA256 mismatch')
        name = names[name_key]
        code = None
        if content_type:
            with path.open('rb') as f:
                magic = f.read(6)
            decoder = ['xz', '-dc'] if magic == b'\xfd7zXZ\x00' else ['zstd', '-q', '-dc']
            process = subprocess.Popen(decoder + [str(path)], stdout=subprocess.PIPE)
            profile = None
            try:
                with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
                    for member in archive:
                        if member.name.removeprefix('./') == 'profile.json':
                            if not member.isfile() or member.size > 1048576:
                                raise ValueError(f'{asset}: invalid profile.json')
                            profile = json.load(archive.extractfile(member))
                while process.stdout.read(65536):
                    pass
            finally:
                process.stdout.close()
                status = process.wait()
            if status != 0 or profile is None:
                raise ValueError(f'{asset}: invalid WCP or missing profile.json')
            code = codes[name_key.removesuffix('_NAME') + '_VERSION_CODE']
            kind = {'PROTON': 'Proton', 'DXVK': 'DXVK', 'VKD3D': 'VKD3D'}[content_type]
            validate_profile(profile, name, code, kind, asset)
        else:
            with zipfile.ZipFile(path) as archive:
                metadata = json.loads(archive.read('meta.json'))
                if metadata.get('name') != name:
                    raise ValueError(f'{asset}: driver name mismatch: expected {name}, got {metadata.get("name")}')
        result.append({'asset': asset, 'name': name, 'versionCode': code, 'sha256': digest})
    return result


if __name__ == '__main__':
    result = inspect(Path(__file__).resolve().parents[1])
    print(json.dumps({'status': 'PASS', 'bundles': result}, indent=2))
