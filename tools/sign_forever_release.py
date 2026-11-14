#!/usr/bin/env python3
"""Re-signs a release APK with the Forever Launcher key and the rotation lineage from the old debug key.

    python3 tools/sign_forever_release.py <apk>

Gradle signs release builds with the debug key (the key every version up to 1.1.2 was published with). This script signs
the APK again: v1/v2 with that old key, v3 with the new "CN=Forever Launcher" key plus the lineage proving the old key
handed over to it. Android 9+ (the app needs 10+) then sees the new key, and installs signed with the old key accept the
update. From now on every published APK must go through this script, and the key folder must never be lost.

Keys: $FOREVER_KEYS_DIR (default ~/forever-keys): debug.keystore.backup, release.jks, release.properties, lineage.bin.
"""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

NEW_SHA256 = "710e65add3f33e333abaf8a5ca28ea0896755befd067c621db3c0e1bc3ab82d2"
OLD_SHA256 = "674473b9c5d8ef5dd534491b3de61c2eabfae6bfdaf064d8694538b6a8d8d3b8"


def apksigner():
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("WOW_SDK") or Path.home() / ".local/share/wow-build-tools/sdk")
    tools = sorted((sdk / "build-tools").glob("*/apksigner"))
    if not tools:
        raise SystemExit(f"apksigner not found under {sdk}/build-tools")
    return str(tools[-1])


def main():
    apk = Path(sys.argv[1]).resolve()
    keys = Path(os.environ.get("FOREVER_KEYS_DIR", Path.home() / "forever-keys"))
    props = dict(line.split("=", 1) for line in (keys / "release.properties").read_text().split("\n") if "=" in line)
    signer = apksigner()
    with tempfile.TemporaryDirectory(prefix="forever-sign-") as tmp:
        new_pw = Path(tmp) / "new"; new_pw.write_text(props["storePassword"])
        old_pw = Path(tmp) / "old"; old_pw.write_text("android")
        out = Path(tmp) / "signed.apk"
        subprocess.run([signer, "sign",
                        "--ks", str(keys / "debug.keystore.backup"), "--ks-key-alias", "androiddebugkey", "--ks-pass", f"file:{old_pw}",
                        "--next-signer",
                        "--ks", str(keys / props["storeFile"]), "--ks-key-alias", props["keyAlias"], "--ks-pass", f"file:{new_pw}",
                        "--lineage", str(keys / "lineage.bin"), "--rotation-min-sdk-version", "28",
                        # v2 carries the old key for tools that do not read v3; Android 9+ uses the v3 block.
                        "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                        "--out", str(out), str(apk)], check=True)
        report = subprocess.run([signer, "verify", "-v", "--print-certs", str(out)], check=True, capture_output=True, text=True).stdout
        if "Verified using v3 scheme (APK Signature Scheme v3): true" not in report or NEW_SHA256 not in report:
            raise SystemExit("signed APK does not verify with the new key:\n" + report)
        lineage = subprocess.run([signer, "lineage", "--in", str(keys / "lineage.bin"), "--print-certs"],
                                 check=True, capture_output=True, text=True).stdout
        if OLD_SHA256 not in lineage or NEW_SHA256 not in lineage:
            raise SystemExit("lineage does not link the old key to the new one")
        out.replace(apk)
    print(f"signed {apk.name}: v3 = CN=Forever Launcher ({NEW_SHA256[:16]}...), lineage from the old key")


if __name__ == "__main__":
    main()
