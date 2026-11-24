#!/usr/bin/env python3
"""Fills the Android string resources of the 9 UI languages from tools/i18n/res/<lang>.txt ("key|text" lines).

Only keys that a locale lacks are added; a key without a translation (brand names, format-only strings such as "FSR" or
"%1$d FPS") gets the English text. Placeholders must match the English text exactly. Strings that no code or resource uses are
removed from every values*/strings.xml (pass --keep-unused to skip that). Run from the repository root."""
from pathlib import Path
import os, re, sys

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
TR = ROOT / "tools/i18n/res"
LANGS = {"hu": "values-hu", "de": "values-de", "es": "values-es", "fr": "values-fr", "pt-BR": "values-pt-rBR", "ru": "values-ru",
         "ko": "values-ko", "zh-CN": "values-zh-rCN", "zh-TW": "values-zh-rTW"}
STRING = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.S)
PH = re.compile(r'%(?:\d+\$)?[.\d]*[sdf%]')

def esc(v): return v.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'").replace('"', '\\"')
def unesc(v): return v.replace("\\'", "'").replace('\\"', '"')

base_path = RES / "values/strings.xml"
base_txt = base_path.read_text(encoding="utf-8")
base = dict(STRING.findall(base_txt))
code = []
for d, _, fs in os.walk(ROOT / "app/src/main"):
    if "/assets" in d: continue
    for f in fs:
        if f.endswith((".kt", ".java", ".xml")) and f != "strings.xml":
            code.append(Path(d, f).read_text(errors="ignore"))
code = "\n".join(code)
unused = [] if "--keep-unused" in sys.argv else \
    [k for k in base if not (re.search(r"R\.string\." + re.escape(k) + r"\b", code) or ("@string/" + k) in code)]

def drop(txt, keys):
    for k in keys:
        txt = re.sub(r'\n?[ \t]*<string name="' + re.escape(k) + r'"[^>]*>.*?</string>', "", txt, flags=re.S)
    return txt

for lang, folder in LANGS.items():
    path = RES / folder / "strings.xml"
    txt = path.read_text(encoding="utf-8")
    have = dict(STRING.findall(txt))
    tr = dict(l.rstrip("\n").split("|", 1) for l in (TR / f"{lang}.txt").read_text(encoding="utf-8").splitlines() if "|" in l)
    added = []
    for k, v in base.items():
        if k in have or k in unused: continue
        if k in tr:
            if sorted(PH.findall(tr[k])) != sorted(PH.findall(unesc(v))):
                sys.exit(f"{lang}/{k}: placeholders differ: {tr[k]!r} vs {v!r}")
            added.append(f'    <string name="{k}">{esc(tr[k])}</string>')
        else:
            added.append(f'    <string name="{k}">{v}</string>')
    txt = drop(txt, unused)
    if added: txt = txt.replace("</resources>", "\n".join(added) + "\n</resources>")
    path.write_text(txt, encoding="utf-8")
    print(lang, "added", len(added))
base_path.write_text(drop(base_txt, unused), encoding="utf-8")
print("unused removed", len(unused), unused)
