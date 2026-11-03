#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib,json,shutil,subprocess
from archive_versions import next_version, write_catalog
root=Path(__file__).resolve().parents[1]
variant=__import__('sys').argv[2] if len(__import__('sys').argv)>2 else 'modernDebug'
if variant not in ('modernDebug', 'modernProfile', 'modernRelease'):
    raise SystemExit('Supported variants: modernDebug, modernProfile, modernRelease')
build_type=variant.removeprefix('modern').lower()
apk=root/f'app/build/outputs/apk/modern/{build_type}/app-modern-{build_type}.apk' 
label=__import__('sys').argv[1]
import re
if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]{0,80}', label):
    raise SystemExit('Use a short label containing letters, digits, underscores or hyphens.')
notes=root/'docs/BUILD_NOTES.md'
if label!='previous-instrumented' and not notes.is_file():
    raise SystemExit('Write docs/BUILD_NOTES.md describing this build before archiving.')
data=apk.read_bytes();sha=hashlib.sha256(data).hexdigest()
archive_version=next_version(root/'docs/apk-archive')
stamp=datetime.now(ZoneInfo('Europe/Budapest')).strftime('%Y%m%d-%H%M%S-%f')
dest=root/'docs/apk-archive'/f'{stamp}-{variant}-{label}-{sha[:12]}'
dest.mkdir(exist_ok=False)
copy=dest/f'wow-forever-v{archive_version}-{variant}-{label}.apk';shutil.copy2(apk,copy)
metadata=root/f'app/build/outputs/apk/modern/{build_type}/output-metadata.json'
if metadata.exists():shutil.copy2(metadata,dest/metadata.name)
info={'archivedAt':datetime.now(ZoneInfo('Europe/Budapest')).isoformat(),'variant':variant,'label':label,'apk':copy.name,'bytes':len(data),'MiB':len(data)/1048576,'sha256':sha,'gitHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'sourceState': 'Pre-build APK: originating source snapshot unavailable' if label=='previous-instrumented' else 'See source-sha256.json and git-state.txt; includes uncommitted changes'}
element=json.loads(metadata.read_text())['elements'][0] if metadata.exists() else {}
info.update(archiveVersion=archive_version, androidVersionName=element.get('versionName'), androidVersionCode=element.get('versionCode'))
(dest/'VERSION.txt').write_text(f"WoW Forever archive version: {archive_version}\nAndroid version: {info['androidVersionName']} (code {info['androidVersionCode']})\nVariant: {variant}\n")
(dest/'manifest.json').write_text(json.dumps(info,indent=2)+'\n')
(dest/'SHA256SUMS').write_text(f'{sha}  {copy.name}\n')
if label!='previous-instrumented':
 shutil.copy2(notes,dest/'BUILD_NOTES.md')
 for name in ('PATCH_NOTES_HU.md','PATCH_NOTES_EN.md'):
  if (root/'docs'/name).is_file():shutil.copy2(root/'docs'/name,dest/name)
 sources={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'app/src').rglob('*') if p.is_file() and '/out/' not in str(p)}
 (dest/'source-sha256.json').write_text(json.dumps(sources,indent=2)+'\n')
 (dest/'git-state.txt').write_text(subprocess.check_output(['git','status','--short'],cwd=root,text=True))
 (dest/'tracked-changes.patch').write_bytes(subprocess.check_output(['git','diff','HEAD','--binary'],cwd=root))
 subprocess.run(['python3','-B',str(root/'tools/archive_source_snapshot.py'),str(dest)],cwd=root,check=True)
assert hashlib.sha256(copy.read_bytes()).hexdigest()==sha
write_catalog(root)
print(str(dest));print(json.dumps(info))
