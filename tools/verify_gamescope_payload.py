#!/usr/bin/env python3
"""Offline preflight of the optional Android Gamescope payload and JNI ABI."""
from pathlib import Path, PurePosixPath
import hashlib, json, re, struct, subprocess

ROOT = Path(__file__).resolve().parents[1]
ASSET = 'gamescope-gamenative-20261105.4-wow-dd4-cursor.tzst'
PIN = 'd945e532c6c44b90dcc3ba780e7e5e7e69f0e5b97ea2bd4afc2eb672194d0629'
NATIVE_PIN = '08d6276c268ed9ff169a0cc76f059bf7e30dec2288f13617bfa64c6f4ff407f6'

def inspect_randr(path):
    data = path.read_bytes()
    assert data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<HH',data,16) == (3,183), 'XRandR must be Android ARM64 ELF'
    dynamic = subprocess.check_output(['readelf','-d',str(path)],text=True)
    needed = set(re.findall(r'NEEDED.*?\[([^]]+)\]', dynamic))
    expected = {'libX11.so', 'libXext.so', 'libXrender.so', 'libc.so'}
    assert needed == expected, f'XRandR mixes X11 stacks: {needed}; expected {expected}'
    assert re.search(r'SONAME.*?\[libXrandr\.so\]', dynamic), 'Wrong XRandR SONAME'
    symbols = subprocess.check_output(['readelf','--dyn-syms','--wide',str(path)],text=True)
    for name in ['XRRGetScreenResources', 'XRRGetScreenResourcesCurrent', 'XRRGetCrtcInfo',
                 'XRRGetOutputInfo', 'XRRGetScreenInfo', 'XRRGetProviderResources',
                 'XRRQueryVersion', 'XRRSetCrtcConfig']:
        assert re.search(r'\b'+name+r'\b', symbols), f'XRandR export missing: {name}'
    return sorted(needed)

def main():
    asset = ROOT / 'app/src/main/assets' / ASSET
    native = ROOT / 'app/src/main/jniLibs/arm64-v8a/libgamenative_drm.so'
    policy = (ROOT/'app/src/main/java/app/gamenative/display/WowGamescopePayload.kt').read_text()
    assert ASSET in policy and PIN in policy, 'App/payload pin mismatch'
    assert hashlib.sha256(asset.read_bytes()).hexdigest() == PIN, 'Gamescope archive changed'
    randr_policy = (ROOT/'app/src/main/java/app/gamenative/display/GamescopeRandrLibrary.kt').read_text()
    randr_asset = re.search(r'const val ASSET = "([^"]+)"', randr_policy).group(1)
    randr_pin = re.search(r'const val SHA256 = "([a-f0-9]{64})"', randr_policy).group(1)
    randr = ROOT/'app/src/main/assets'/randr_asset
    assert hashlib.sha256(randr.read_bytes()).hexdigest() == randr_pin, 'XRandR asset hash mismatch'
    randr_needed = inspect_randr(randr)
    assert (ROOT/'app/src/main/assets/licenses/gamescope-libXrandr.txt').is_file()
    data = native.read_bytes()
    assert hashlib.sha256(data).hexdigest() == NATIVE_PIN, 'JNI bridge version mismatch'
    assert data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<HH',data,16) == (3,183), 'Expected ARM64 shared library'
    names = subprocess.check_output(['tar','--zstd','--warning=no-unknown-keyword','-tf',str(asset)],text=True).splitlines()
    for n in names:
        p = PurePosixPath(n)
        assert not p.is_absolute() and '..' not in p.parts, f'Unsafe archive entry: {n}'
    for n in ['bin/gn-xtree-probe','bin/gn-scaling-mode','bin/gamescope','bin/gamescopereaper','bin/Xwayland','lib/libgndrm_shim.so','lib/libadrenotools_gamescope.so']:
        assert n in names, f'Missing payload file: {n}'
    symbols = subprocess.check_output(['readelf','--dyn-syms','--wide',str(native)],text=True)
    bindings = (ROOT/'app/src/main/java/app/gamenative/display/GameNativeDrm.kt').read_text()
    for name in re.findall(r'external fun (\w+)', bindings):
        assert 'Java_app_gamenative_display_GameNativeDrm_'+name in symbols, f'JNI method missing: {name}'
    print(json.dumps({'status':'PASS','archive':ASSET,'sha256':PIN,'bridgeSha256':NATIVE_PIN,'jni':'ARM64 / required exports present','randrSha256':randr_pin,'randrNeeded':randr_needed},indent=2))

if __name__ == '__main__':
    main()
