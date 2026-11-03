"""Apply only named, host-tested candidate clusters after verifying baseline hashes."""
from pathlib import Path
import sys,json,hashlib
root=Path(__file__).resolve().parents[2];out=Path(sys.argv[1]).resolve();group=sys.argv[2]
groups={
 'gl-state':['renderer/Texture.java','renderer/VertexAttribute.java','renderer/material/ShaderMaterial.java'],
 'effect-targets':['renderer/EffectComposer.java'],
 'window-events':['xserver/XClient.java','xserver/WindowManager.java'],
 'shm-protocol':['xserver/extensions/MITSHMExtension.java','xserver/events/ShmCompletionNotify.java'],
 'surface-geometry':['renderer/ASurfaceRenderer.java'],
}
report=json.loads((out/'evidence/candidate-final/result.json').read_text());assert all(r['exit']==0 for r in report['results'])
baseline=json.loads((out/'evidence/source-before.json').read_text());changes=[]
for name in groups[group]:
 relative='app/src/main/java/com/winlator/'+name;dest=root/relative;src=out/'candidate'/relative
 if relative in baseline:assert hashlib.sha256(dest.read_bytes()).hexdigest()==baseline[relative], 'Changed since baseline: '+relative
 else:assert not dest.exists(),relative
 assert hashlib.sha256(src.read_bytes()).hexdigest()==report['sourceHashes'][name], 'Untested candidate change: '+name
 changes.append((src,dest))
for src,dest in changes:dest.write_bytes(src.read_bytes());print(dest.relative_to(root))
