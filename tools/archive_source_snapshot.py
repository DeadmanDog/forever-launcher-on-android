#!/usr/bin/env python3
"""Add a verified, local project-file snapshot to an existing APK archive."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import tarfile

root = Path(__file__).resolve().parents[1]
dest = Path(sys.argv[1]).resolve()
if dest.parent != root / 'docs/apk-archive' or not (dest / 'manifest.json').is_file():
    raise SystemExit('Expected an existing APK archive directory.')
archive = dest / 'project-source.tar.gz'
if archive.exists():
    raise SystemExit('Source snapshot already exists; refusing to overwrite it.')

paths = set(subprocess.check_output(
    ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root
).decode().split('\0'))
# Include ignored runtime assets and native payloads needed by this checkout.
paths.update(str(p.relative_to(root)) for p in (root / 'app/src').rglob('*') if p.is_file())
paths.update(str(p.relative_to(root)) for p in (root / 'docs').glob('*.md'))
paths.discard('')
paths.discard('local.properties')
files = sorted(p for p in paths if (root / p).is_file()
               and not p.startswith('docs/apk-archive/'))
hashes = {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in files}
expected = json.loads((dest / 'source-sha256.json').read_text())
for name, sha in expected.items():
    if hashes.get(name) != sha:
        raise SystemExit(f'APK source snapshot mismatch: {name}')
partial = dest / 'project-source.tar.gz.partial'
try:
    with tarfile.open(partial, 'w:gz', compresslevel=1, dereference=True) as tar:
        for name in files:
            tar.add(root / name, arcname=name, recursive=False)
    with tarfile.open(partial, 'r:gz') as tar:
        for member in tar.getmembers():
            content = tar.extractfile(member)
            if content is None or hashlib.sha256(content.read()).hexdigest() != hashes[member.name]:
                raise RuntimeError(f'Archive verification failed: {member.name}')
    partial.rename(archive)
except BaseException:
    partial.unlink(missing_ok=True)
    raise

(dest / 'project-files-sha256.json').write_text(json.dumps(hashes, indent=2) + '\n')
sha = hashlib.sha256(archive.read_bytes()).hexdigest()
with (dest / 'SHA256SUMS').open('a') as sums:
    sums.write(f'{sha}  {archive.name}\n')
(dest / 'SOURCE_RESTORE.md').write_text('''# Local source restoration

Verify `sha256sum -c SHA256SUMS` in this directory. Extract
`project-source.tar.gz` into a NEW, EMPTY directory, never over the working checkout:

```sh
mkdir /tmp/wow-restored-checkout
tar -xzf project-source.tar.gz -C /tmp/wow-restored-checkout
```

This snapshot includes project files, uncommitted/new sources, tests and the
current app/src assets/native payloads. It excludes Git history, local.properties,
SDK/JDK/Gradle caches and build outputs. Use the project's normal build toolchain;
this is a source snapshot, not a guarantee of a byte-identical rebuild.
`project-files-sha256.json` verifies the extracted file contents.
It does not restore Android app data, preferences, game files, imported runtime
components or device prefixes. Earlier APK archives without this tarball contain
only partial source provenance; do not assume their untracked files are recoverable.
''')
print(f'Verified source snapshot: {archive} ({len(files)} files)')
