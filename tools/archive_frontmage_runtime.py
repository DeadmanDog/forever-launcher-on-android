#!/usr/bin/env python3
"""Archive and reread-verify the pinned complete VKD3D source/submodules locally."""
from pathlib import Path
import hashlib, json, sys, tarfile
root=Path(__file__).resolve().parents[1]
dest=Path(sys.argv[1]).resolve();dest.mkdir(parents=True,exist_ok=False)
upstream=root/'docs/dx12/vkd3d-proton'
files={p for p in upstream.rglob('*') if p.is_file() and '.git' not in p.relative_to(upstream).parts}
for relative in ['docs/dx12/BASELINE.json','docs/dx12/aarch64-windows.ini','docs/dx12/client-abi-evidence.json',
 'docs/dx12/dxgi-interface-check.json','docs/dx12/README.md','docs/dx12/upstream-master-titles.txt',
 'docs/dx12/smoke/frontmage-dx12-smoke.cpp','docs/dx12/wcp-stage/profile.json','docs/dx12/wcp-stage/UPSTREAM.json',
 'docs/dx12/releases/r1-licensed/manifest.json','docs/dx12/releases/r1-licensed/stage/UPSTREAM.json']:
 p=root/relative
 if p.is_file():files.add(p)
hashes={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}
archive=dest/'frontmage-dx12-runtime-source.tar.gz'
with tarfile.open(archive,'w:gz',compresslevel=1,dereference=True) as tar:
 for p in sorted(files):tar.add(p,arcname=str(p.relative_to(root)),recursive=False)
with tarfile.open(archive,'r:gz') as tar:
 for member in tar:
  stream=tar.extractfile(member)
  assert stream is not None and hashlib.sha256(stream.read()).hexdigest()==hashes[member.name],member.name
(dest/'files-sha256.json').write_text(json.dumps(hashes,indent=2)+'\n')
info={'file':archive.name,'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),
      'verifiedEntries':len(hashes),'upstreamCommit':'3b10bd7a7ec6a7347e616cf8bea59333afec2255','scope':'Complete VKD3D release/submodules/licences, ABI/interop evidence, native ARM64 smoke source and build inputs. Android integration/build tools are in the separate project source snapshot.'}
(dest/'manifest.json').write_text(json.dumps(info,indent=2)+'\n')
(dest/'SHA256SUMS').write_text(info['sha256']+'  '+archive.name+'\n')
print(json.dumps(info,indent=2))
