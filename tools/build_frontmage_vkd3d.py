#!/usr/bin/env python3
"""Build native Windows ARM64 VKD3D 3.0.1 and package an immutable local WCP.

No Android NDK is used for the Windows DLLs. See docs/DX12_REPORT.md for the
pinned source/submodules/tool versions. Output must be a NEW directory.
"""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, tarfile

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
p.add_argument('--build-dir', type=Path, default=root/'docs/dx12/build-vkd3d-arm64')
p.add_argument('--llvm-mingw', type=Path, default=Path('/home/fsos-x/WoW-Proton/vegas-pipeline-diagnostic/llvm-mingw-20250709-ucrt-ubuntu-22.04-x86_64'))
p.add_argument('--wine-tools', type=Path, default=root/'docs/dx12/toolchains/wine-tools')
p.add_argument('--skip-build', action='store_true')
a = p.parse_args(); out = a.output.resolve(); out.mkdir(parents=True, exist_ok=False)
source = root/'docs/dx12/vkd3d-proton'; compiler = a.llvm_mingw/'bin'
run = lambda command: subprocess.run([str(x) for x in command], check=True, cwd=root)
if not a.skip_build:
    cross = out/'aarch64-windows.ini'
    lines = ['[binaries]']
    names = {'c':'aarch64-w64-mingw32-clang', 'cpp':'aarch64-w64-mingw32-clang++', 'ar':'llvm-ar', 'strip':'llvm-strip', 'windres':'aarch64-w64-mingw32-windres'}
    lines += [f"{k} = '{compiler/v}'" for k,v in names.items()]
    lines += [f"widl = ['{a.wine_tools/'usr/lib/wine/widl'}', '-m64', '-I{a.wine_tools/'usr/include/wine/wine/windows'}']", '[properties]', 'needs_exe_wrapper = true', '[host_machine]', "system = 'windows'", "cpu_family = 'aarch64'", "cpu = 'aarch64'", "endian = 'little'"]
    cross.write_text('\n'.join(lines)+'\n')
    if not (a.build_dir/'build.ninja').exists():
        run(['meson','setup',a.build_dir,source,'--cross-file',cross,'--buildtype','release','-Db_lto=false','-Denable_tests=true','-Denable_trace=false'])
    run(['meson','compile','-C',a.build_dir,'-j8'])
stage = out/'stage'; (stage/'system32').mkdir(parents=True)
metadata = json.loads((root/'docs/dx12/wcp-stage/profile.json').read_text())
upstream = json.loads((root/'docs/dx12/wcp-stage/UPSTREAM.json').read_text())
for name, directory in [('d3d12.dll','d3d12'),('d3d12core.dll','d3d12core')]:
    target = stage/'system32'/name; shutil.copy2(a.build_dir/'libs'/directory/name,target)
    run([compiler/'llvm-strip','--strip-debug',target])
    report = subprocess.check_output([str(compiler/'llvm-readobj'),'--file-headers','--coff-exports','--coff-imports',str(target)], text=True)
    assert 'IMAGE_FILE_MACHINE_ARM64 (0xAA64)' in report and 'IMAGE_FILE_DLL' in report
    (out/(name+'.pe.txt')).write_text(report)
for name in ['COPYING','LICENSE','AUTHORS']:
    shutil.copy2(source/name,stage/name)
licenses = {}
for file in source.rglob('*'):
    if not file.is_file() or '.git' in file.parts: continue
    if file.name.lower().startswith(('license','copying','notice')):
        relative = file.relative_to(source)
        target = stage/'licenses'/relative; target.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(file,target)
        licenses[str(relative)] = hashlib.sha256(file.read_bytes()).hexdigest()
upstream['licenses'] = licenses
upstream['dllSHA256'] = {name:hashlib.sha256((stage/'system32'/name).read_bytes()).hexdigest() for name in ['d3d12.dll','d3d12core.dll']}
upstream['build'] = {'LLVM-MinGW':'20250709 UCRT / Clang20.1.8','Meson':'1.3.2','configuration':'release, b_lto=false, enable_tests=true, enable_trace=false','note':'Procedural source rebuild. Original PE link timestamps are retained; byte-identical DLL rebuild is not claimed.'}
(stage/'profile.json').write_text(json.dumps(metadata,indent=2)+'\n')
(stage/'UPSTREAM.json').write_text(json.dumps(upstream,indent=2)+'\n')
tar = out/'FrontMage-VKD3D-Proton-ARM64-r1.tar'
with tarfile.open(tar,'w',format=tarfile.PAX_FORMAT) as archive:
    for file in sorted(stage.rglob('*')):
        if not file.is_file(): continue
        info=archive.gettarinfo(str(file),arcname=str(file.relative_to(stage)))
        info.uid=info.gid=0; info.uname=info.gname=''; info.mtime=0; info.mode=0o644; info.pax_headers={}
        with file.open('rb') as stream: archive.addfile(info,stream)
wcp=out/'FrontMage-VKD3D-Proton-ARM64-r1.wcp'
run(['zstd','-19','-T2','--check','--long=23',tar,'-o',wcp])
run(['zstd','-t',wcp])
manifest={'file':wcp.name,'bytes':wcp.stat().st_size,'sha256':hashlib.sha256(wcp.read_bytes()).hexdigest(),'upstreamVersion':'3.0.1','upstreamCommit':metadata['frontmage']['upstreamCommit'],'architecture':'IMAGE_FILE_MACHINE_ARM64/AA64 (classic ABI), not ARM64EC'}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest,indent=2))
