#!/usr/bin/env python3
"""Turn a translator's line file into the app's language assets.

Input (one entry per line, "\\n" for a line break inside a text):
    <index>|<translation>   index into the ordered key list (ordered_keys.json, see below)
    T|<translation>         the Quick Menu tip on the splash
    R0..R5|<translation>    the release tree's relabelled pacing texts (tools/prepare_forever_release.py)
    res:<name>|<translation>  an Android string resource of the Quick Menu (values-hu keys)

    python3 -B tools/i18n/build_language.py <lang> <file>

Writes app/src/main/assets/i18n/<lang>.json and app/src/main/res/values-<qualifier>/strings.xml; fails on a missing entry or
a placeholder ({0}, %1$d) that does not match the English text.
"""
from pathlib import Path
import json
import re
import sys
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[2]
I18N = ROOT / "tools/i18n"
QUALIFIER = {"de": "de", "fr": "fr", "es": "es", "pt-BR": "pt-rBR", "ru": "ru", "ko": "ko", "zh-CN": "zh-rCN", "zh-TW": "zh-rTW"}
# R0/R1/R5 were the release relabels of the pacing choice; it has one app mode since 1.3.2 (lines kept in the files, ignored).
RETIRED = {"R0", "R1", "R5"}
SPECIAL = {
    "T": "Tip: in the game, swipe back (or press Back) to open the Quick Menu.",
    "R2": "Lowest (default)",
    "R3": None,  # the FIFO latency hint, release wording
    "R4": "Smooth",
}


def placeholders(text):
    return sorted(re.findall(r"\{\d+\}|%\d+\$[ds]", text))


def main():
    lang, file = sys.argv[1], Path(sys.argv[2])
    keys = json.loads((I18N / "ordered_keys.json").read_text())
    source = json.loads((I18N / "source_en.json").read_text())
    release = [k for k, v in source.items() if v["files"] == ["release"]]
    SPECIAL["R3"] = next(k for k in release if k.startswith("Only with FIFO. Smooth:"))
    resources_en = json.loads((I18N / "resources_en.json").read_text())
    table, resources, errors = {}, {}, []
    for line in file.read_text().splitlines():
        if not line.strip():
            continue
        tag, _, text = line.partition("|")
        text = text.replace("\\n", "\n")
        if tag.startswith("res:"):
            name = tag[4:]
            if name not in resources_en:
                errors.append(f"unknown resource {name}")
                continue
            if placeholders(resources_en[name]) != placeholders(text):
                errors.append(f"placeholder mismatch in {name}")
            resources[name] = text
            continue
        if tag in RETIRED:
            continue
        en = SPECIAL.get(tag) if tag in SPECIAL else keys[int(tag)]
        if placeholders(en) != placeholders(text):
            errors.append(f"placeholder mismatch at {tag}: {en!r} -> {text!r}")
        table[en] = text
    missing = [k for k in source if k not in table]
    missing_res = [k for k in resources_en if k not in resources]
    if missing:
        errors.append(f"{len(missing)} texts missing, e.g. {missing[:3]}")
    if missing_res:
        errors.append(f"{len(missing_res)} resources missing, e.g. {missing_res[:3]}")
    if errors:
        print("\n".join(errors))
        sys.exit(1)
    out = ROOT / f"app/src/main/assets/i18n/{lang}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({k: table[k] for k in source}, ensure_ascii=False, indent=0) + "\n")
    res_dir = ROOT / f"app/src/main/res/values-{QUALIFIER[lang]}"
    res_dir.mkdir(parents=True, exist_ok=True)

    def xml_text(value):
        value = escape(value).replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
        return value

    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f"<!-- {lang} texts of the in-game Quick Menu (tools/i18n/build_language.py). The menu follows the in-app language. -->",
             "<resources>"]
    lines += [f'    <string name="{name}">{xml_text(resources[name])}</string>' for name in resources_en]
    lines.append("</resources>")
    (res_dir / "strings.xml").write_text("\n".join(lines) + "\n")
    print(f"{lang}: {len(table)} texts, {len(resources)} resources -> {out.name}, values-{QUALIFIER[lang]}")


if __name__ == "__main__":
    main()
