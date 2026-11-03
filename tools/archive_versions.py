"""Local archive versions; independent of the Android version embedded in an APK."""
from pathlib import Path
import json
import os
import re


def next_version(archive_root: Path) -> str:
    versions = []
    used = set()
    for manifest in archive_root.glob('*/manifest.json'):
        value = json.loads(manifest.read_text()).get('archiveVersion')
        used.add(value)
        match = re.fullmatch(r'0\.1\.(\d+)', value or '')
        if match:
            versions.append(int(match.group(1)))
        elif not re.fullmatch(r'\d+\.\d+(?:\.\d+)?', value or ''):
            raise ValueError(f'Missing or unsupported archive version: {manifest}')
    # An explicit release number (WOW_ARCHIVE_VERSION=1.0) is allowed once; versions are never reused.
    explicit = os.environ.get('WOW_ARCHIVE_VERSION')
    if explicit:
        if not re.fullmatch(r'\d+\.\d+(?:\.\d+)?', explicit) or explicit in used:
            raise ValueError(f'Invalid or already used archive version: {explicit}')
        return explicit
    return f'0.1.{max(versions, default=0) + 1}'


def write_catalog(root: Path) -> None:
    rows = []
    for path in sorted((root / 'docs/apk-archive').glob('*/manifest.json')):
        info = json.loads(path.read_text())
        version = info['archiveVersion']
        android = f"{info.get('androidVersionName', '?')} ({info.get('androidVersionCode', '?')})"
        directory = path.parent.relative_to(root / 'docs')
        apk = f"[{version}]({directory}/{info['apk']})"
        notes = f'[Notes]({directory}/BUILD_NOTES.md)' if (path.parent / 'BUILD_NOTES.md').exists() else '—'
        source = f'[Source]({directory}/project-source.tar.gz)' if (path.parent / 'project-source.tar.gz').exists() else 'Nincs teljes mentés / No complete snapshot'
        rows.append(f"| {apk} | {info['archivedAt'][:16]} | {android} | {info['label']} | {notes} | {source} |")
    (root / 'docs/RELEASES.md').write_text('''# WoW Forever — local build versions

Archive versions identify successive saved builds. They do not modify the Android
versionName/versionCode inside existing APKs. Versions are assigned retrospectively
to the four existing archives; earlier development stages without archived APKs
are covered by cumulative patch notes, not invented release entries.
Future archives increment the patch number; never reuse a version.

| Archive version / APK | Saved at (Europe/Budapest) | Android version (code) | Build | Notes | Sources |
| --- | --- | --- | --- | --- | --- |
''' + '\n'.join(rows) + '\n')
